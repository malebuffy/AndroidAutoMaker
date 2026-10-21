package com.automaker.app.engine

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import com.reandroid.apk.ApkBundle
import com.reandroid.apk.ApkModule
import com.reandroid.app.AndroidManifest
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlElement
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

private val SPLIT_META = arrayOf(
    "com.android.vending.splits.required",
    "com.android.vending.splits",
    "com.android.stamp.source",
    "com.android.stamp.type",
    "com.android.vending.derived.apk.id",
)

data class ConvertedPackage(
    val changes: List<String>,
    val packageName: String,
    val size: Long,
    val format: String,
)

class PackageConverter(private val signer: ApkSigner.SignerConfig) {
    fun convertApk(
        input: File,
        output: File,
        log: (String) -> Unit,
        onStep: (String) -> Unit,
        cancelled: () -> Boolean = { false },
        kind: String = "game",
        navigationDex: ByteArray? = null,
        lifecycleDex: ByteArray? = null,
    ): ConvertedPackage {
        checkCancelled(cancelled)
        val directory = output.parentFile ?: throw IllegalStateException("The session folder is missing.")
        directory.mkdirs()
        val unsigned = File(directory, "unsigned.apk")
        onStep("decode")
        log("Reading the binary Android manifest.")
        val apk = ApkModule.loadApkFile(input)
        try {
            checkCancelled(cancelled)
            val manifest = apk.androidManifest
                ?: throw IllegalStateException("The decoded APK has no AndroidManifest.xml.")
            onStep("patch")
            val changes = ManifestPatcher.patch(manifest, apk, kind)
            changes.forEach(log)
            checkCancelled(cancelled)
            onStep("build")
            log("Writing the rebuilt APK.")
            apk.apkSignatureBlock = null
            stripSignatures(apk)
            if (unsigned.exists()) unsigned.delete()
            apk.writeApk(unsigned)
            if (kind == "navigation") {
                val serviceBytes = navigationDex
                val lifecycleBytes = lifecycleDex
                if (serviceBytes != null && !zipContains(unsigned, "Lcom/automaker/nav/NavigationCarService;")) {
                    addStoredDex(unsigned, serviceBytes)
                }
                if (lifecycleBytes != null && !zipContains(unsigned, "Landroidx/lifecycle/LifecycleRegistry;")) {
                    addStoredDex(unsigned, lifecycleBytes)
                }
            }
            val packageName = manifest.packageName.orEmpty()
            checkCancelled(cancelled)
            onStep("sign")
            alignAndSign(unsigned, output, log)
            unsigned.delete()
            return ConvertedPackage(changes, packageName, output.length(), "apk")
        } finally {
            apk.close()
        }
    }

    fun convertXapk(
        input: File,
        output: File,
        log: (String) -> Unit,
        onStep: (String) -> Unit,
        cancelled: () -> Boolean = { false },
        kind: String = "game",
        navigationDex: ByteArray? = null,
        lifecycleDex: ByteArray? = null,
    ): ConvertedPackage {
        checkCancelled(cancelled)
        val directory = output.parentFile ?: throw IllegalStateException("The session folder is missing.")
        val extracted = File(directory, "xapk")
        val modules = File(directory, "modules")
        val merged = File(directory, "merged.apk")
        onStep("decode")
        log("Extracting the XAPK.")
        extracted.deleteRecursively()
        modules.deleteRecursively()
        XapkPackages.extractZip(input, extracted)
        val apks = XapkPackages.listApks(extracted)
        if (apks.isEmpty()) throw IllegalArgumentException("The XAPK does not contain an APK.")
        modules.mkdirs()
        apks.forEachIndexed { index, apk ->
            val safe = apk.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            apk.copyTo(File(modules, "${index}_$safe"), overwrite = true)
        }
        ApkBundle().use { bundle ->
            bundle.loadApkDirectory(modules)
            bundle.mergeModules().use { module ->
                stripSplitMetadata(module)
                module.apkSignatureBlock = null
                if (merged.exists()) merged.delete()
                module.writeApk(merged)
            }
        }
        extracted.deleteRecursively()
        modules.deleteRecursively()
        val patched = convertApk(merged, output, log, onStep, cancelled, kind, navigationDex, lifecycleDex)
        merged.delete()
        return patched.copy(format = "apk")
    }

    private fun stripSplitMetadata(apk: ApkModule) {
        val manifest = apk.androidManifest ?: return
        val root = manifest.manifestElement ?: return
        val application = manifest.applicationElement
        removeSplitAttributes(root)
        if (application != null) removeSplitAttributes(application)
        removeNamedChildren(root, "uses-split")
        for (name in SPLIT_META) removeMeta(manifest, name)
        manifest.refresh()
    }

    private fun removeSplitAttributes(element: ResXmlElement) {
        removeAttribute(element, AndroidManifest.ID_requiredSplitTypes, AndroidManifest.NAME_requiredSplitTypes)
        removeAttribute(element, AndroidManifest.ID_splitTypes, AndroidManifest.NAME_splitTypes)
        removeAttribute(element, AndroidManifest.ID_isSplitRequired, AndroidManifest.NAME_isSplitRequired)
        removeAttribute(element, AndroidManifest.ID_extractNativeLibs, AndroidManifest.NAME_extractNativeLibs)
        element.searchAttributeByName(AndroidManifest.NAME_split)?.let { element.removeAttribute(it) }
    }

    private fun removeAttribute(element: ResXmlElement, id: Int, name: String) {
        val attribute = element.searchAttributeByResourceId(id) ?: element.searchAttributeByName(name)
        if (attribute != null) element.removeAttribute(attribute)
    }

