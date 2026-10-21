package com.automaker.app.engine

import org.json.JSONObject
import java.io.File
import java.nio.charset.Charset
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object XapkPackages {
    private val unsafeDrive = Regex("^[A-Za-z]:")
    private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    private val SAFE_FILE = Regex("^[A-Za-z0-9._-]+$")

    data class ObbFile(val file: File, val packageName: String, val name: String)
    data class BundleParts(val apks: List<File>, val obbs: List<ObbFile>)

    fun assertSafeEntry(root: File, entryName: String) {
        val name = entryName.replace('\\', '/')
        if (name.isEmpty() || name.startsWith("/") || name.contains('\u0000') || unsafeDrive.containsMatchIn(name)) {
            throw IllegalArgumentException("The XAPK contains an unsafe path.")
        }
        val base = root.canonicalFile
        val target = File(root, name).canonicalFile
        val relative = base.toPath().relativize(target.toPath()).toString()
        if (relative == ".." || relative.startsWith("..${File.separator}") || relative.startsWith("../")) {
            throw IllegalArgumentException("The XAPK contains an unsafe path.")
        }
    }

    fun isAuxiliarySplit(fileName: String): Boolean {
        val lower = File(fileName).name.lowercase()
        return lower.startsWith("config.") || lower.startsWith("split_") || Regex("asset[-_]?pack").containsMatchIn(lower)
    }

    fun listApks(root: File): List<File> {
        val found = ArrayList<File>()
        fun walk(dir: File) {
            for (child in dir.listFiles().orEmpty()) {
                if (child.isDirectory) walk(child)
                else if (child.name.lowercase().endsWith(".apk")) found.add(child)
            }
        }
        walk(root)
        return found
    }

    fun relativeZipPath(root: File, file: File): String {
        return root.canonicalFile.toPath().relativize(file.canonicalFile.toPath()).toString().replace('\\', '/')
    }

    fun chooseBaseApk(root: File, apks: List<File> = listApks(root)): File {
        if (apks.isEmpty()) throw IllegalArgumentException("The XAPK does not contain an APK.")
        val manifest = readManifest(root)
        val byZipPath = apks.associateBy { relativeZipPath(root, it) }
        val splits = manifest?.optJSONArray("split_apks")
        if (splits != null && splits.length() > 0) {
            for (index in 0 until splits.length()) {
                val item = splits.optJSONObject(index) ?: continue
                val id = item.optString("id")
                if (id == "base" || id == "base.apk") {
                    val listed = normalizeZipPath(item.optString("file"))
                    if (listed != null && byZipPath.containsKey(listed)) return byZipPath.getValue(listed)
                }
            }
            for (index in 0 until splits.length()) {
                val item = splits.optJSONObject(index) ?: continue
                val file = normalizeZipPath(item.optString("file")) ?: continue
                if (Regex("(^|/)(config\\.|split_)").containsMatchIn(file)) continue
                if (byZipPath.containsKey(file)) return byZipPath.getValue(file)
            }
        }
        val installable = apks.filter { !isAuxiliarySplit(it.path) }
        val pool = if (installable.isNotEmpty()) installable else apks
        val packageName = manifest?.optString("package_name").orEmpty()
        if (packageName.isNotEmpty()) {
            val named = pool.firstOrNull { it.name == "$packageName.apk" }
            if (named != null) return named
        }
        if (pool.size == 1) return pool[0]
        return pool
            .map { it to apkScore(it) }
            .sortedWith(compareByDescending<Pair<File, Int>> { it.second }.thenByDescending { it.first.length() })
            .first()
            .first
    }

    fun listBundle(root: File, packageName: String = ""): BundleParts {
        val apks = listApks(root)
        val base = chooseBaseApk(root, apks)
        val ordered = listOf(base) + apks.filter { it.canonicalFile != base.canonicalFile }
        val manifestName = readManifest(root)?.optString("package_name").orEmpty()
        val fallback = packageName.ifBlank { manifestName }.trim()
        val obbs = listFiles(root)
            .filter { it.name.lowercase().endsWith(".obb") }
            .mapNotNull { file ->
                val owner = fallback.ifBlank { packageFromObbPath(root, file) }
                if (!PACKAGE_NAME.matches(owner) || !SAFE_FILE.matches(file.name)) null
                else ObbFile(file, owner, file.name)
            }
        return BundleParts(ordered, obbs)
    }

    private fun listFiles(root: File): List<File> {
        val found = ArrayList<File>()
        fun walk(dir: File) {
            for (child in dir.listFiles().orEmpty()) {
                if (child.isDirectory) walk(child) else found.add(child)
            }
        }
        walk(root)
        return found
    }

    private fun packageFromObbPath(root: File, file: File): String {
        val relative = relativeZipPath(root, file)
        val match = Regex("(?:^|/)obb/([^/]+)/", RegexOption.IGNORE_CASE).find(relative)
        return match?.groupValues?.getOrNull(1).orEmpty()
    }

    fun extractZip(zipPath: File, dest: File) {
        val root = dest.canonicalFile
        root.mkdirs()
        val methods = methodsFile(root)
        if (methods.exists()) methods.delete()
        ZipFile(zipPath).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                assertSafeEntry(root, entries.nextElement().name)
            }
            val again = zip.entries()
            while (again.hasMoreElements()) {
                val entry = again.nextElement()
                val name = entry.name.replace('\\', '/')
                val target = File(root, name)
                if (entry.isDirectory) {
                    target.mkdirs()
                    continue
                }
                target.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
                methods.appendText("${entry.method}\t$name\n")
            }
        }
    }

    fun packZip(sourceDir: File, destPath: File) {
        val methods = readMethods(sourceDir)
        if (destPath.exists()) destPath.delete()
        ZipOutputStream(destPath.outputStream()).use { zip ->
            val files = ArrayList<File>()
            fun walk(dir: File) {
                for (child in dir.listFiles().orEmpty().sortedBy { it.name }) {
                    if (child.isDirectory) walk(child)
                    else files.add(child)
                }
            }
            walk(sourceDir)
            for (file in files) {
                val name = relativeZipPath(sourceDir, file)
                val method = methods[name] ?: ZipEntry.DEFLATED
                val entry = ZipEntry(name)
                if (method == ZipEntry.STORED) {
                    entry.method = ZipEntry.STORED
                    entry.size = file.length()
                    entry.compressedSize = file.length()
                    entry.crc = crc32(file)
                    zip.putNextEntry(entry)
                    file.inputStream().use { it.copyTo(zip) }
                } else {
                    zip.setLevel(Deflater.DEFAULT_COMPRESSION)
                    entry.method = ZipEntry.DEFLATED
                    zip.putNextEntry(entry)
                    file.inputStream().use { it.copyTo(zip) }
                }
                zip.closeEntry()
            }
        }
        if (!destPath.isFile) throw IllegalStateException("The XAPK could not be packed.")
        methodsFile(sourceDir).delete()
    }

    fun refreshManifestSize(root: File) {
        val manifestPath = File(root, "manifest.json")
        if (!manifestPath.isFile) return
        val manifest = readManifest(root) ?: return
        var total = 0L
        fun walk(dir: File) {
            for (child in dir.listFiles().orEmpty()) {
                if (child.isDirectory) walk(child)
                else if (child.absolutePath != manifestPath.absolutePath) {
                    total += child.length()
                }
            }
        }
        walk(root)
        manifest.put("total_size", total)
        manifestPath.writeText(manifest.toString(2) + "\n")
    }

    private fun apkScore(file: File): Int {
        val signals = manifestSignals(file)
        val stem = file.name.removeSuffix(".apk").removeSuffix(".APK")
        var score = 0
        if (signals.launcher) score += 10
        if (!signals.featureSplit) score += 5
        if (stem.contains('.')) score += 3
        if (stem.equals("base", ignoreCase = true)) score += 2
        return score
    }

    private data class Signals(val launcher: Boolean, val featureSplit: Boolean)

    private fun manifestSignals(apkPath: File): Signals {
        val data = readZipEntry(apkPath, "AndroidManifest.xml") ?: return Signals(false, false)
        val utf16 = String(data, Charsets.UTF_16LE)
        val latin = String(data, Charset.forName("ISO-8859-1"))
        val blob = "$utf16\n$latin"
        return Signals(
            launcher = blob.contains("android.intent.category.LAUNCHER"),
            featureSplit = blob.contains("isFeatureSplit") || blob.contains("asset-pack"),
        )
    }

    private fun readZipEntry(zipPath: File, wantedName: String): ByteArray? {
        return try {
            ZipFile(zipPath).use { zip ->
                val entry = zip.getEntry(wantedName) ?: return null
                zip.getInputStream(entry).use { it.readBytes() }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readManifest(root: File): JSONObject? {
        val manifestPath = File(root, "manifest.json")
        if (!manifestPath.isFile) return null
        val text = manifestPath.readText().removePrefix("\uFEFF")
        return try {
            JSONObject(text)
        } catch (_: Exception) {
            throw IllegalArgumentException("The XAPK manifest.json could not be read.")
        }
    }

    private fun normalizeZipPath(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return value.replace('\\', '/').removePrefix("./")
    }

    private fun methodsFile(root: File) = File(root.parentFile, ".${root.name}.methods")

    private fun readMethods(root: File): Map<String, Int> {
        val file = methodsFile(root)
        if (!file.isFile) return emptyMap()
        return file.readLines().mapNotNull { line ->
            val parts = line.split('\t', limit = 2)
            if (parts.size != 2) null else parts[1] to parts[0].toIntOrNull()
        }.mapNotNull { (name, method) -> method?.let { name to it } }.toMap()
    }

    private fun crc32(file: File): Long {
        val crc = java.util.zip.CRC32()
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                crc.update(buffer, 0, count)
            }
        }
        return crc.value
    }
}
