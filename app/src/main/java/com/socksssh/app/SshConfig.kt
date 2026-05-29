// Created by opencode on 2026-05-29
package com.socksssh.app

import java.io.Serializable

data class SshConfig(
    val serverAddress: String = "",
    val serverPort: Int = 22,
    val localPort: Int = 1080,
    val healthCheckPeriod: Int = 30,
    val extraParams: String = "",
    val login: String = "",
    val password: String = "",
    val privateKey: String = ""
) : Serializable
