// Created by opencode on 2026-05-29
package com.socksssh.app

import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket

class SshManager(
    private val config: SshConfig,
    private val onLog: (String) -> Unit,
    private val onStateChange: (ConnectionState) -> Unit
) {
    private var session: Session? = null
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
        }
        workerThread?.apply {
            isDaemon = true
            name = "ssh-manager"
            start()
        }
    }

    fun stop() {
        isRunning = false
        workerThread?.interrupt()
        workerThread = null
        disconnect()
        onStateChange(ConnectionState.DISCONNECTED)
        onLog("Disconnected")
    }

    private fun run() {
        while (isRunning && !Thread.currentThread().isInterrupted) {
            try {
                connect()
                onStateChange(ConnectionState.CONNECTED)
                onLog("Connected. SOCKS5 proxy on ${config.localPort}")

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
        sess.setPortForwardingL(config.localPort)

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
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", config.localPort))
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

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING
}
