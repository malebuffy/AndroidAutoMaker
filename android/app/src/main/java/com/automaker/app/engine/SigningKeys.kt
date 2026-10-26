package com.automaker.app.engine

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.android.apksig.ApkSigner
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * Signing keys created on this phone, one per converted package.
 *
 * Each key is generated randomly in the Android Keystore the first time a package is converted
 * and reused afterwards, so a converted app can be updated by converting it again. The private
 * key never leaves the Keystore and is never shipped inside the maker APK.
 */
class SigningKeys {
    private val store: KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    @Synchronized
    fun signerFor(packageName: String): ApkSigner.SignerConfig {
        require(PACKAGE_NAME.matches(packageName)) { "The package name is missing." }
        val alias = ALIAS_PREFIX + packageName
        if (!store.containsAlias(alias)) generate(alias, packageName)
        val key = store.getKey(alias, null) as PrivateKey
        val certificate = store.getCertificate(alias) as X509Certificate
        return ApkSigner.SignerConfig.Builder(SIGNER_NAME, key, listOf(certificate)).build()
    }

    private fun generate(alias: String, packageName: String) {
        val notBefore = Date()
        val notAfter = Calendar.getInstance().apply { add(Calendar.YEAR, VALIDITY_YEARS) }.time
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setKeySize(KEY_SIZE)
            // apksig signs v1 with SHA-1 for packages that support API 17 and older.
            .setDigests(KeyProperties.DIGEST_SHA1, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            .setCertificateSubject(X500Principal("CN=$packageName, O=Android Auto Maker"))
            .setCertificateSerialNumber(BigInteger(64, SecureRandom()).add(BigInteger.ONE))
            .setCertificateNotBefore(notBefore)
            .setCertificateNotAfter(notAfter)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, PROVIDER).apply {
            initialize(spec)
            generateKeyPair()
        }
    }

    companion object {
        private const val PROVIDER = "AndroidKeyStore"
        private const val ALIAS_PREFIX = "automaker-sign:"
        private const val SIGNER_NAME = "automaker"
        private const val KEY_SIZE = 2048
        private const val VALIDITY_YEARS = 30
        private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+$")
    }
}
