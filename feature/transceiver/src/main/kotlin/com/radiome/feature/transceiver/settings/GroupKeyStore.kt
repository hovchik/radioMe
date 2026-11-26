package com.radiome.feature.transceiver.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.radiome.core.protocol.crypto.GroupKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The group key for end-to-end encryption, as the rest of the app sees it. Faked in tests. */
interface GroupKeyProvider {
    /** Current key, or null when none is set. */
    val key: StateFlow<GroupKey?>

    /** Derive the key from [passphrase] (slow: PBKDF2) and store it. */
    suspend fun setPassphrase(passphrase: String): GroupKey

    suspend fun clear()

    /** For callers that must answer synchronously (the service's dependencies). */
    fun current(): GroupKey?
}

/**
 * Stores the derived group key wrapped by a non-exportable AES key in the Android Keystore, so the raw key never
 * sits on disk in clear and the passphrase is never stored at all.
 */
class KeystoreGroupKeyProvider(
    private val store: DataStore<Preferences>,
    scope: CoroutineScope,
) : GroupKeyProvider {
    private val _key = MutableStateFlow<GroupKey?>(null)
    override val key: StateFlow<GroupKey?> = _key.asStateFlow()
    @Volatile private var loaded = false

    init {
        scope.launch(Dispatchers.IO) { load() }
    }

    private suspend fun load(): GroupKey? {
        val wrapped = store.data.first()[WRAPPED]
        val k = wrapped?.let { runCatching { unwrap(it) }.getOrNull() }
        _key.value = k
        loaded = true
        return k
    }

    override fun current(): GroupKey? = if (loaded) _key.value else runBlocking(Dispatchers.IO) { load() }

    override suspend fun setPassphrase(passphrase: String): GroupKey = withContext(Dispatchers.Default) {
        val k = GroupKey.fromPassphrase(passphrase)
        val wrapped = wrap(k)
        store.edit { it[WRAPPED] = wrapped }
        _key.value = k
        loaded = true
        k
    }

    override suspend fun clear() {
        store.edit { it.remove(WRAPPED) }
        _key.value = null
    }

    private fun wrappingKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun wrap(k: GroupKey): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val raw = k.encoded()
        val sealed = cipher.iv + cipher.doFinal(raw)
        raw.fill(0)
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    private fun unwrap(s: String): GroupKey {
        val b = Base64.decode(s, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, b, 0, IV_BYTES))
        return GroupKey(cipher.doFinal(b, IV_BYTES, b.size - IV_BYTES))
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "radiome.group_key_wrap"
        const val IV_BYTES = 12
        val WRAPPED = stringPreferencesKey("group_key_wrapped")
    }
}
