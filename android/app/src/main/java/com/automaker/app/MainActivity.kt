package com.automaker.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.automaker.app.databinding.ActivityMainBinding
import com.automaker.app.databinding.ItemSessionBinding
import com.automaker.app.engine.InstalledApps
import rikka.shizuku.Shizuku
import com.automaker.app.install.AttributedInstaller
import com.automaker.app.install.BundleInstall
import com.automaker.app.install.PlayStoreInstall
import com.automaker.app.install.RootAccess
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class PickerModel : ViewModel() {
    var uri: Uri? = null
    var installedPackage: String? = null
    var name: String? = null
    var size: Long = -1
    var kind: String = "game"
    var renamePackage: Boolean = false
    var gamepad: Boolean = false
    var useRoot: Boolean = false
    var rootChecked: Boolean = false
}

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val picker by viewModels<PickerModel>()
    private var pendingSave: SessionSnapshot? = null
    private var pendingInstall: SessionSnapshot? = null

    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val name = queryName(uri)
        val size = querySize(uri)
        val lower = name.lowercase(Locale.US)
        if (!lower.endsWith(".apk") && !lower.endsWith(".xapk")) {
            snack("Choose an .apk or .xapk file.")
            return@registerForActivityResult
        }
        picker.uri = uri
        picker.installedPackage = null
        picker.name = name
        picker.size = size
        showSelection()
    }

    private val pickInstalled = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val packageName = data.getStringExtra(InstalledAppActivity.EXTRA_PACKAGE) ?: return@registerForActivityResult
        picker.uri = null
        picker.installedPackage = packageName
        picker.name = data.getStringExtra(InstalledAppActivity.EXTRA_LABEL) ?: packageName
        picker.size = data.getLongExtra(InstalledAppActivity.EXTRA_SIZE, -1)
        allowMissingStorage = false
        showSelection()
    }

    private val storageAccess = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (picker.installedPackage != null) beginConvert()
    }

    private val saveApk = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.android.package-archive"),
    ) { uri -> onSave(uri) }

    private val saveXapk = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> onSave(uri) }

    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        startSelected()
    }

    private val donation = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) snack(getString(R.string.donation_cancelled))
        beginConvert()
    }

    private val installLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        installResult?.complete(it.resultCode)
    }

    private val installPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val session = pendingInstall ?: return@registerForActivityResult
        launchAttributedInstall(session)
    }

    private var installResult: CompletableDeferred<Int>? = null
    private var latestSessions: List<SessionSnapshot> = emptyList()
    private var allowMissingStorage = false
    private val installingIds = mutableSetOf<String>()
    private val openLogs = mutableSetOf<String>()
    private val installedIds = mutableSetOf<String>()
    private val installJobs = mutableMapOf<String, Job>()

    private val shizukuPermission = Shizuku.OnRequestPermissionResultListener { requestCode, result ->
        if (requestCode != AttributedInstaller.SHIZUKU_REQUEST) return@OnRequestPermissionResultListener
        val session = pendingInstall ?: return@OnRequestPermissionResultListener
        if (result == PackageManager.PERMISSION_GRANTED) launchAttributedInstall(session)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        runCatching { Shizuku.addRequestPermissionResultListener(shizukuPermission) }
        binding.choose.setOnClickListener {
            openFile.launch(arrayOf("*/*"))
        }
        binding.chooseInstalled.setOnClickListener {
            pickInstalled.launch(Intent(this, InstalledAppActivity::class.java))
        }
        binding.clearFile.setOnClickListener {
            picker.uri = null
            picker.installedPackage = null
            picker.name = null
            picker.size = -1
            showSelection()
        }
        binding.renamePackage.isChecked = picker.renamePackage
        binding.renamePackage.setOnCheckedChangeListener { _, checked ->
            picker.renamePackage = checked
            showRenameNote()
        }
        binding.gamepad.isChecked = picker.gamepad
        binding.gamepad.setOnCheckedChangeListener { _, checked ->
            picker.gamepad = checked
        }
        binding.useRoot.setOnCheckedChangeListener { _, checked ->
            picker.useRoot = checked
            RootAccess.setPreferRoot(this, checked)
            showRenameNote()
        }
        showRenameNote()
        binding.convert.setOnClickListener {
            if (picker.kind == "navigation" && (picker.uri != null || picker.installedPackage != null)) askDonation()
            else beginConvert()
        }
        val offersNavigation = resources.getBoolean(R.bool.offers_navigation)
        binding.kindNavigation.visibility = if (offersNavigation) View.VISIBLE else View.GONE
        if (!offersNavigation) picker.kind = "game"
        binding.kind.setOnCheckedChangeListener { _, checkedId ->
            picker.kind = if (offersNavigation && checkedId == R.id.kind_navigation) "navigation" else "game"
            showRenameNote()
        }
        if (offersNavigation && picker.kind == "navigation") binding.kindNavigation.isChecked = true
        showSelection()
        detectRootOption()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                (application as MakerApp).sessions.state.collect { render(it) }
            }
        }
    }

    private fun detectRootOption() {
        lifecycleScope.launch {
            val rooted = withContext(Dispatchers.IO) { RootAccess.available() }
            if (!rooted) {
                binding.useRoot.visibility = View.GONE
                picker.useRoot = false
                return@launch
            }
            if (!picker.rootChecked) {
                picker.useRoot = RootAccess.storedPreferRoot(this@MainActivity)
                picker.rootChecked = true
            }
            binding.useRoot.visibility = View.VISIBLE
            binding.useRoot.isChecked = picker.useRoot
            RootAccess.setPreferRoot(this@MainActivity, picker.useRoot)
            showRenameNote()
        }
    }

    override fun onDestroy() {
        runCatching { Shizuku.removeRequestPermissionResultListener(shizukuPermission) }
        super.onDestroy()
    }

    private fun askDonation() {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.donation_title)
            .setMessage(R.string.donation_body)
            .setNegativeButton(R.string.donation_skip, null)
            .setPositiveButton(R.string.donation_yes, null)
            .create()
        dialog.setOnShowListener {
            val donate = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val skip = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            donate.isEnabled = false
            skip.isEnabled = false
            donate.postDelayed({
                if (!dialog.isShowing) return@postDelayed
                donate.isEnabled = true
                skip.isEnabled = true
            }, 400)
            donate.setOnClickListener {
                if (!donate.isEnabled) return@setOnClickListener
                dialog.dismiss()
                donation.launch(Intent(this, DonationActivity::class.java))
            }
            skip.setOnClickListener {
                if (!skip.isEnabled) return@setOnClickListener
                dialog.dismiss()
                beginConvert()
            }
        }
        dialog.show()
    }

    private fun beginConvert() {
        if (picker.uri == null && picker.installedPackage == null) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startSelected()
    }

    private fun startSelected() {
        val installed = picker.installedPackage
        val uri = picker.uri
        if (installed == null && uri == null) return
        val name = if (installed != null) {
            val label = picker.name ?: installed
            if (label.lowercase(Locale.US).endsWith(".xapk")) label else "$label.xapk"
        } else {
            picker.name ?: "app.apk"
        }
        binding.convert.isEnabled = false
        lifecycleScope.launch {
            var started = false
            val error = withContext(Dispatchers.IO) {
                try {
                    if (installed != null) {
                        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager() && !allowMissingStorage) {
                            allowMissingStorage = true
                            withContext(Dispatchers.Main) {
                                storageAccess.launch(
                                    Intent(
                                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                        Uri.parse("package:$packageName"),
                                    ),
                                )
                            }
                            return@withContext null
                        }
                        val staging = File(cacheDir, "installed-${System.nanoTime()}.xapk")
                        val skipStorage = allowMissingStorage &&
                            (Build.VERSION.SDK_INT < 30 || !Environment.isExternalStorageManager())
                        val notes = InstalledApps.export(this@MainActivity, installed, staging, skipStorage)
                        (application as MakerApp).sessions.start(staging, name, picker.kind, picker.renamePackage, notes, picker.gamepad)
                        started = true
                    } else {
                        val source = uri ?: return@withContext "That file could not be read."
                        val staging = File(cacheDir, "incoming-${System.nanoTime()}")
                        contentResolver.openInputStream(source)?.use { input ->
                            staging.outputStream().use { output -> input.copyTo(output, 1024 * 1024) }
                        } ?: return@withContext "That file could not be read."
                        val header = ByteArray(2)
                        staging.inputStream().use { stream ->
                            if (stream.read(header) < 2 || header[0] != 'P'.code.toByte() || header[1] != 'K'.code.toByte()) {
                                staging.delete()
                                return@withContext "That file is not an APK or XAPK."
                            }
                        }
                        (application as MakerApp).sessions.start(staging, name, picker.kind, picker.renamePackage, gamepad = picker.gamepad)
                        started = true
                    }
                    null
                } catch (error: InstalledApps.StorageAccessRequired) {
                    allowMissingStorage = true
                    withContext(Dispatchers.Main) {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:$packageName"),
                        )
                        storageAccess.launch(intent)
                    }
                    null
                } catch (error: Exception) {
                    error.message ?: "The package could not be started."
                }
            }
            binding.convert.isEnabled = picker.uri != null || picker.installedPackage != null
            if (error != null) {
                snack(error)
            } else if (started) {
                picker.uri = null
                picker.installedPackage = null
                picker.name = null
                picker.size = -1
                allowMissingStorage = false
                showSelection()
            }
        }
    }

    private fun showRenameNote() {
        val sideBySide = picker.renamePackage
        val navigation = picker.kind == "navigation"
        binding.phoneNote.setText(if (sideBySide) R.string.change_phone_side else R.string.change_phone_title)
        binding.installNote.setText(if (sideBySide) R.string.install_note_side else R.string.install_note)
        binding.availabilityTitle.setText(if (navigation) R.string.change_navigation_title else R.string.change_game_title)
        binding.availabilityBody.setText(if (navigation) R.string.change_navigation_body else R.string.change_game_body)
        binding.availabilityNote.setText(
            when {
                !navigation -> R.string.parked_note
                picker.useRoot -> R.string.driving_note_root
                else -> R.string.driving_note
            },
        )
    }

    private fun showSelection() {
        val name = picker.name
        binding.fileRow.visibility = if (name == null) View.GONE else View.VISIBLE
        binding.fileName.text = name.orEmpty()
        binding.fileSize.text = buildString {
            if (picker.installedPackage != null) append(picker.installedPackage)
            if (picker.size >= 0) {
                if (isNotEmpty()) append(" · ")
                append(formatSize(picker.size))
            }
        }
        binding.convert.isEnabled = name != null
    }

    private fun render(sessions: List<SessionSnapshot>) {
        latestSessions = sessions
        binding.sessionsTitle.visibility = if (sessions.isEmpty()) View.GONE else View.VISIBLE
        val container = binding.sessions
        val seen = HashSet<String>()
        for (session in sessions) {
            seen.add(session.id)
            val current = findSessionView(container, session.id)
            val binding = if (current == null) {
                ItemSessionBinding.inflate(layoutInflater, container, false).also { created ->
                    created.root.tag = session.id
                    container.addView(created.root)
                }
            } else {
                ItemSessionBinding.bind(current)
            }
            bindSession(binding, session)
        }
        for (index in container.childCount - 1 downTo 0) {
            val child = container.getChildAt(index)
            if (child.tag !in seen) container.removeViewAt(index)
        }
    }

    private fun findSessionView(container: LinearLayout, id: String): View? {
        for (index in 0 until container.childCount) {
            val child = container.getChildAt(index)
            if (child.tag == id) return child
        }
        return null
    }

    private fun bindSession(binding: ItemSessionBinding, session: SessionSnapshot) {
        val waiting = session.id in installingIds
        binding.sessionTitle.text = session.originalName
        val extracting = session.progress in 0..100
        binding.sessionStatus.text = when {
            waiting -> getString(R.string.install_waiting)
            session.id in installedIds -> getString(R.string.install_done)
            extracting -> getString(R.string.phase_progress, session.phase.ifBlank { "Working" }, session.progress)
            session.status == "running" -> "Working"
            session.status == "done" -> "Ready to save or install"
            else -> "Could not convert"
        }
        binding.progress.visibility = if (session.status == "running" || waiting) View.VISIBLE else View.GONE
        if (extracting) {
            binding.progress.isIndeterminate = false
            binding.progress.max = 100
            binding.progress.setProgressCompat(session.progress, true)
        } else if (session.status == "running" || waiting) {
            binding.progress.isIndeterminate = true
        }
        binding.steps.removeAllViews()
        for (step in session.steps) {
            val row = TextView(this)
            row.text = stepLabel(step)
            row.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            val color = when (step.state) {
                "done" -> R.color.primary
                "active" -> R.color.primary
                "error" -> R.color.error
                else -> R.color.outline
            }
            row.setTextColor(ContextCompat.getColor(this, color))
            binding.steps.addView(row)
        }
        val detail = buildString {
            if (session.packageName.isNotBlank()) append(session.packageName).append('\n')
            session.changes.forEach { append("• ").append(it).append('\n') }
            if (!session.error.isNullOrBlank()) append(session.error)
        }.trim()
        binding.changes.text = detail
        binding.changes.visibility = if (detail.isBlank()) View.GONE else View.VISIBLE
        binding.extractRow.visibility = if (extracting) View.VISIBLE else View.GONE
        if (extracting) binding.extractLabel.text = getString(R.string.phase_progress, session.phase.ifBlank { "Working" }, session.progress)
        val showLog = session.id in openLogs
        val logText = session.log.joinToString("\n")
        val followLog = !binding.logScroll.canScrollVertically(1)
        if (binding.log.text.toString() != logText) {
            binding.log.text = logText
            if (showLog && followLog) binding.logScroll.post { binding.logScroll.fullScroll(View.FOCUS_DOWN) }
        }
        binding.logScroll.visibility = if (showLog) View.VISIBLE else View.GONE
        binding.showLog.visibility = View.VISIBLE
        binding.showLog.setText(if (showLog) R.string.hide_log else R.string.show_log)
        binding.showLog.setOnClickListener {
            if (session.id in openLogs) openLogs.remove(session.id) else openLogs.add(session.id)
            bindSession(binding, session)
        }
        binding.save.visibility = if (session.status == "done") View.VISIBLE else View.GONE
        binding.save.text = "Save APK"
        binding.save.setOnClickListener {
            pendingSave = session
            // val launcher = if (session.format == "xapk") saveXapk else saveApk
            saveApk.launch(session.downloadName)
        }
        val canInstall = session.status == "done"
        binding.install.visibility = if (canInstall) View.VISIBLE else View.GONE
        binding.install.isEnabled = !waiting
        binding.install.setOnClickListener { beginInstall(session) }
        binding.discard.setOnClickListener {
            installJobs.remove(session.id)?.cancel()
            installingIds.remove(session.id)
            installedIds.remove(session.id)
            (application as MakerApp).sessions.discard(session.id)
        }
    }

    private fun stepLabel(step: StepView): String {
        val mark = when (step.state) {
            "done" -> "●"
            "active" -> "●"
            "error" -> "●"
            else -> "○"
        }
        return "$mark  ${step.label}"
    }

    private fun beginInstall(session: SessionSnapshot) {
        pendingInstall = session
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            installPermission.launch(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")),
            )
            return
        }
        launchAttributedInstall(session)
    }

    private fun launchAttributedInstall(session: SessionSnapshot) {
        val file = (application as MakerApp).sessions.outputFile(session.id)
        if (file == null) {
            snack("The signed package is not ready.")
            return
        }
        // if (session.format == "xapk") {
        //     launchBundleInstall(file, session)
        //     return
        // }
        startInstallWatch(session) {
            val before = packageStamp(session.packageName)
            val openedAsShell = withContext(Dispatchers.IO) {
                val sidecars = (application as MakerApp).sessions.directory(session.id)?.let { File(it, "sidecars") }
                if (sidecars != null && sidecars.isDirectory) {
                    runCatching { AttributedInstaller(this@MainActivity).installSidecars(sidecars) }
                }
                AttributedInstaller(this@MainActivity).openAsShell(file)
            }
            if (!openedAsShell) {
                val code = launchInstaller(PlayStoreInstall.createInstallIntent(this@MainActivity, file, session.packageName))
                val timeout = if (code == RESULT_CANCELED) 15_000L else 180_000L
                return@startInstallWatch awaitPackageChange(session.packageName, before, timeout)
            }
            awaitPackageChange(session.packageName, before, 180_000L)
        }
    }

    private fun launchBundleInstall(file: File, session: SessionSnapshot) {
        startInstallWatch(session) {
            val before = packageStamp(session.packageName)
            val outcome = try {
                withContext(Dispatchers.IO) {
                    val parts = BundleInstall.prepare(file, session.packageName)
                    val installer = AttributedInstaller(this@MainActivity)
                    when {
                        parts.apks.size > 1 && installer.installBundle(parts.apks, parts.obbs) -> {
                            installer.setPlayInstaller(session.packageName)
                            "installed"
                        }
                        parts.apks.size > 1 && installer.requestPermission() -> "waiting"
                        parts.apks.size == 1 -> {
                            runCatching { installer.copyObbs(parts.obbs) }
                            val staged = BundleInstall.stageBase(file, parts.apks[0])
                            if (installer.openAsShell(staged)) {
                                "opened"
                            } else {
                                "dialog:${staged.absolutePath}"
                            }
                        }
                        else -> {
                            BundleInstall.commit(this@MainActivity, parts.apks)
                            "opened"
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                android.util.Log.e("BundleInstall", "Could not open the installer", error)
                "failed"
            }
            when {
                outcome == "waiting" -> null
                outcome == "failed" -> false
                outcome.startsWith("dialog:") -> {
                    val staged = File(outcome.removePrefix("dialog:"))
                    val code = launchInstaller(PlayStoreInstall.createInstallIntent(this@MainActivity, staged, session.packageName))
                    val timeout = if (code == RESULT_CANCELED) 15_000L else 180_000L
                    awaitPackageChange(session.packageName, before, timeout)
                }
                outcome == "installed" -> awaitPackageChange(session.packageName, before, 60_000L)
                else -> awaitPackageChange(session.packageName, before, 180_000L)
            }
        }
    }

    private fun startInstallWatch(session: SessionSnapshot, confirm: suspend () -> Boolean?) {
        if (!PACKAGE_NAME.matches(session.packageName)) {
            snack(getString(R.string.install_missing))
            return
        }
        installJobs.remove(session.id)?.cancel()
        installingIds.add(session.id)
        installedIds.remove(session.id)
        render(latestSessions)
        val self = CompletableDeferred<Job>()
        val job = lifecycleScope.launch {
            val current = self.await()
            val confirmed = try {
                confirm()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                android.util.Log.e("Install", "Could not confirm the install", error)
                false
            }
            if (installJobs[session.id] != current) return@launch
            installJobs.remove(session.id)
            installingIds.remove(session.id)
            if (confirmed == true) installedIds.add(session.id)
            render(latestSessions)
            if (confirmed != null) {
                snack(getString(if (confirmed) R.string.install_done else R.string.install_missing))
            }
        }
        self.complete(job)
        installJobs[session.id] = job
    }

    private suspend fun launchInstaller(intent: Intent): Int {
        val deferred = CompletableDeferred<Int>()
        installResult = deferred
        withContext(Dispatchers.Main) {
            installLauncher.launch(intent)
        }
        return deferred.await()
    }

    private fun packageStamp(packageName: String): Long? {
        if (!PACKAGE_NAME.matches(packageName)) return null
        return try {
            val info = if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            info.lastUpdateTime
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    private suspend fun awaitPackageChange(packageName: String, before: Long?, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val stamp = withContext(Dispatchers.IO) { packageStamp(packageName) }
            if (stamp != null && stamp != before) return true
            delay(1000)
        }
        return false
    }

    private fun onSave(uri: Uri?) {
        val session = pendingSave ?: return
        pendingSave = null
        if (uri == null) return
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                (application as MakerApp).sessions.exportAndDelete(session.id, uri)
            }
            if (error != null) snack(error)
            else snack("Saved ${session.downloadName}. The session files were deleted.")
        }
    }

    private fun queryName(uri: Uri): String {
        val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index) ?: "app.apk"
            }
        }
        return uri.lastPathSegment ?: "app.apk"
    }

    private fun querySize(uri: Uri): Long {
        val projection = arrayOf(android.provider.OpenableColumns.SIZE)
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (index >= 0 && !cursor.isNull(index)) return cursor.getLong(index)
            }
        }
        return -1
    }

    private fun snack(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kilobytes = bytes / 1024.0
        if (kilobytes < 1024) return String.format(Locale.US, "%.1f KB", kilobytes)
        return String.format(Locale.US, "%.1f MB", kilobytes / 1024.0)
    }

    companion object {
        private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+$")
    }
}
