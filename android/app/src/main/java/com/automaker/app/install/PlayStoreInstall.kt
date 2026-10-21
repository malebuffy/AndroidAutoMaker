package com.automaker.app.install

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File

object PlayStoreInstall {
    const val VENDING_PACKAGE = "com.android.vending"
    const val GOOGLE_PACKAGE_INSTALLER = "com.google.android.packageinstaller"
    const val SHELL_PACKAGE = "com.android.shell"
    const val APK_MIME_TYPE = "application/vnd.android.package-archive"

    private val uriPermissionTargets = listOf(
        SHELL_PACKAGE,
        GOOGLE_PACKAGE_INSTALLER,
        "com.android.packageinstaller",
    )

    fun grantInstallUriPermissions(context: Context, uri: Uri) {
        uriPermissionTargets.forEach { target ->
            runCatching {
                context.grantUriPermission(target, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    @Suppress("DEPRECATION")
    fun createInstallIntent(context: Context, file: File, targetPackageName: String? = null): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val playStoreReferrer = "android-app://$VENDING_PACKAGE".toUri()
        val storeListing = "https://play.google.com/store/apps/details?id=${targetPackageName ?: context.packageName}".toUri()
        grantInstallUriPermissions(context, uri)
        return Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, VENDING_PACKAGE)
            putExtra("installerPackageName", VENDING_PACKAGE)
            putExtra("android.content.pm.extra.VERIFICATION_INSTALLER_PACKAGE", VENDING_PACKAGE)
            putExtra("android.content.pm.extra.VERIFICATION_INSTALLER_UID", 0)
            putExtra("android.intent.extra.INSTALL_REASON", 1)
            putExtra("android.intent.extra.REFERRER_NAME", "android-app://$VENDING_PACKAGE")
            putExtra(Intent.EXTRA_REFERRER, playStoreReferrer)
            putExtra("android.intent.extra.ORIGINATING_PACKAGE", VENDING_PACKAGE)
            putExtra(Intent.EXTRA_ORIGINATING_URI, storeListing)
            if (Build.VERSION.SDK_INT >= 34) {
                putExtra("android.content.pm.extra.REQUEST_UPDATE_OWNERSHIP", true)
            }
        }
    }

    fun createShellInstallIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        grantInstallUriPermissions(context, uri)
        return Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, VENDING_PACKAGE)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra("android.intent.extra.REFERRER_NAME", "android-app://$VENDING_PACKAGE")
            putExtra("android.content.pm.extra.INSTALL_REASON", 1)
        }
    }
}
