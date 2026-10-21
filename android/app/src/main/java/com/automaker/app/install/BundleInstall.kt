package com.automaker.app.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import com.automaker.app.engine.XapkPackages
import java.io.File

object BundleInstall {
    fun prepare(xapk: File, packageName: String): XapkPackages.BundleParts {
        val dest = File(xapk.parentFile, "install-bundle")
        dest.deleteRecursively()
        XapkPackages.extractZip(xapk, dest)
        return XapkPackages.listBundle(dest, packageName)
    }

    fun stageBase(xapk: File, base: File): File {
        val staged = File(xapk.parentFile, "install.apk")
        base.copyTo(staged, overwrite = true)
        return staged
    }

    fun sessionCreateCommand(sdkInt: Int): String {
        val command = StringBuilder("pm install-create -r -i com.android.vending")
        if (sdkInt >= 34) command.append(" --originating-uri https://play.google.com/store/apps")
        return command.toString()
    }

    fun parseSessionId(output: String): Int? {
        val match = Regex("\\[(-?\\d+)]").find(output)
            ?: Regex("session\\D+(-?\\d+)", RegexOption.IGNORE_CASE).find(output)
            ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    fun commit(context: Context, apks: List<File>) {
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setSize(apks.sumOf { it.length() })
        params.setInstallReason(PackageManager.INSTALL_REASON_USER)
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val installer = context.packageManager.packageInstaller
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            apks.forEachIndexed { index, file ->
                val name = if (index == 0) "base.apk" else "split$index.apk"
                session.openWrite(name, 0, file.length()).use { output ->
                    file.inputStream().use { it.copyTo(output) }
                    session.fsync(output)
                }
            }
            val callback = Intent(context, InstallResultReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pending = PendingIntent.getBroadcast(context, sessionId, callback, flags)
            session.commit(pending.intentSender)
        } catch (error: Exception) {
            runCatching { session.abandon() }
            throw error
        } finally {
            runCatching { session.close() }
        }
    }
}
