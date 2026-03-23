package com.aegisnav.app.security

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.preferencesDataStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AeadKeyTemplates
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.SharedPreferencesMigration
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Factory/utility for encrypted Preferences DataStore instances.
 *
 * - One DataStore singleton per file name (cached in [stores]).
 * - Tink AES-256-GCM AEAD initialized once; key stored in Android Keystore.
 * - Includes [SharedPreferencesMigration] so legacy SharedPreferences data is
 *   automatically migrated into the DataStore on first read.
 *
 * Usage:
 *   val ds = SecureDataStore.get(context, "my_prefs")
 *   ds.edit { it[KEY] = value }
 *   ds.data.map { it[KEY] ?: default }
 */
object SecureDataStore {

    // Cache of DataStore instances (must be singleton per file)
    private val stores = mutableMapOf<String, DataStore<Preferences>>()

    // Tink AEAD for encrypting individual preference values
    @Volatile private var aead: Aead? = null

    @Synchronized
    fun get(context: Context, name: String): DataStore<Preferences> {
        return stores.getOrPut(name) {
            // Initialize Tink once
            if (aead == null) {
                AeadConfig.register()
                @Suppress("DEPRECATION") // AeadKeyTemplates is stable for our use-case
                aead = AndroidKeysetManager.Builder()
                    .withSharedPref(
                        context.applicationContext,
                        "aegisnav_datastore_keyset",
                        "aegisnav_tink_prefs"
                    )
                    .withKeyTemplate(AeadKeyTemplates.AES256_GCM)
                    .withMasterKeyUri("android-keystore://aegisnav_datastore_master_key")
                    .build()
                    .keysetHandle
                    .getPrimitive(Aead::class.java)
            }

            // Create DataStore with SharedPreferences migration
            PreferenceDataStoreFactory.create(
                migrations = listOf(
                    SharedPreferencesMigration(context.applicationContext, name)
                ),
                produceFile = {
                    context.applicationContext.preferencesDataStoreFile("encrypted_$name")
                }
            )
        }
    }

    /**
     * Encrypt a string value using Tink AEAD.
     * Returns base64-encoded ciphertext.
     *
     * Must call [get] first so that [aead] is initialized.
     */
    fun encrypt(plaintext: String): String {
        val ct = requireNotNull(aead) { "Call SecureDataStore.get() before encrypt()" }
            .encrypt(plaintext.toByteArray(Charsets.UTF_8), byteArrayOf())
        return android.util.Base64.encodeToString(ct, android.util.Base64.NO_WRAP)
    }

    /**
     * Decrypt a base64-encoded ciphertext produced by [encrypt].
     * Returns the original plaintext string.
     */
    fun decrypt(ciphertext: String): String {
        val ct = android.util.Base64.decode(ciphertext, android.util.Base64.NO_WRAP)
        return String(
            requireNotNull(aead) { "Call SecureDataStore.get() before decrypt()" }
                .decrypt(ct, byteArrayOf()),
            Charsets.UTF_8
        )
    }
}

// ── Blocking helpers for legacy / non-suspend call sites ────────────────────

/**
 * Blocking read — kept for test code compatibility only.
 *
 * **DO NOT USE in production code** — risks ANR if called on the main thread.
 * Use `dataStore.data.first()` in a suspend function, or cache in a `StateFlow` via
 * `dataStore.data.stateIn(scope, SharingStarted.Eagerly, emptyPreferences())`.
 */
@Deprecated(
    message = "Blocks the calling thread — risks ANR on main thread. " +
        "Use dataStore.data.first() in a suspend fun, or stateIn() for a cached StateFlow.",
    replaceWith = ReplaceWith(
        "dataStore.data.first()",
        "kotlinx.coroutines.flow.first"
    ),
    level = DeprecationLevel.WARNING
)
fun DataStore<Preferences>.readBlocking(): Preferences = runBlocking { data.first() }

/**
 * Blocking write — kept for test code compatibility only.
 *
 * **DO NOT USE in production code** — risks ANR if called on the main thread.
 * Use `scope.launch { dataStore.edit { } }` instead.
 */
@Deprecated(
    message = "Blocks the calling thread — risks ANR on main thread. " +
        "Use scope.launch { dataStore.edit { } } instead.",
    replaceWith = ReplaceWith(
        "scope.launch { edit { transform(it) } }",
        "kotlinx.coroutines.launch"
    ),
    level = DeprecationLevel.WARNING
)
fun DataStore<Preferences>.editBlocking(transform: MutablePreferences.() -> Unit) {
    runBlocking { edit { it.transform() } }
}
