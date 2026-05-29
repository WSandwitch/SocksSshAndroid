// Created by opencode on 2026-05-29
package com.socksssh.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            val prefs = PreferencesManager(context)
            val config = prefs.loadConfig()

            if (!prefs.wasManuallyStopped && config.serverAddress.isNotBlank()) {
                val serviceIntent = Intent(context, SshService::class.java).apply {
                    action = SshService.ACTION_CONNECT
                    putExtra(SshService.EXTRA_CONFIG, config)
                }
                context.startForegroundService(serviceIntent)
            }
        }
    }
}