    private fun removeNamedChildren(parent: ResXmlElement, tag: String) {
        val found = ArrayList<ResXmlElement>()
        val iterator = parent.getElements(tag)
        while (iterator.hasNext()) {
            val element = iterator.next() as? ResXmlElement ?: continue
            found.add(element)
        }
        for (element in found) element.parentElement?.remove(element)
    }

    private fun removeMeta(manifest: AndroidManifestBlock, name: String) {
        val found = ArrayList<ResXmlElement>()
        val iterator = manifest.recursiveElements()
        while (iterator.hasNext()) {
            val element = iterator.next() as? ResXmlElement ?: continue
            if (element.equalsName(AndroidManifest.TAG_meta_data) && AndroidManifestBlock.getAndroidNameValue(element) == name) {
                found.add(element)
            }
        }
        for (element in found) element.parentElement?.remove(element)
    }

    fun alignAndSign(input: File, output: File, log: (String) -> Unit) {
        val aligned = File(output.parentFile, ".aligning-${output.name}")
        try {
            try {
                ZipAlign.align(input, aligned, pageSize = 16 * 1024, alignment = 4)
                log("Aligned uncompressed files to 4 bytes and native libraries to 16 KB.")
            } catch (error: ZipAlignException) {
                log("16 KB alignment failed. Aligning to 4 bytes.")
                error.message?.let(log)
                if (aligned.exists()) aligned.delete()
                ZipAlign.align(input, aligned, pageSize = 4096, alignment = 4)
            }
            if (output.exists()) output.delete()
            ApkSigner.Builder(listOf(signer))
                .setInputApk(aligned)
                .setOutputApk(output)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setV4SigningEnabled(false)
                .setOtherSignersSignaturesPreserved(false)
                .build()
                .sign()
            val verified = ApkVerifier.Builder(output).build().verify()
            if (!verified.isVerified) {
                val details = verified.errors.joinToString("\n") { it.toString() }
                throw IllegalStateException("The signed package did not verify.\n$details".trim())
            }
            log("Signed and verified with apksig.")
        } finally {
            if (aligned.exists()) aligned.delete()
        }
    }

    private fun zipContains(apkFile: File, marker: String): Boolean {
        val needle = marker.encodeToByteArray()
        ZipFile(apkFile).use { zip ->
            return zip.entries().toList().any { entry ->
                entry.name.matches(Regex("""classes\d*\.dex""")) &&
                    zip.getInputStream(entry).use { containsSequence(it.readBytes(), needle) }
            }
        }
    }

    private fun addStoredDex(apkFile: File, dex: ByteArray) {
        ZipFile(apkFile).use { zip ->
            val entries = zip.entries().toList()
            val names = entries.map { it.name }.toSet()
            var index = 2
            while ("classes$index.dex" in names) index += 1
            val temp = File(apkFile.parentFile, "${apkFile.name}.dexed")
            ZipOutputStream(temp.outputStream()).use { out ->
                for (entry in entries) {
                    val next = ZipEntry(entry.name)
                    next.time = entry.time
                    if (entry.method == ZipEntry.STORED) {
                        next.method = ZipEntry.STORED
                        next.size = entry.size
                        next.compressedSize = entry.size
                        next.crc = entry.crc
                    }
                    out.putNextEntry(next)
                    zip.getInputStream(entry).use { it.copyTo(out) }
                    out.closeEntry()
                }
                val crc = CRC32()
                crc.update(dex)
                val added = ZipEntry("classes$index.dex")
                added.method = ZipEntry.STORED
                added.size = dex.size.toLong()
                added.compressedSize = dex.size.toLong()
                added.crc = crc.value
                out.putNextEntry(added)
                out.write(dex)
                out.closeEntry()
            }
        }
        val temp = File(apkFile.parentFile, "${apkFile.name}.dexed")
        if (temp.exists()) {
            if (!apkFile.delete() || !temp.renameTo(apkFile)) {
                temp.copyTo(apkFile, overwrite = true)
                temp.delete()
            }
        }
    }

    private fun containsSequence(bytes: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || bytes.size < needle.size) return false
        val last = bytes.size - needle.size
        for (start in 0..last) {
            var matches = true
            for (offset in needle.indices) {
                if (bytes[start + offset] != needle[offset]) {
                    matches = false
                    break
                }
            }
            if (matches) return true
        }
        return false
    }

    private fun stripSignatures(apk: ApkModule) {
        val paths = apk.inputSources.mapNotNull { source ->
            source.alias?.takeIf { it.isNotBlank() } ?: source.name
        }
        for (path in paths) {
            val normalized = path.replace('\\', '/')
            val upper = normalized.uppercase()
            val base = upper.substringAfterLast('/')
            val signature = upper.startsWith("META-INF/") && (
                base == "MANIFEST.MF" ||
                    base.endsWith(".SF") ||
                    base.endsWith(".RSA") ||
                    base.endsWith(".DSA") ||
                    base.endsWith(".EC")
                )
            if (signature) apk.removeInputSource(normalized)
        }
    }

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled()) throw CancelledConversion()
    }

    companion object {
        fun signerFrom(keystore: File): ApkSigner.SignerConfig {
            val store = KeyStore.getInstance("PKCS12")
            keystore.inputStream().use { store.load(it, "android".toCharArray()) }
            val key = store.getKey("androiddebugkey", "android".toCharArray()) as PrivateKey
            val certificate = store.getCertificate("androiddebugkey") as X509Certificate
            return ApkSigner.SignerConfig.Builder("androiddebugkey", key, listOf(certificate)).build()
        }
    }
}

class CancelledConversion : RuntimeException("The conversion was cancelled.")
