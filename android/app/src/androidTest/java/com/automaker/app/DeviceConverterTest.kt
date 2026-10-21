package com.automaker.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.automaker.app.engine.PackageConverter
import com.reandroid.apk.ApkModule
import com.reandroid.arsc.value.ValueType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DeviceConverterTest {
    @Test
    fun convertsAnApkOnThePhone() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val dir = instrumentation.targetContext.cacheDir
        val input = File(dir, "sample.apk")
        instrumentation.context.assets.open("sample.apk").use { source ->
            input.outputStream().use { source.copyTo(it) }
        }
        val keystore = File(dir, "signing.p12")
        instrumentation.targetContext.assets.open("signing.p12").use { source ->
            keystore.outputStream().use { source.copyTo(it) }
        }
        val output = File(dir, "signed.apk")
        val result = PackageConverter(PackageConverter.signerFrom(keystore)).convertApk(input, output, {}, {})
        assertEquals("com.example.plainapp", result.packageName)
        assertTrue(result.changes.any { it.contains("game") })
        assertTrue(output.length() > 0)
        val apk = ApkModule.loadApkFile(output)
        try {
            val category = apk.androidManifest.applicationElement.searchAttributeByResourceId(0x01010545)
            assertEquals(ValueType.DEC, category.valueType)
            assertEquals(0, category.data)
        } finally {
            apk.close()
        }
    }
}
