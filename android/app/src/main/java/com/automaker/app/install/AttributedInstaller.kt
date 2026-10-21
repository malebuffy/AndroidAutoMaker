package com.automaker.app.install

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.io.File

class AttributedInstaller(private val context: Context) {
    fun shizukuReady(): Boolean {
        if (!shizukuInstalled()) return false
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    fun requestPermission(): Boolean {
        if (!shizukuInstalled()) return false
        return try {
            if (!Shizuku.pingBinder()) return false
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return false
            Shizuku.requestPermission(SHIZUKU_REQUEST)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun setPlayInstaller(packageName: String): Boolean {
        if (!shizukuReady() || !PACKAGE_NAME.matches(packageName)) return false
        val commands = listOf(
            "cmd package set-installer-package --user 0 $packageName ${PlayStoreInstall.VENDING_PACKAGE}",
            "cmd package set-installer --user 0 $packageName ${PlayStoreInstall.VENDING_PACKAGE}",
            "pm set-installer $packageName ${PlayStoreInstall.VENDING_PACKAGE}",
        )
        return commands.any { command ->
            runCatching { shell(command) }.isSuccess
        }
    }

    fun openAsShell(file: File): Boolean {
        if (!shizukuReady()) return false
        return try {
            val intent = PlayStoreInstall.createShellInstallIntent(context, file)
            startInstallActivityAsShell(intent)
        } catch (error: Throwable) {
            Log.e(TAG, "Could not open the installer as shell", error)
            false
        }
    }

    fun installBundle(apks: List<File>, obbs: List<com.automaker.app.engine.XapkPackages.ObbFile>): Boolean {
        if (!shizukuReady() || apks.isEmpty()) return false
        val id = java.util.UUID.randomUUID().toString()
        val remotes = ArrayList<String>()
        var sessionId: Int? = null
        return try {
            val sdk = shell("getprop ro.build.version.sdk").trim().toIntOrNull() ?: 0
            val created = runCatching { shell(BundleInstall.sessionCreateCommand(sdk)) }.getOrElse {
                shell("pm install-create -r -i com.android.vending")
            }
            val session = BundleInstall.parseSessionId(created) ?: return false
            sessionId = session
            apks.forEachIndexed { index, file ->
                val remote = "/data/local/tmp/automaker-$id-$index.apk"
                copyToRemote(file, remote)
                remotes += remote
                val split = if (index == 0) "base.apk" else "split$index.apk"
                shell("pm install-write -S ${file.length()} $session $split $remote")
            }
            shell("pm install-commit $session")
            runCatching { copyObbs(obbs) }
            true
        } catch (error: Throwable) {
            Log.e(TAG, "Could not install the bundle", error)
            if (sessionId != null) runCatching { shell("pm install-abandon $sessionId") }
            false
        } finally {
            remotes.forEach { remote -> runCatching { shell("rm -f $remote") } }
        }
    }

    fun copyObbs(obbs: List<com.automaker.app.engine.XapkPackages.ObbFile>) {
        if (!shizukuReady()) return
        for (obb in obbs) {
            val remoteDir = "/sdcard/Android/obb/${obb.packageName}"
            val remote = "$remoteDir/${obb.name}"
            shell("mkdir -p $remoteDir")
            copyToRemote(obb.file, remote)
        }
    }

    @SuppressLint("DiscouragedPrivateApi", "PrivateApi", "BlockedPrivateApi")
    private fun startInstallActivityAsShell(intent: android.content.Intent): Boolean {
        val binder = SystemServiceHelper.getSystemService("activity_task")
            ?: SystemServiceHelper.getSystemService("activity")
            ?: return false
        val wrappedBinder = ShizukuBinderWrapper(binder)
        val atmStubClass = Class.forName("android.app.IActivityTaskManager\$Stub")
        val asInterfaceMethod = atmStubClass.getDeclaredMethod("asInterface", IBinder::class.java)
        asInterfaceMethod.isAccessible = true
        val atmInstance = asInterfaceMethod.invoke(null, wrappedBinder) ?: return false
        val method = atmInstance.javaClass.methods.firstOrNull { it.name == "startActivityAsUser" }
            ?: atmInstance.javaClass.declaredMethods.firstOrNull { it.name == "startActivityAsUser" }
            ?: return false
        method.isAccessible = true
        val args = arrayOfNulls<Any>(method.parameterTypes.size)
        for (index in method.parameterTypes.indices) {
            val paramType = method.parameterTypes[index]
            args[index] = when {
                paramType.isAssignableFrom(android.content.Intent::class.java) -> intent
                paramType == String::class.java && (index == 1 || index == 2) -> PlayStoreInstall.SHELL_PACKAGE
                paramType == Int::class.javaPrimitiveType -> 0
                else -> null
            }
        }
        method.invoke(atmInstance, *args)
        return true
    }

    private fun shell(command: String): String {
        val process = remoteProcess(arrayOf("sh", "-c", command))
        val output = StringBuilder()
        val error = StringBuilder()
        val outThread = Thread { output.append(process.inputStream.bufferedReader().readText()) }
        val errThread = Thread { error.append(process.errorStream.bufferedReader().readText()) }
        outThread.start()
        errThread.start()
        val code = process.waitFor()
        outThread.join()
        errThread.join()
        val text = output.toString() + error.toString()
        if (code != 0) throw IllegalStateException(text.ifBlank { "Command failed." })
        return text
    }

    private fun copyToRemote(file: File, remote: String) {
        require(REMOTE_PATH.matches(remote))
        val process = remoteProcess(arrayOf("sh", "-c", "cat > $remote"))
        val error = StringBuilder()
        val errThread = Thread { error.append(process.errorStream.bufferedReader().readText()) }
        errThread.start()
        file.inputStream().use { input -> input.copyTo(process.outputStream) }
        process.outputStream.close()
        process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        errThread.join()
        if (code != 0) throw IllegalStateException(error.toString().ifBlank { "The file could not be copied." })
    }

    private fun remoteProcess(command: Array<String>): Process {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(null, command, null, null) as Process
    }

    private fun shizukuInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    companion object {
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val TAG = "AttributedInstaller"
        const val SHIZUKU_REQUEST = 5001
        private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+$")
        private val REMOTE_PATH = Regex("^/(?:data/local/tmp/automaker-[0-9a-fA-F-]+-\\d+\\.apk|sdcard/Android/obb/[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+/[A-Za-z0-9._-]+)$")
    }
}
