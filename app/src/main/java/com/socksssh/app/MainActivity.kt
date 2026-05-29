// Created by opencode on 2026-05-29
package com.socksssh.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val _sshService = mutableStateOf<SshService?>(null)
    private val prefs: PreferencesManager by lazy { PreferencesManager(this) }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            _sshService.value = (binder as SshService.LocalBinder).getService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            _sshService.value = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Intent(this, SshService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }

        requestNotificationPermission()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SocksSshApp(
                        service = _sshService.value,
                        prefs = prefs,
                        context = this@MainActivity
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        try {
            unbindService(connection)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

@Composable
fun SocksSshApp(
    service: SshService?,
    prefs: PreferencesManager,
    context: Context
) {
    var config by remember { mutableStateOf(prefs.loadConfig()) }

    val logs by produceState<List<String>>(initialValue = emptyList(), key1 = service) {
        service?.let { s -> s.logs.collect { value = it } }
    }

    val connectionState by produceState(
        initialValue = ConnectionState.DISCONNECTED,
        key1 = service
    ) {
        service?.let { s -> s.connectionState.collect { value = it } }
    }

    val logScrollState = rememberScrollState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            logScrollState.animateScrollTo(logScrollState.maxValue)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = when (connectionState) {
                ConnectionState.DISCONNECTED -> "Disconnected"
                ConnectionState.CONNECTING -> "Connecting..."
                ConnectionState.CONNECTED -> "Connected"
                ConnectionState.RECONNECTING -> "Reconnecting..."
            },
            color = when (connectionState) {
                ConnectionState.CONNECTED -> Color(0xFF4CAF50)
                ConnectionState.DISCONNECTED -> Color(0xFF9E9E9E)
                else -> Color(0xFFFF9800)
            },
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = config.serverAddress,
            onValueChange = { config = config.copy(serverAddress = it) },
            label = { Text("Server Address") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            enabled = connectionState == ConnectionState.DISCONNECTED
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = if (config.serverPort == 0) "" else config.serverPort.toString(),
                onValueChange = {
                    config = config.copy(serverPort = it.toIntOrNull() ?: 22)
                },
                label = { Text("Port") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                enabled = connectionState == ConnectionState.DISCONNECTED
            )
            OutlinedTextField(
                value = if (config.localPort == 0) "" else config.localPort.toString(),
                onValueChange = {
                    config = config.copy(localPort = it.toIntOrNull() ?: 1080)
                },
                label = { Text("Local Port") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                enabled = connectionState == ConnectionState.DISCONNECTED
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = if (config.healthCheckPeriod == 0) "" else config.healthCheckPeriod.toString(),
                onValueChange = {
                    config = config.copy(healthCheckPeriod = it.toIntOrNull() ?: 30)
                },
                label = { Text("HC Period (s)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                enabled = connectionState == ConnectionState.DISCONNECTED
            )
            OutlinedTextField(
                value = config.extraParams,
                onValueChange = { config = config.copy(extraParams = it) },
                label = { Text("Extra SSH params") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                enabled = connectionState == ConnectionState.DISCONNECTED
            )
        }

        OutlinedTextField(
            value = config.login,
            onValueChange = { config = config.copy(login = it) },
            label = { Text("Login") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            enabled = connectionState == ConnectionState.DISCONNECTED
        )

        OutlinedTextField(
            value = config.password,
            onValueChange = { config = config.copy(password = it) },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            enabled = connectionState == ConnectionState.DISCONNECTED
        )

        OutlinedTextField(
            value = config.privateKey,
            onValueChange = { config = config.copy(privateKey = it) },
            label = { Text("Private Key (optional)") },
            minLines = 2,
            maxLines = 4,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 100.dp),
            enabled = connectionState == ConnectionState.DISCONNECTED
        )

        Spacer(Modifier.height(8.dp))

        if (connectionState == ConnectionState.DISCONNECTED) {
            Button(
                onClick = {
                    prefs.saveConfig(config)
                    prefs.wasManuallyStopped = false
                    context.startForegroundService(
                        Intent(context, SshService::class.java).apply {
                            action = SshService.ACTION_CONNECT
                            putExtra(SshService.EXTRA_CONFIG, config)
                        }
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = config.serverAddress.isNotBlank()
            ) {
                Text("Connect")
            }
        } else {
            Button(
                onClick = {
                    prefs.wasManuallyStopped = true
                    context.startService(
                        Intent(context, SshService::class.java).apply {
                            action = SshService.ACTION_DISCONNECT
                        }
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))
            ) {
                Text("Disconnect")
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            "Logs:",
            style = MaterialTheme.typography.labelMedium
        )
        Spacer(Modifier.height(4.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            color = Color(0xFF1E1E1E),
            shape = MaterialTheme.shapes.small
        ) {
            Text(
                text = logs.joinToString("\n"),
                color = Color(0xFF00FF00),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier
                    .padding(8.dp)
                    .verticalScroll(logScrollState)
            )
        }
    }
}
