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
                socksProxy = Socks5Proxy(session!!, config.localPort, config.verbose, onLog)
                socksProxy!!.start()
                onStateChange(ConnectionState.CONNECTED)
                onLog("Connected. SOCKS5 proxy on 0.0.0.0:${socksProxy!!.boundPort}")

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

        if (config.useCompression) {
            sess.setConfig("compression.s2c", "zlib@openssh.com,zlib")
            sess.setConfig("compression.c2s", "zlib@openssh.com,zlib")
        }

        if (config.verbose) {
            onLog("Verbose mode enabled (JSch debug-level logging)")
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
        return session?.isConnected == true && socksProxy?.isRunning == true
    }

}

internal class Socks5Proxy(
    private val session: Session,
    private val localPort: Int,
    private val verbose: Boolean,
    private val onLog: (String) -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()

    val boundPort: Int get() = serverSocket?.localPort ?: -1
    val isRunning: Boolean get() = serverSocket != null && !serverSocket!!.isClosed

    fun start() {
        val port = if (localPort in 1..65535) localPort else 1080
        serverSocket = ServerSocket().apply { bind(InetSocketAddress("0.0.0.0", port)) }
        onLog("SOCKS5 listening on 0.0.0.0:${serverSocket!!.localPort}")
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

            val ver = input.read()
            if (ver != 5) {
                if (ver > 0) onLog("SOCKS: unsupported version $ver")
                socket.close()
                return
            }

            val nMethods = input.read()
            if (nMethods < 0) {
                socket.close()
                return
            }
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
                    if (len < 0) {
                        socket.close()
                        return
                    }
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

            if (port !in 1..65535) {
                output.write(buildSocks5Response(3))
                socket.close()
                return
            }
            if (addrType == 4) {
                output.write(buildSocks5Response(8))
                socket.close()
                return
            }

            if (verbose) onLog("SOCKS CONNECT $host:$port")

            val channel = try {
                session.getStreamForwarder(host, port) as Channel
            } catch (e: Exception) {
                onLog("SSH tunnel to $host:$port failed: ${e.message}")
                output.write(buildSocks5Response(4))
                socket.close()
                return
            }
            try {
                channel.connect(30000)
            } catch (e: Exception) {
                if (verbose) onLog("Tunnel to $host:$port failed: ${e.message}")
                if (!socket.isClosed) {
                    try {
                        output.write(buildSocks5Response(4))
                    } catch (_: Exception) {}
                }
                socket.close()
                return
            }

            if (verbose) onLog("Tunnel established to $host:$port")
            output.write(buildSocks5Response(0))
            output.flush()

            val remoteIn = channel.inputStream
            val remoteOut = channel.outputStream

            val upload = Thread {
                try {
                    val buf = ByteArray(32768)
                    while (true) {
                        val len = input.read(buf)
                        if (len < 0) break
                        remoteOut.write(buf, 0, len)
                        remoteOut.flush()
                    }
                } catch (_: Exception) {}
                try { socket.close() } catch (_: Exception) {}
            }.apply { isDaemon = true; start() }

            val download = Thread {
                try {
                    val buf = ByteArray(32768)
                    while (true) {
                        val len = remoteIn.read(buf)
                        if (len < 0) break
                        output.write(buf, 0, len)
                        output.flush()
                    }
                } catch (_: Exception) {}
                try { socket.close() } catch (_: Exception) {}
            }.apply { isDaemon = true; start() }

            upload.join()
            download.join()
            channel.disconnect()

            onLog("Tunnel closed for $host:$port")
        } catch (e: java.net.SocketTimeoutException) {
            if (verbose) onLog("SOCKS client timeout")
        } catch (e: Exception) {
            if (verbose) onLog("SOCKS client error: ${e.javaClass.simpleName}: ${e.message}")
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
