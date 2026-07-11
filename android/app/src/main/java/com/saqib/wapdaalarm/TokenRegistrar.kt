package com.saqib.wapdaalarm

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object TokenRegistrar {
    fun registerAsync(context: Context, token: String, onResult: ((Boolean, String) -> Unit)? = null) {
        val appContext = context.applicationContext
        val mainHandler = Handler(Looper.getMainLooper())
        Thread {
            val prefs = PrefsManager(appContext)
            val serverUrl = prefs.serverUrl
            val secret = prefs.registrationSecret
            if (serverUrl.isBlank() || secret.isBlank()) {
                val msg = "Enter server URL and registration secret first"
                prefs.lastRegistrationStatus = msg
                mainHandler.post { onResult?.invoke(false, msg) }
                return@Thread
            }

            runCatching {
                val endpoint = URL("${serverUrl.trimEnd('/')}/register")
                val connection = (endpoint.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer $secret")
                }
                val body = JSONObject()
                    .put("token", token)
                    .put("device_name", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
                    .toString()
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body) }
                val ok = connection.responseCode in 200..299
                val msg = if (ok) "Connected - watching for alerts" else "Registration failed: HTTP ${connection.responseCode}"
                prefs.isRegistered = ok
                prefs.lastRegistrationStatus = msg
                mainHandler.post { onResult?.invoke(ok, msg) }
                connection.disconnect()
            }.onFailure {
                val msg = "Registration failed: ${it.message}"
                prefs.isRegistered = false
                prefs.lastRegistrationStatus = msg
                mainHandler.post { onResult?.invoke(false, msg) }
            }
        }.start()
    }
}
