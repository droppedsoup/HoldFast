package com.holdfast.ssh

import com.holdfast.ssh.crypto.BackupCrypto
import com.holdfast.ssh.data.Host
import com.holdfast.ssh.data.VaultData
import com.holdfast.ssh.ssh.Keys
import com.holdfast.ssh.ssh.Remote
import com.holdfast.ssh.ssh.Uploader
import com.holdfast.ssh.util.Backoff
import com.holdfast.ssh.util.SnippetVars
import com.holdfast.ssh.util.Tailnet
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.Socket
import java.nio.file.Files
import java.security.PublicKey
import java.security.Security

class CoreTest {
    companion object {
        @BeforeClass @JvmStatic fun bc() { Security.removeProvider("BC"); Security.insertProviderAt(BouncyCastleProvider(), 1) }
    }

    @Test fun backupRoundTrip() {
        val data = "hello vault".toByteArray()
        val blob = BackupCrypto.encrypt(data, "correct horse", 2000)
        assertArrayEquals(data, BackupCrypto.decrypt(blob, "correct horse"))
    }

    @Test(expected = BackupCrypto.WrongPassphrase::class) fun backupWrongPassphrase() {
        BackupCrypto.decrypt(BackupCrypto.encrypt("x".toByteArray(), "right one", 2000), "wrong one")
    }

    @Test(expected = BackupCrypto.WrongPassphrase::class) fun backupTampered() {
        val b = BackupCrypto.encrypt("x".toByteArray(), "pass1234", 2000); b[b.size - 1] = (b.last() + 1).toByte()
        BackupCrypto.decrypt(b, "pass1234")
    }

    @Test fun vaultJsonRoundTrip() {
        val v = VaultData(hosts = mutableListOf(Host(label = "a", address = "100.1.2.3", username = "u", tags = listOf("x"))))
        val back = VaultData.fromJson(JSONObject(v.toJson().toString()))
        assertEquals("100.1.2.3", back.hosts[0].address); assertEquals(listOf("x"), back.hosts[0].tags)
    }

    @Test fun tailscaleDetection() {
        assertTrue(Tailnet.isTailscale("100.101.102.103"))
        assertTrue(Tailnet.isTailscale("my-pc.tail1234ab.ts.net."))
        assertTrue(Tailnet.isTailscale("fd7a:115c:a1e0::1"))
        assertFalse(Tailnet.isTailscale("100.200.1.1"))
        assertFalse(Tailnet.isTailscale("192.168.1.5"))
        assertFalse(Tailnet.isTailscale("example.com"))
    }

    @Test fun backoff() {
        assertEquals(listOf(1L, 2, 4, 8, 16, 30, 30), (0..6).map { Backoff.delayMs(it) / 1000 })
    }

    @Test fun snippetVars() {
        assertEquals(listOf("folder", "n"), SnippetVars.names("cd {{folder}} && ls {{ n }} {{folder}}"))
        assertEquals("cd D:\\YTUB && ls 3 D:\\YTUB", SnippetVars.fill("cd {{folder}} && ls {{ n }} {{folder}}", mapOf("folder" to "D:\\YTUB", "n" to "3")))
    }

    @Test fun shellPaths() {
        assertEquals("C:\\Users\\me\\HoldFast-uploads\\a.jpg", Uploader.shellPath("/C:/Users/me/HoldFast-uploads/a.jpg"))
        assertEquals("/home/u/HoldFast-uploads/a.jpg", Uploader.shellPath("/home/u/HoldFast-uploads/a.jpg"))
        assertEquals("'/home/u/my pic.jpg'", Uploader.shellPath("/home/u/my pic.jpg"))
        assertEquals("\"C:\\Users\\J Doe\\x.jpg\"", Uploader.shellPath("/C:/Users/J Doe/x.jpg"))
        assertEquals("a_b_.jpg", Uploader.safeName("a&b\$().jpg"))
        assertEquals("IMG_2024_01.jpg", Uploader.safeName("IMG 2024;01.jpg"))
        assertEquals("upload", Uploader.safeName("..."))
    }

    @Test fun generatedKeysLoadBack() {
        for ((t, prefix) in listOf("ed25519" to "ssh-ed25519 ", "ecdsa-256" to "ecdsa-sha2-nistp256 ", "rsa-4096" to "ssh-rsa ")) {
            val k = Keys.generate(t, "test key")
            assertTrue("$t: ${k.publicKey}", k.publicKey.startsWith(prefix))
            assertTrue(k.publicKey.endsWith(" test-key"))
            val again = Keys.fromPrivate(k.privateKey, null, "x")
            assertEquals(k.publicKey.split(" ")[1], again.publicKey.split(" ")[1])
        }
    }

    /** Real handshake with this PC's OpenSSH server (skipped where none is listening). */
    @Test fun handshakeWithLocalSshd() {
        val up = try { Socket("127.0.0.1", 22).use { true } } catch (_: Exception) { false }
        assumeTrue(up)
        var fp = ""
        SSHClient(DefaultConfig()).use { c ->
            c.addHostKeyVerifier(object : HostKeyVerifier {
                override fun verify(h: String, p: Int, key: PublicKey): Boolean { fp = Keys.fingerprint(key); return true }
                override fun findExistingAlgorithms(h: String, p: Int) = emptyList<String>()
            })
            c.connect("127.0.0.1", 22)
            assertTrue(c.isConnected)
        }
        assertTrue(fp, fp.startsWith("SHA256:"))
        println("local sshd host key $fp")
    }

    /** Runs the Windows authorized_keys script against a temp profile (never the real files). */
    @Test fun windowsInstallScriptInSandbox() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("windows"))
        val tmp = Files.createTempDirectory("hfkeys").toFile()
        val prof = File(tmp, "profile").apply { mkdirs() }
        val pd = File(tmp, "programdata").apply { mkdirs() }
        val key = Keys.generate("ed25519", "sandbox")
        val script = Remote.windowsInstallScript(key.publicKey)
        fun run(): String {
            val pb = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand",
                java.util.Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE)))
            pb.environment()["USERPROFILE"] = prof.absolutePath
            pb.environment()["ProgramData"] = pd.absolutePath
            pb.redirectErrorStream(true)
            val p = pb.start(); val out = p.inputStream.readBytes().toString(Charsets.UTF_8); p.waitFor(); return out
        }
        val out1 = run(); run() // twice: must not duplicate
        assertTrue(out1, out1.contains("DONE "))
        val userFile = File(prof, ".ssh/authorized_keys")
        assertTrue(userFile.exists())
        assertEquals(1, userFile.readText().lines().count { it.contains(key.publicKey.split(" ")[1]) })
        val adminFile = File(pd, "ssh/administrators_authorized_keys")
        if (adminFile.exists()) assertEquals(1, adminFile.readText().lines().count { it.contains(key.publicKey.split(" ")[1]) })
        tmp.deleteRecursively()
    }
}
