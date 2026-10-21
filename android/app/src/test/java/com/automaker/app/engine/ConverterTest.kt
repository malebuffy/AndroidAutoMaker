package com.automaker.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ConverterTest {
    private val sdk = File(System.getenv("LOCALAPPDATA"), "Android/Sdk")
    private val java = File("C:/Program Files/Java/jdk-17/bin/java.exe")
    private val project = File("..").canonicalFile.parentFile
    private val keystore = File("src/main/assets/signing.p12")

    @Test(timeout = 180000)
    fun alignsNativeLibrariesTo16Kb() {
        val directory = Files.createTempDirectory("align").toFile()
        val input = File(directory, "in.zip")
        ZipOutputStream(input.outputStream()).use { zip ->
            val entry = ZipEntry("lib/arm64-v8a/libsample.so")
            val bytes = ByteArray(32) { it.toByte() }
            entry.method = ZipEntry.STORED
            entry.size = bytes.size.toLong()
            entry.compressedSize = bytes.size.toLong()
            val crc = CRC32()
            crc.update(bytes)
            entry.crc = crc.value
            zip.putNextEntry(entry)
            zip.write(bytes)
            zip.closeEntry()
        }
        val output = File(directory, "out.zip")
        ZipAlign.align(input, output, pageSize = 16 * 1024, alignment = 4)
        val offset = storedDataOffset(output, "lib/arm64-v8a/libsample.so")
        assertEquals(0, offset % (16 * 1024))
    }

    @Test
    fun choosesTheInstallableBaseInsteadOfAnAssetPack() {
        val root = Files.createTempDirectory("xapk-base").toFile()
        File(root, "UnityDataAssetPack.apk").writeBytes(fakeApk("isFeatureSplit asset-pack"))
        File(root, "config.arm64_v8a.apk").writeBytes(fakeApk("config"))
        File(root, "com.example.game.apk").writeBytes(fakeApk("android.intent.category.LAUNCHER"))
        val base = XapkPackages.chooseBaseApk(root)
        assertEquals("com.example.game.apk", base.name)
    }

    @Test
    fun rejectsZipSlip() {
        val root = Files.createTempDirectory("slip").toFile()
        var failed = false
        try {
            XapkPackages.assertSafeEntry(root, "../outside.txt")
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
    }

    @Test(timeout = 240000)
    fun convertsAnApkAndAnXapk() {
        val aapt2 = File(sdk, "build-tools/36.0.0/aapt2.exe")
        val androidJar = File(sdk, "platforms/android-36/android.jar")
        val zipalign = File(sdk, "build-tools/36.0.0/zipalign.exe")
        val apktool = File(project, "tools/apktool.jar")
        assertTrue("aapt2 is required", aapt2.isFile)
        assertTrue("android.jar is required", androidJar.isFile)
        assertTrue(java.isFile)
        assertTrue(apktool.isFile)
        assertTrue(keystore.isFile)

        val directory = Files.createTempDirectory("auto-maker-android").toFile()
        val sample = buildSample(directory, aapt2, androidJar)
        val signer = PackageConverter.signerFrom(keystore)
        val converter = PackageConverter(signer)
        val output = File(directory, "out/signed.apk")
        output.parentFile.mkdirs()
        val result = converter.convertApk(sample, output, {}, {})
        assertEquals("com.example.plainapp", result.packageName)
        assertTrue(result.changes.any { it.contains("game") })
        assertTrue(output.length() > 0)

        run(zipalign.absolutePath, listOf("-c", "-P", "16", "4", output.absolutePath))
        val decoded = File(directory, "decoded")
        val framework = File(project, "tools/framework")
        framework.mkdirs()
        run(
            java.absolutePath,
            listOf(
                "-jar", apktool.absolutePath,
                "d", "-f", "-s",
                "-p", framework.absolutePath,
                "-o", decoded.absolutePath,
                output.absolutePath,
            ),
        )
        val manifest = File(decoded, "AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:appCategory=\"game\""))
        assertTrue(manifest.contains("android:isGame=\"true\""))
        assertTrue(manifest.contains("android.intent.category.CAR_LAUNCHER"))
        assertTrue(manifest.contains("android.hardware.type.automotive"))
        assertTrue(manifest.contains("android.hardware.gamepad"))
        assertTrue(manifest.contains("android:required=\"false\""))
        assertFalse(manifest.contains("distractionOptimized"))
        assertFalse(manifest.contains("derived.apk.id"))
        assertTrue(activityTag(manifest, "com.example.plainapp.MainActivity").contains("android:exported=\"true\""))
        assertTrue(activityTag(manifest, "com.example.plainapp.Other").contains("android:exported=\"false\""))

        val navigation = File(directory, "out/navigation.apk")
        val navigationDex = File(project, "tools/navigation.dex").readBytes()
        val lifecycleDex = File(project, "tools/lifecycle.dex").readBytes()
        converter.convertApk(sample, navigation, {}, {}, kind = "navigation", navigationDex = navigationDex, lifecycleDex = lifecycleDex)
        val navigationDecoded = File(directory, "navigation-decoded")
        run(
            java.absolutePath,
            listOf(
                "-jar", apktool.absolutePath,
                "d", "-f", "-s",
                "-p", framework.absolutePath,
                "-o", navigationDecoded.absolutePath,
                navigation.absolutePath,
            ),
        )
        val navigationManifest = File(navigationDecoded, "AndroidManifest.xml").readText()
        val navigationXml = File(navigationDecoded, "res/xml/automotive_app_desc.xml").readText()
        assertTrue(navigationManifest.contains("android.intent.category.CAR_LAUNCHER"))
        assertTrue(navigationManifest.contains("androidx.car.app.category.NAVIGATION"))
        assertTrue(navigationManifest.contains("com.automaker.nav.NavigationCarService"))
        assertTrue(navigation.readBytes().toString(Charsets.ISO_8859_1).contains("Lcom/automaker/nav/NavigationCarService;"))
        assertTrue(navigationManifest.contains("androidx.car.app.NAVIGATION_TEMPLATES"))
        assertTrue(navigationManifest.contains("androidx.car.app.minCarApiLevel"))
        assertTrue(navigationManifest.contains("com.google.android.gms.car.application"))
        assertTrue(navigationManifest.contains("android.hardware.gamepad"))
        assertFalse(navigationManifest.contains("android.app.Activity"))
        assertFalse(navigationManifest.contains("android:appCategory=\"game\""))
        assertFalse(navigationManifest.contains("android:isGame=\"true\""))
        for (name in listOf("media", "template", "service", "projection")) {
            assertTrue(navigationXml.contains("name=\"$name\""))
        }

        val again = converter.convertApk(output, File(directory, "out/again.apk"), {}, {})
        assertTrue(again.changes.any { it.contains("Prepared as a parked game") })

        val xapkDir = File(directory, "bundle")
        xapkDir.mkdirs()
        sample.copyTo(File(xapkDir, "com.example.plainapp.apk"))
        sample.copyTo(File(xapkDir, "config.en.apk"))
        val obb = File(xapkDir, "Android/obb/com.example.plainapp/main.obb")
        obb.parentFile.mkdirs()
        obb.writeText("expansion")
        File(xapkDir, "manifest.json").writeText(
            """
            {
              "package_name": "com.example.plainapp",
              "split_apks": [
                {"id": "base", "file": "com.example.plainapp.apk"},
                {"id": "config.en", "file": "config.en.apk"}
              ]
            }
            """.trimIndent(),
        )
        val xapk = File(directory, "sample.xapk")
        zipDirectory(xapkDir, xapk)
        val xapkOut = File(directory, "out/signed.apk")
        val packed = converter.convertXapk(xapk, xapkOut, {}, {})
        assertEquals("apk", packed.format)
        assertTrue(packed.changes.any { it.contains("Prepared as a parked game") })
        ZipFile(xapkOut).use { zip ->
            assertTrue(zip.getEntry("AndroidManifest.xml") != null)
            assertTrue(zip.getEntry("manifest.json") == null)
        }
    }

    private fun buildSample(directory: File, aapt2: File, androidJar: File): File {
        val manifest = File(directory, "AndroidManifest.xml")
        manifest.writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.plainapp"
                android:versionCode="1"
                android:versionName="1.0">
                <uses-sdk android:minSdkVersion="24" android:targetSdkVersion="34"/>
                <uses-feature android:name="android.hardware.type.automotive" android:required="true"/>
                <application android:label="@string/app_name" android:appCategory="video" android:isGame="false" android:hasCode="false">
                    <activity android:name="com.example.plainapp.MainActivity" android:exported="false">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN"/>
                            <category android:name="android.intent.category.LAUNCHER"/>
                        </intent-filter>
                        <meta-data android:name="distractionOptimized" android:value="true"/>
                    </activity>
                    <activity android:name="com.example.plainapp.Other" android:exported="false"/>
                    <meta-data android:name="com.android.vending.derived.apk.id" android:value="99"/>
                </application>
            </manifest>
            """.trimIndent(),
        )
        val values = File(directory, "res/values")
        values.mkdirs()
        val strings = File(values, "strings.xml")
        strings.writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="app_name">Plain App</string>
            </resources>
            """.trimIndent(),
        )
        val compiled = File(directory, "compiled")
        compiled.mkdirs()
        run(aapt2.absolutePath, listOf("compile", "-o", compiled.absolutePath, strings.absolutePath))
        val unsigned = File(directory, "sample-unsigned.apk")
        run(
            aapt2.absolutePath,
            listOf(
                "link",
                "-o", unsigned.absolutePath,
                "-I", androidJar.absolutePath,
                "--manifest", manifest.absolutePath,
                "--min-sdk-version", "24",
                "--target-sdk-version", "34",
                File(compiled, "values_strings.arsc.flat").absolutePath,
            ),
        )
        val signed = File(directory, "sample.apk")
        val signer = PackageConverter.signerFrom(keystore)
        PackageConverter(signer).alignAndSign(unsigned, signed) {}
        return signed
    }

    private fun activityTag(xml: String, name: String): String {
        val pattern = Regex("<activity\\b[^>]*android:name=\"$name\"[^>]*>")
        return pattern.find(xml)?.value ?: error("Missing activity $name in $xml")
    }

    private fun fakeApk(marker: String): ByteArray {
        val directory = Files.createTempDirectory("fake").toFile()
        val file = File(directory, "fake.apk")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(marker.toByteArray(Charsets.UTF_16LE))
            zip.closeEntry()
        }
        return file.readBytes()
    }

    private fun zipDirectory(source: File, dest: File) {
        ZipOutputStream(dest.outputStream()).use { zip ->
            val files = ArrayList<File>()
            fun walk(dir: File) {
                for (child in dir.listFiles().orEmpty()) {
                    if (child.isDirectory) walk(child) else files.add(child)
                }
            }
            walk(source)
            for (file in files) {
                val name = source.toPath().relativize(file.toPath()).toString().replace('\\', '/')
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun storedDataOffset(file: File, name: String): Long {
        return RandomAccessFile(file, "r").use { raf ->
            val size = raf.length()
            val tailLen = minOf(size, 22L + 65535).toInt()
            val tail = ByteArray(tailLen)
            raf.seek(size - tailLen)
            raf.readFully(tail)
            var eocd = -1
            for (index in tail.size - 22 downTo 0) {
                if (u32(tail, index) == 0x06054b50L) {
                    eocd = index
                    break
                }
            }
            val cdSize = u32(tail, eocd + 12)
            val cdOffset = u32(tail, eocd + 16)
            val directory = ByteArray(cdSize.toInt())
            raf.seek(cdOffset)
            raf.readFully(directory)
            var offset = 0
            while (offset + 46 <= directory.size) {
                val nameLen = u16(directory, offset + 28)
                val extraLen = u16(directory, offset + 30)
                val commentLen = u16(directory, offset + 32)
                val local = u32(directory, offset + 42)
                val entryName = directory.decodeToString(offset + 46, offset + 46 + nameLen)
                if (entryName == name) {
                    val header = ByteArray(30)
                    raf.seek(local)
                    raf.readFully(header)
                    return@use local + 30 + u16(header, 26) + u16(header, 28)
                }
                offset += 46 + nameLen + extraLen + commentLen
            }
            error("Missing $name")
        }
    }

    private fun u16(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun u32(bytes: ByteArray, offset: Int): Long {
        return (u16(bytes, offset).toLong() and 0xFFFF) or ((u16(bytes, offset + 2).toLong() and 0xFFFF) shl 16)
    }

    private fun run(command: String, args: List<String>) {
        val process = ProcessBuilder(listOf(command) + args).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        val code = process.waitFor()
        if (code != 0) throw AssertionError("$command failed ($code)\n$output")
    }
}
