package com.aegisnav.app.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.aegisnav.app.security.SecureDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Thrown when the Keystore key requires recent device authentication and none has occurred.
 * Callers should prompt for device credentials and retry.
 */
class KeystoreAuthRequiredException : Exception("Device authentication required to access database key")

object DatabaseKeyManager {
    private const val KEYSTORE_ALIAS = "an_db_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val PREFS_NAME = "an_secure"
    private const val PREF_DB_PASS = "db_pass_enc"

    /**
     * Returns the database passphrase, decrypting it with the Keystore-backed key.
     * @throws KeystoreAuthRequiredException if device authentication is needed first.
     */
    @Throws(KeystoreAuthRequiredException::class)
    fun getOrCreatePassphrase(context: Context): ByteArray {
        try {
            val dataStore = SecureDataStore.get(context, PREFS_NAME)
            val dbPassKey = stringPreferencesKey(PREF_DB_PASS)
            // Pattern A: Room DB builder requires passphrase synchronously.
            // We use runBlocking(Dispatchers.IO) here — this is NOT called on the main thread
            // because provideAppDatabase() runs during Hilt singleton init on Dispatchers.IO.
            // This is the only safe remaining use of runBlocking in production code.
            val existing = runBlocking(Dispatchers.IO) { dataStore.data.first()[dbPassKey] }
            if (existing != null) {
                return decryptWithKeystore(Base64.decode(existing, Base64.DEFAULT))
            }
            // Generate 32 random bytes as passphrase
            val passphrase = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            // Encrypt with Keystore key and store
            val encrypted = encryptWithKeystore(passphrase)
            runBlocking(Dispatchers.IO) {
                dataStore.edit { it[dbPassKey] = Base64.encodeToString(encrypted, Base64.DEFAULT) }
            }
            return passphrase
        } catch (e: UserNotAuthenticatedException) {
            throw KeystoreAuthRequiredException()
        } catch (e: Exception) {
            // Wrap any other Keystore auth errors (e.g. InvalidKeyException with auth cause)
            if (e.cause is UserNotAuthenticatedException) throw KeystoreAuthRequiredException()
            throw e
        }
    }

    private fun getOrCreateKeystoreKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).also { it.load(null) }
        return if (ks.containsAlias(KEYSTORE_ALIAS)) {
            (ks.getEntry(KEYSTORE_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        } else {
            val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            kg.init(KeyGenParameterSpec.Builder(KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        setUserAuthenticationParameters(
                            30,
                            KeyProperties.AUTH_DEVICE_CREDENTIAL or KeyProperties.AUTH_BIOMETRIC_STRONG
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        setUserAuthenticationValidityDurationSeconds(30)
                    }
                }
                .build())
            kg.generateKey()
        }
    }

    private fun encryptWithKeystore(data: ByteArray): ByteArray {
        val key = getOrCreateKeystoreKey()
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(data)
        // Prepend IV (12 bytes) to ciphertext
        return iv + encrypted
    }

    private fun decryptWithKeystore(data: ByteArray): ByteArray {
        val key = getOrCreateKeystoreKey()
        val iv = data.copyOfRange(0, 12)
        val ciphertext = data.copyOfRange(12, data.size)
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        val spec = javax.crypto.spec.GCMParameterSpec(128, iv)
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key, spec)
        return cipher.doFinal(ciphertext)
    }
}
