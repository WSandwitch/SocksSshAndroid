// Created by opencode on 2026-05-29
package com.socksssh.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class PreferencesManager(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveConfig(config: SshConfig) {
        prefs.edit()
            .putString(KEY_SERVER, config.serverAddress)
            .putInt(KEY_SERVER_PORT, config.serverPort)
            .putInt(KEY_LOCAL_PORT, config.localPort)
            .putInt(KEY_HEALTH_PERIOD, config.healthCheckPeriod)
            .putString(KEY_EXTRA_PARAMS, config.extraParams)
            .putString(KEY_LOGIN, config.login)
            .putString(KEY_PASSWORD, config.password)
            .putString(KEY_PRIVATE_KEY, config.privateKey)
            .apply()
    }

    fun loadConfig(): SshConfig {
        return SshConfig(
            serverAddress = prefs.getString(KEY_SERVER, "") ?: "",
            serverPort = prefs.getInt(KEY_SERVER_PORT, 22),
            localPort = prefs.getInt(KEY_LOCAL_PORT, 1080),
            healthCheckPeriod = prefs.getInt(KEY_HEALTH_PERIOD, 30),
            extraParams = prefs.getString(KEY_EXTRA_PARAMS, "") ?: "",
            login = prefs.getString(KEY_LOGIN, "") ?: "",
            password = prefs.getString(KEY_PASSWORD, "") ?: "",
            privateKey = prefs.getString(KEY_PRIVATE_KEY, "") ?: ""
        )
    }

    var wasManuallyStopped: Boolean
        get() = prefs.getBoolean(KEY_MANUAL_STOP, false)
        set(value) = prefs.edit().putBoolean(KEY_MANUAL_STOP, value).apply()

    companion object {
        private const val PREFS_NAME = "socks_ssh_config"
        private const val KEY_SERVER = "server"
        private const val KEY_SERVER_PORT = "server_port"
        private const val KEY_LOCAL_PORT = "local_port"
        private const val KEY_HEALTH_PERIOD = "health_period"
        private const val KEY_EXTRA_PARAMS = "extra_params"
        private const val KEY_LOGIN = "login"
        private const val KEY_PASSWORD = "password"
        private const val KEY_PRIVATE_KEY = "private_key"
        private const val KEY_MANUAL_STOP = "manual_stop"
    }
}
