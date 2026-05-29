// Created by opencode on 2026-05-29
package com.socksssh.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SshService : Service() {

    companion object {
        const val CHANNEL_ID = "ssh_tunnel"
        const val NOTIFICATION_ID = 1
        const val ACTION_CONNECT = "com.socksssh.app.CONNECT"
        const val ACTION_DISCONNECT = "com.socksssh.app.DISCONNECT"
        const val EXTRA_CONFIG = "config"
    }

    private val binder = LocalBinder()
    private var sshManager: SshManager? = null

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _config = MutableStateFlow(SshConfig())
    val config: StateFlow<SshConfig> = _config.asStateFlow()

    inner class LocalBinder : Binder() {
        fun getService(): SshService = this@SshService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val prefs = PreferencesManager(this)
            val savedConfig = prefs.loadConfig()
            if (savedConfig.serverAddress.isNotBlank()) {
                _config.value = savedConfig
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val config = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getSerializableExtra(EXTRA_CONFIG, SshConfig::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getSerializableExtra(EXTRA_CONFIG)
                } as? SshConfig
                if (config != null) {
                    startSsh(config)
                } else {
                    val prefs = PreferencesManager(this)
                    val savedConfig = prefs.loadConfig()
                    if (savedConfig.serverAddress.isNotBlank()) {
                        startSsh(savedConfig)
                    }
                }
            }
            ACTION_DISCONNECT -> {
                stopSsh()
            }
        }
        return START_STICKY
    }

    fun startSsh(config: SshConfig) {
        _config.value = config.copy(
            localPort = if (config.localPort in 1..65535) config.localPort else 1080,
            serverPort = if (config.serverPort in 1..65535) config.serverPort else 22,
            healthCheckPeriod = if (config.healthCheckPeriod > 0) config.healthCheckPeriod else 30
        )
        startForeground(NOTIFICATION_ID, createNotification("Connecting..."))

        sshManager?.stop()
        sshManager = null

        _logs.value = emptyList()

        sshManager = SshManager(
            config = config,
            onLog = { log ->
                val current = _logs.value
                _logs.value = if (current.size > 1000) {
                    current.takeLast(500) + log
                } else {
                    current + log
                }
            },
            onStateChange = { state ->
                _connectionState.value = state
                updateNotification(state)
            }
        )
        sshManager?.start()
    }

    fun stopSsh() {
        sshManager?.stop()
        sshManager = null
        _connectionState.value = ConnectionState.DISCONNECTED
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "SSH Tunnel",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "SSH tunnel status"
            setShowBadge(false)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(text: String): Notification {
        val disconnectIntent = Intent(this, SshService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this, 0, disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SSH Tunnel")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(openPendingIntent)
            .addAction(0, "Disconnect", disconnectPendingIntent)
            .build()
    }

    private fun updateNotification(state: ConnectionState) {
        val text = when (state) {
            ConnectionState.CONNECTING -> "Connecting..."
            ConnectionState.CONNECTED -> "Active on port ${_config.value.localPort}"
            ConnectionState.DISCONNECTED -> "Disconnected"
            ConnectionState.RECONNECTING -> "Reconnecting..."
        }
        val notification = createNotification(text)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }
}
