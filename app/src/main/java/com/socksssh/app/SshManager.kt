// Created by opencode on 2026-05-29
package com.socksssh.app

import com.jcraft.jsch.Channel
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class SshManager(
    private val config: SshConfig,
    private val onLog: (String) -> Unit,
    private val onStateChange: (ConnectionState) -> Unit
) {
    private var session: Session? = null
    private var socksProxy: Socks5Proxy? = null
    private var workerThread: Thread? = null

    @Volatile
    var isRunning = false
        private set

    fun start() {
        if (isRunning) return
        isRunning = true
        onStateChange(ConnectionState.CONNECTING)
        onLog("Starting SSH connection to ${config.serverAddress}:${config.serverPort}...")
        workerThread = Thread {
            run()
        }.apply {
            isDaemon = true
            name = "ssh-manager"
            start()
        }
    }

    fun stop() {
        isRunning = false
        workerThread?.interrupt()
        workerThread = null
        socksProxy?.stop()
        socksProxy = null
        disconnect()
        onStateChange(ConnectionState.DISCONNECTED)
        onLog("Disconnected")
    }

    private fun run() {
        while (isRunning && !Thread.currentThread().isInterrupted) {
            try {
                connect()
                socksProxy = Socks5Proxy(session!!, config.localPort, onLog)
                socksProxy!!.start()
                onStateChange(ConnectionState.CONNECTED)
                onLog("Connected. SOCKS5 proxy on 0.0.0.0:${config.localPort}")

                while (isRunning && !Thread.currentThread().isInterrupted) {
                    Thread.sleep(config.healthCheckPeriod * 1000L)
                    if (!isRunning) break
                    if (!checkHealth()) {
                        onLog("Healthcheck failed")
                        onStateChange(ConnectionState.RECONNECTING)
                        break
                    }
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                onLog(e.message ?: "Connection error")
            } finally {
                socksProxy?.stop()
                socksProxy = null
                disconnect()
            }

            if (isRunning && !Thread.currentThread().isInterrupted) {
                onLog("Reconnecting in 5s...")
                try {
                    Thread.sleep(5000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        onStateChange(ConnectionState.DISCONNECTED)
    }

    private fun connect() {
        val jsch = JSch()

        if (config.privateKey.isNotBlank()) {
            jsch.addIdentity("ssh-key", config.privateKey.toByteArray(), null, null)
        }

        val sess = jsch.getSession(config.login, config.serverAddress, config.serverPort)

        if (config.password.isNotBlank()) {
            sess.setPassword(config.password)
        }

        sess.setConfig("StrictHostKeyChecking", "no")
        sess.setConfig("ServerAliveInterval", "15")
        sess.setConfig("ServerAliveCountMax", "3")

        parseExtraParams(config.extraParams).forEach { (key, value) ->
            sess.setConfig(key, value)
        }

        sess.connect(30000)
        session = sess
    }

    private fun disconnect() {
        try {
            session?.disconnect()
        } catch (_: Exception) {
        }
        session = null
    }

    private fun checkHealth(): Boolean {
        return try {
            val proxy = java.net.Proxy(
                java.net.Proxy.Type.SOCKS,
                InetSocketAddress("127.0.0.1", config.localPort)
            )
            val socket = Socket(proxy)
            socket.connect(InetSocketAddress("1.1.1.1", 53), 5000)
            socket.close()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun parseExtraParams(params: String): Map<String, String> {
        if (params.isBlank()) return emptyMap()
        val map = mutableMapOf<String, String>()
        val parts = params.trim().split("\\s+".toRegex())
        var i = 0
        while (i < parts.size) {
            when (parts[i]) {
                "-o", "-O" -> {
                    if (i + 1 < parts.size) {
                        val kv = parts[i + 1].split("=", limit = 2)
                        if (kv.size == 2) map[kv[0]] = kv[1]
                        i += 2
                    } else i++
                }
                "-C" -> {
                    map["compression.s2c"] = "zlib@openssh.com,zlib"
                    map["compression.c2s"] = "zlib@openssh.com,zlib"
                    i++
                }
                "-v" -> {
                    onLog("Verbose mode enabled (JSch debug-level logging)")
                    i++
                }
                else -> i++
            }
        }
        return map
    }
}

internal class Socks5Proxy(
    private val session: Session,
    private val localPort: Int,
    private val onLog: (String) -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()

    fun start() {
        serverSocket = ServerSocket().apply { bind(InetSocketAddress("0.0.0.0", localPort)) }
        onLog("SOCKS5 listening on 0.0.0.0:$localPort")
        executor.submit {
            try {
                while (!serverSocket!!.isClosed) {
                    val client = serverSocket!!.accept()
                    executor.submit { handleClient(client) }
                }
            } catch (_: Exception) {
            }
        }
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        executor.shutdownNow()
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 30000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            input.read()
            val nMethods = input.read()
            input.readNBytes(nMethods)
            output.write(byteArrayOf(5, 0))

            input.read()
            val cmd = input.read()
            input.read()

            if (cmd != 1) {
                output.write(buildSocks5Response(7))
                socket.close()
                return
            }

            val addrType = input.read()
            val (host, port) = when (addrType) {
                1 -> {
                    val addr = input.readNBytes(4)
                    InetAddress.getByAddress(addr).hostAddress to readShort(input)
                }
                3 -> {
                    val len = input.read()
                    String(input.readNBytes(len)) to readShort(input)
                }
                4 -> {
                    val addr = input.readNBytes(16)
                    InetAddress.getByAddress(addr).hostAddress to readShort(input)
                }
                else -> {
                    output.write(buildSocks5Response(8))
                    socket.close()
                    return
                }
            }

            val channel = session.getStreamForwarder(host, port) as Channel
            channel.setInputStream(input)
            channel.connect(30000)

            output.write(buildSocks5Response(0))
            output.flush()

            channel.setOutputStream(output)

            while (channel.isConnected && !socket.isClosed) {
                Thread.sleep(500)
            }
        } catch (_: Exception) {
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun readShort(input: java.io.InputStream): Int {
        val b = input.readNBytes(2)
        return (b[0].toInt() and 0xFF) shl 8 or (b[1].toInt() and 0xFF)
    }

    private fun buildSocks5Response(reply: Int): ByteArray {
        return byteArrayOf(5, reply.toByte(), 0, 1, 0, 0, 0, 0, 0, 0)
    }
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING
}
