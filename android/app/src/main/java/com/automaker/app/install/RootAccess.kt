package com.automaker.app.install

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

object RootAccess {
    private const val PREFS = "automaker"
    private const val KEY_USE_ROOT = "use_root"

    @Volatile
    private var cached: Boolean? = null

    fun available(): Boolean {
        cached?.let { return it }
        val found = probe()
        cached = found
        return found
    }

    fun preferRoot(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_USE_ROOT)) {
            val rooted = available()
            prefs.edit().putBoolean(KEY_USE_ROOT, rooted).apply()
            return rooted
        }
        return prefs.getBoolean(KEY_USE_ROOT, false) && available()
    }

    fun setPreferRoot(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_USE_ROOT, value)
            .apply()
    }

    fun storedPreferRoot(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_USE_ROOT)) return available()
        return prefs.getBoolean(KEY_USE_ROOT, false)
    }

    fun invalidate() {
        cached = null
    }

    private fun probe(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val output = StringBuilder()
            val reader = Thread {
                BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8)).use { stream ->
                    while (true) {
                        val line = stream.readLine() ?: break
                        if (output.isNotEmpty()) output.append('\n')
                        output.append(line)
                    }
                }
            }
            reader.start()
            val finished = process.waitFor(4, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                reader.join(500)
                return false
            }
            reader.join(1000)
            process.exitValue() == 0 && output.toString().contains("uid=0")
        } catch (_: Throwable) {
            false
        }
    }
}
