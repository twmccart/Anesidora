package com.anesidora

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Pandora's JSON API predates widespread HTTPS, so every request body (except partner login)
 * is additionally encrypted with Blowfish ECB, using keys pulled from Pandora's Android app.
 * These are the same keys that crypt.js / keys.js use in the browser extension, just
 * expressed as the raw key rather than precomputed Blowfish tables.
 */
object PandoraCrypto {
    private const val ENCRYPTION_KEY = "6#26FRL\$ZWD"
    private const val DECRYPTION_KEY = "R=U!LH\$O2B#"
    private const val BLOCK_SIZE = 8

    fun encrypt(plaintext: String): String {
        val input = plaintext.toByteArray(Charsets.UTF_8)
        val paddedLength = (input.size + BLOCK_SIZE - 1) / BLOCK_SIZE * BLOCK_SIZE
        val padded = input.copyOf(paddedLength)
        return toHex(cipher(Cipher.ENCRYPT_MODE, ENCRYPTION_KEY).doFinal(padded))
    }

    fun decrypt(hexCiphertext: String): ByteArray =
        cipher(Cipher.DECRYPT_MODE, DECRYPTION_KEY).doFinal(fromHex(hexCiphertext))

    private fun cipher(mode: Int, key: String): Cipher =
        Cipher.getInstance("Blowfish/ECB/NoPadding").apply {
            init(mode, SecretKeySpec(key.toByteArray(Charsets.US_ASCII), "Blowfish"))
        }

    private fun toHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun fromHex(hex: String): ByteArray =
        ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
}
