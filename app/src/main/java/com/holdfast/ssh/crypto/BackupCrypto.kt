package com.holdfast.ssh.crypto

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Passphrase encryption for .holdfast backups and gist sync.
 * Format: "HFB1" | iterations(int) | salt(16) | iv(12) | AES-256-GCM ciphertext.
 */
object BackupCrypto {
    private val MAGIC = "HFB1".toByteArray()
    const val ITERATIONS = 310_000

    class WrongPassphrase : Exception("Wrong passphrase or corrupted backup")

    private fun derive(pass: CharArray, salt: ByteArray, iters: Int): SecretKeySpec {
        val spec = PBEKeySpec(pass, salt, iters, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }

    fun encrypt(plain: ByteArray, passphrase: String, iterations: Int = ITERATIONS): ByteArray {
        require(passphrase.isNotEmpty()) { "Passphrase required" }
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val iv = ByteArray(12).also(rnd::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, derive(passphrase.toCharArray(), salt, iterations), GCMParameterSpec(128, iv))
        c.updateAAD(MAGIC)
        val ct = c.doFinal(plain)
        return ByteBuffer.allocate(4 + 4 + 16 + 12 + ct.size)
            .put(MAGIC).putInt(iterations).put(salt).put(iv).put(ct).array()
    }

    fun decrypt(blob: ByteArray, passphrase: String): ByteArray {
        if (blob.size < 36 || !blob.copyOfRange(0, 4).contentEquals(MAGIC)) throw WrongPassphrase()
        val bb = ByteBuffer.wrap(blob, 4, blob.size - 4)
        val iters = bb.int
        if (iters < 1000 || iters > 10_000_000) throw WrongPassphrase()
        val salt = ByteArray(16).also { bb.get(it) }
        val iv = ByteArray(12).also { bb.get(it) }
        val ct = ByteArray(bb.remaining()).also { bb.get(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, derive(passphrase.toCharArray(), salt, iters), GCMParameterSpec(128, iv))
        c.updateAAD(MAGIC)
        return try {
            c.doFinal(ct)
        } catch (e: AEADBadTagException) {
            throw WrongPassphrase()
        }
    }
}
