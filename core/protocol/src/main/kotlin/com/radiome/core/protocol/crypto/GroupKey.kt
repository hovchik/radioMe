package com.radiome.core.protocol.crypto

import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * A pre-shared 256-bit group key. Everyone in the group enters the same passphrase; the key is derived with
 * PBKDF2-HMAC-SHA256 so the passphrase itself never needs to be stored or sent.
 *
 * [fingerprint] (shown in Settings) lets people compare keys by voice. [keyId] is 2 bytes of a different hash that
 * goes on air, so a receiver can tell "not my group" from "corrupted" without learning anything about the key.
 */
class GroupKey(private val bytes: ByteArray) {
    init {
        require(bytes.size == KEY_BYTES) { "group key must be $KEY_BYTES bytes" }
    }

    fun encoded(): ByteArray = bytes.copyOf()

    val keyId: Int = sha256(ID_LABEL, bytes).let { ((it[0].toInt() and 0xFF) shl 8) or (it[1].toInt() and 0xFF) }

    /** 8 hex digits in two groups, for comparing keys out loud: "3FA2-91C0". */
    val fingerprint: String = sha256(FP_LABEL, bytes).take(4).joinToString("") { "%02X".format(it) }.let { it.take(4) + "-" + it.drop(4) }

    override fun equals(other: Any?): Boolean = other is GroupKey && MessageDigest.isEqual(bytes, other.bytes)
    override fun hashCode(): Int = keyId
    override fun toString(): String = "GroupKey($fingerprint)"

    companion object {
        const val KEY_BYTES = 32
        const val MIN_PASSPHRASE = 8
        /** Slow enough to make guessing a short passphrase from a recording expensive; ~0.2-0.5 s on a phone. */
        const val ITERATIONS = 150_000
        private val SALT = "RadioMe group key v1".toByteArray(Charsets.UTF_8)
        private val ID_LABEL = "RadioMe key id v1".toByteArray(Charsets.UTF_8)
        private val FP_LABEL = "RadioMe fingerprint v1".toByteArray(Charsets.UTF_8)

        fun fromPassphrase(passphrase: String, iterations: Int = ITERATIONS): GroupKey {
            require(passphrase.length >= MIN_PASSPHRASE) { "passphrase must have at least $MIN_PASSPHRASE characters" }
            val spec = PBEKeySpec(passphrase.toCharArray(), SALT, iterations, KEY_BYTES * 8)
            try {
                val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
                return GroupKey(key)
            } finally {
                spec.clearPassword()
            }
        }

        private fun sha256(label: ByteArray, key: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").run {
                update(label)
                update(key)
                digest()
            }
    }
}
