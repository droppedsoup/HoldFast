package com.holdfast.ssh.ssh

import com.holdfast.ssh.data.SshKey
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.PasswordUtils
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.util.Base64

object Keys {
    val TYPES = listOf("ed25519", "ecdsa-256", "rsa-4096")

    class NeedsPassphrase : Exception("This key is protected by a passphrase")

    /** Generates a key and returns it ready to store. */
    fun generate(type: String, name: String): SshKey {
        val pem = when (type) {
            "ed25519" -> ed25519OpenSsh(name)
            "ecdsa-256" -> pkcs8Pem(KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
            }.generateKeyPair().private.encoded)
            "rsa-4096" -> pkcs8Pem(KeyPairGenerator.getInstance("RSA").apply {
                initialize(4096, SecureRandom())
            }.generateKeyPair().private.encoded)
            else -> error("Unknown key type $type")
        }
        return fromPrivate(pem, null, name)
    }

    /** Builds an SshKey from an imported/generated private key (any format sshj reads). */
    fun fromPrivate(privateKey: String, passphrase: String?, name: String): SshKey {
        val pub = load(privateKey, passphrase).public
        val type = KeyType.fromKey(pub).toString()
        return SshKey(
            name = name, type = type, privateKey = privateKey.trim() + "\n",
            publicKey = publicLine(pub, name), passphrase = passphrase?.ifEmpty { null },
        )
    }

    fun provider(key: SshKey, askPassphrase: (() -> String?)? = null): KeyProvider {
        val pass = key.passphrase
        return load(key.privateKey, pass ?: askPassphrase?.invoke())
    }

    fun load(privateKey: String, passphrase: String?): KeyProvider {
        val finder: PasswordFinder? = passphrase?.let { PasswordUtils.createOneOff(it.toCharArray()) }
        val kp = SSHClient(DefaultConfig()).loadKeys(privateKey.trim() + "\n", null, finder)
        try {
            kp.public // forces parse/decrypt
        } catch (e: Exception) {
            if (passphrase == null && (e.message?.contains("ncrypt", true) == true ||
                    e.message?.contains("assword", true) == true || e.message?.contains("ecrypt", true) == true)
            ) throw NeedsPassphrase()
            throw e
        }
        return kp
    }

    fun blob(pub: PublicKey): ByteArray = Buffer.PlainBuffer().putPublicKey(pub).compactData

    fun publicLine(pub: PublicKey, comment: String): String =
        KeyType.fromKey(pub).toString() + " " + Base64.getEncoder().encodeToString(blob(pub)) +
            (if (comment.isBlank()) "" else " " + comment.replace(Regex("\\s+"), "-"))

    fun fingerprint(pub: PublicKey): String =
        "SHA256:" + Base64.getEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(blob(pub)))

    private fun pkcs8Pem(der: ByteArray): String =
        "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) +
            "\n-----END PRIVATE KEY-----\n"

    /** Unencrypted openssh-key-v1 container for an Ed25519 key (it's re-encrypted by the vault). */
    private fun ed25519OpenSsh(comment: String): String {
        val priv = Ed25519PrivateKeyParameters(SecureRandom())
        val seed = priv.encoded
        val pub = priv.generatePublicKey().encoded

        fun DataOutputStream.str(b: ByteArray) { writeInt(b.size); write(b) }
        fun DataOutputStream.str(s: String) = str(s.toByteArray())

        val pubBlob = ByteArrayOutputStream().also { o ->
            DataOutputStream(o).apply { str("ssh-ed25519"); str(pub) }
        }.toByteArray()

        val check = SecureRandom().nextInt()
        val privSection = ByteArrayOutputStream()
        DataOutputStream(privSection).apply {
            writeInt(check); writeInt(check)
            str("ssh-ed25519"); str(pub); str(seed + pub); str(comment)
            var pad = 1
            while (privSection.size() % 8 != 0) writeByte(pad++)
        }

        val all = ByteArrayOutputStream()
        DataOutputStream(all).apply {
            write("openssh-key-v1".toByteArray()); writeByte(0)
            str("none"); str("none"); str(ByteArray(0))
            writeInt(1)
            str(pubBlob)
            str(privSection.toByteArray())
        }
        val b64 = Base64.getMimeEncoder(70, "\n".toByteArray()).encodeToString(all.toByteArray())
        return "-----BEGIN OPENSSH PRIVATE KEY-----\n$b64\n-----END OPENSSH PRIVATE KEY-----\n"
    }
}
