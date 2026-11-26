package com.radiome.core.protocol.crypto

import com.radiome.core.protocol.payload.ContentType
import com.radiome.core.protocol.payload.Payload
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end encryption of a whole payload with AES-256-GCM under a [GroupKey]
 * ([ContentType.ENCRYPTED] on air). Only the session header's length and the clear STATION frame are visible to
 * a listener; content type, name and bytes are inside.
 *
 * ```
 * envelope  : version u8 (= 1) | key id u16 | nonce 12 bytes (random) | ciphertext | GCM tag 16 bytes
 * plaintext : inner content type u8 | name length u8 | name UTF-8 | data length u16 | data | zero padding
 * AAD       : version | key id | sender callsign (ASCII, as in its STATION frame)
 * ```
 *
 * The plaintext is padded to a multiple of [PAD_TO] bytes so short messages (a position, "OK") look alike.
 * Binding the sender's callsign as AAD means a recorded packet replayed under another callsign fails to open.
 * Random 96-bit nonces are safe for far more messages than a radio group will ever send under one key.
 */
object SecureEnvelope {
    const val VERSION = 1
    const val NONCE_BYTES = 12
    const val TAG_BYTES = 16
    const val HEADER_BYTES = 3 + NONCE_BYTES
    const val OVERHEAD = HEADER_BYTES + TAG_BYTES
    const val PAD_TO = 16
    private val random = SecureRandom()

    sealed interface Opened {
        data class Ok(val payload: Payload) : Opened
        /** Encrypted for another group key (or we have none). */
        data class WrongKey(val keyId: Int) : Opened
        /** Right key id but the tag did not verify: tampered, replayed under another callsign, or corrupt. */
        data object Forged : Opened
        data object Malformed : Opened
    }

    fun seal(payload: Payload, key: GroupKey, senderCallsign: String): Payload {
        require(payload.type != ContentType.ENCRYPTED) { "already encrypted" }
        val name = payload.name.toByteArray(Charsets.UTF_8).let { if (it.size > 255) it.copyOf(255) else it }
        require(payload.bytes.size <= 0xFFFF) { "payload too large to encrypt" }
        val body = 1 + 1 + name.size + 2 + payload.bytes.size
        val padded = (body + PAD_TO - 1) / PAD_TO * PAD_TO
        val plain = ByteArray(padded)
        plain[0] = payload.type.code.toByte()
        plain[1] = name.size.toByte()
        name.copyInto(plain, 2)
        var at = 2 + name.size
        plain[at] = (payload.bytes.size ushr 8).toByte()
        plain[at + 1] = payload.bytes.size.toByte()
        at += 2
        payload.bytes.copyInto(plain, at)

        val header = ByteArray(HEADER_BYTES)
        header[0] = VERSION.toByte()
        header[1] = (key.keyId ushr 8).toByte()
        header[2] = key.keyId.toByte()
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        nonce.copyInto(header, 3)
        val cipher = cipher(Cipher.ENCRYPT_MODE, key, nonce)
        cipher.updateAAD(aad(header, senderCallsign))
        val sealed = cipher.doFinal(plain)
        plain.fill(0)
        return Payload(ContentType.ENCRYPTED, header + sealed)
    }

    fun keyIdOf(envelope: ByteArray): Int? =
        if (envelope.size < OVERHEAD || envelope[0].toInt() != VERSION) null
        else ((envelope[1].toInt() and 0xFF) shl 8) or (envelope[2].toInt() and 0xFF)

    fun open(envelope: ByteArray, key: GroupKey?, senderCallsign: String): Opened {
        val id = keyIdOf(envelope) ?: return Opened.Malformed
        if (key == null || key.keyId != id) return Opened.WrongKey(id)
        val header = envelope.copyOfRange(0, HEADER_BYTES)
        val nonce = envelope.copyOfRange(3, HEADER_BYTES)
        val plain = try {
            cipher(Cipher.DECRYPT_MODE, key, nonce).run {
                updateAAD(aad(header, senderCallsign))
                doFinal(envelope, HEADER_BYTES, envelope.size - HEADER_BYTES)
            }
        } catch (e: AEADBadTagException) {
            return Opened.Forged
        } catch (e: java.security.GeneralSecurityException) {
            return Opened.Forged
        }
        if (plain.size < 4) return Opened.Malformed
        val type = ContentType.fromCode(plain[0].toInt() and 0xFF) ?: return Opened.Malformed
        if (type == ContentType.ENCRYPTED) return Opened.Malformed
        val nameLen = plain[1].toInt() and 0xFF
        if (plain.size < 4 + nameLen) return Opened.Malformed
        val name = String(plain, 2, nameLen, Charsets.UTF_8)
        val at = 2 + nameLen
        val len = ((plain[at].toInt() and 0xFF) shl 8) or (plain[at + 1].toInt() and 0xFF)
        if (plain.size < at + 2 + len) return Opened.Malformed
        return Opened.Ok(Payload(type, plain.copyOfRange(at + 2, at + 2 + len), name))
    }

    /** Envelope size for a payload of [dataBytes] with a [nameBytes] name. */
    fun sealedSize(dataBytes: Int, nameBytes: Int = 0): Int = OVERHEAD + (4 + nameBytes + dataBytes + PAD_TO - 1) / PAD_TO * PAD_TO

    private fun aad(header: ByteArray, callsign: String): ByteArray = header.copyOfRange(0, 3) + callsign.toByteArray(Charsets.US_ASCII)

    private fun cipher(mode: Int, key: GroupKey, nonce: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key.encoded(), "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
        }
}
