package com.jarvis.assistant.online

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Device-local configuration of the Groq provider (Online Brain fallback, after Gemini).
 *
 *  - The API key is encrypted with an AES-256-GCM key that lives in the Android Keystore (non-exportable) and only
 *    the ciphertext is written to SharedPreferences. There is NO plaintext fallback: if encryption is impossible
 *    the key is simply not saved.
 *  - The key is never part of the source, BuildConfig, Git or the ZIP, and it is never logged.
 *  - The model name is not a secret; it is stored in clear (default [DEFAULT_MODEL], editable in the setup screen).
 *
 * One process-wide instance ([get]) so the setup screen and the provider always see the same state.
 */
class GroqConfigStore private constructor(appContext: Context) {

    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile private var cachedKey: String? = null

    /** Cheap (no decryption): is an encrypted key stored? Safe to call from the main thread. */
    fun hasApiKey(): Boolean = prefs.contains(PREF_KEY_BLOB)

    /** The decrypted key, or null when none is stored / it can no longer be decrypted. Never logged. */
    fun getApiKey(): String? {
        cachedKey?.let { return it }
        val blob = prefs.getString(PREF_KEY_BLOB, null) ?: return null
        return when (val r = decrypt(blob)) {
            is Decrypted.Ok -> { cachedKey = r.plain; r.plain }
            Decrypted.Permanent -> { clearApiKey(); null }     // keystore entry lost / data tampered: unusable for good
            Decrypted.Transient -> null                        // keystore busy: keep the blob, try again next time
        }
    }

    /** Encrypts and stores [raw]. Returns false (and stores nothing) when it looks invalid or encryption fails. */
    fun saveApiKey(raw: String): Boolean {
        val key = raw.trim()
        if (!isPlausibleKey(key)) return false
        val blob = encrypt(key) ?: return false
        val ok = prefs.edit().putString(PREF_KEY_BLOB, blob).commit()
        if (ok) cachedKey = key
        return ok
    }

    /** Removes the stored key and its Keystore entry. */
    fun clearApiKey() {
        cachedKey = null
        prefs.edit().remove(PREF_KEY_BLOB).commit()
        try {
            keyStore().deleteEntry(KEY_ALIAS)
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "Keystore entry could not be deleted")
        } catch (e: RuntimeException) {
            Log.w(TAG, "Keystore entry could not be deleted")
        }
    }

    fun getModel(): String =
        prefs.getString(PREF_MODEL, null)?.takeIf { MODEL_REGEX.matches(it) } ?: DEFAULT_MODEL

    /** Blank resets to [DEFAULT_MODEL]. Returns false for an invalid name. */
    fun setModel(raw: String): Boolean {
        val name = raw.trim()
        if (name.isEmpty()) { prefs.edit().remove(PREF_MODEL).commit(); return true }
        if (!MODEL_REGEX.matches(name)) return false
        return prefs.edit().putString(PREF_MODEL, name).commit()
    }

    // ---- Keystore -------------------------------------------------------------------------------

    private sealed interface Decrypted {
        data class Ok(val plain: String) : Decrypted
        object Permanent : Decrypted
        object Transient : Decrypted
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun secretKey(create: Boolean): SecretKey? {
        val ks = keyStore()
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String? = try {
        val key = secretKey(create = true) ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(iv + sealed, Base64.NO_WRAP)
    } catch (e: GeneralSecurityException) {
        Log.w(TAG, "API key could not be encrypted")
        null
    } catch (e: RuntimeException) {                     // ProviderException etc. from a broken keystore
        Log.w(TAG, "API key could not be encrypted")
        null
    }

    private fun decrypt(blob: String): Decrypted = try {
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        if (raw.size <= IV_BYTES) {
            Decrypted.Permanent
        } else {
            val key = secretKey(create = false)
            if (key == null) {
                Decrypted.Permanent
            } else {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES))
                Decrypted.Ok(String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8))
            }
        }
    } catch (e: IllegalArgumentException) {             // not valid Base64
        Decrypted.Permanent
    } catch (e: KeyPermanentlyInvalidatedException) {
        Decrypted.Permanent
    } catch (e: UnrecoverableKeyException) {
        Decrypted.Permanent
    } catch (e: AEADBadTagException) {
        Decrypted.Permanent
    } catch (e: BadPaddingException) {
        Decrypted.Permanent
    } catch (e: GeneralSecurityException) {
        Log.w(TAG, "API key could not be decrypted")
        Decrypted.Transient
    } catch (e: RuntimeException) {
        Log.w(TAG, "API key could not be decrypted")
        Decrypted.Transient
    }

    companion object {
        private const val TAG = "GroqConfig"
        private const val PREFS = "jarvis_groq_config"
        private const val PREF_KEY_BLOB = "api_key_blob"
        private const val PREF_MODEL = "model"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "jarvis_groq_api_key_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128

        /**
         * Production text model listed (and featured) on https://console.groq.com/docs/models when this was written
         * (2026-10-07). Groq retires models; the name stays editable in the setup screen.
         */
        const val DEFAULT_MODEL = "openai/gpt-oss-120b"

        private val MODEL_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9._/-]{0,95}$")

        @Volatile private var instance: GroqConfigStore? = null

        fun get(context: Context): GroqConfigStore =
            instance ?: synchronized(this) {
                instance ?: GroqConfigStore(context.applicationContext).also { instance = it }
            }

        /** No whitespace / control characters (it goes into an HTTP header) and a sane length. */
        internal fun isPlausibleKey(key: String): Boolean =
            key.length in 16..512 && key.none { it.isWhitespace() || it.isISOControl() || it.code > 126 }
    }
}
