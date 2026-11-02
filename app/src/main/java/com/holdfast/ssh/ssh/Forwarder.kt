package com.holdfast.ssh.ssh

import com.holdfast.ssh.data.PortForward
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder
import net.schmizz.sshj.connection.channel.forwarded.SocketForwardingConnectListener
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** Runs one port-forward rule over an established connection. */
class Forwarder(private val client: SSHClient, val rule: PortForward) {
    @Volatile var status: String = "Starting"
        private set
    private var server: ServerSocket? = null
    private var remote: RemotePortForwarder.Forward? = null

    fun start() {
        try {
            when (rule.type) {
                "L" -> {
                    val ss = bind()
                    thread(isDaemon = true, name = "hf-L${rule.bindPort}") {
                        try {
                            client.newLocalPortForwarder(
                                Parameters(rule.bindHost, rule.bindPort, rule.destHost, rule.destPort), ss
                            ).listen()
                        } catch (e: Exception) { if (server != null) status = "Stopped: ${e.message}" }
                    }
                }
                "R" -> {
                    remote = client.remotePortForwarder.bind(
                        RemotePortForwarder.Forward(rule.bindPort),
                        SocketForwardingConnectListener(InetSocketAddress(rule.destHost, rule.destPort)),
                    )
                }
                "D" -> {
                    val ss = bind()
                    thread(isDaemon = true, name = "hf-D${rule.bindPort}") { socksLoop(ss) }
                }
            }
            status = "Active"
        } catch (e: Exception) {
            status = "Failed: ${e.message}"
        }
    }

    fun stop() {
        server?.let { s -> server = null; try { s.close() } catch (_: Exception) {} }
        remote?.let { f -> remote = null; try { client.remotePortForwarder.cancel(f) } catch (_: Exception) {} }
        status = "Stopped"
    }

    private fun bind(): ServerSocket = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(InetAddress.getByName(rule.bindHost), rule.bindPort))
        server = this
    }

    // Minimal SOCKS5 (no auth, CONNECT only) tunnelled through direct-tcpip channels.
    private fun socksLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val sock = try { ss.accept() } catch (e: Exception) { break }
            thread(isDaemon = true) { try { socks(sock) } catch (_: Exception) { try { sock.close() } catch (_: Exception) {} } }
        }
    }

    private fun socks(sock: Socket) {
        val inp = DataInputStream(sock.getInputStream())
        val out = sock.getOutputStream()
        if (inp.readUnsignedByte() != 5) { sock.close(); return }
        val n = inp.readUnsignedByte(); repeat(n) { inp.readUnsignedByte() }
        out.write(byteArrayOf(5, 0)); out.flush()
        if (inp.readUnsignedByte() != 5) { sock.close(); return }
        val cmd = inp.readUnsignedByte(); inp.readUnsignedByte()
        val host = when (inp.readUnsignedByte()) {
            1 -> ByteArray(4).also { inp.readFully(it) }.let { InetAddress.getByAddress(it).hostAddress!! }
            3 -> ByteArray(inp.readUnsignedByte()).also { inp.readFully(it) }.let { String(it) }
            4 -> ByteArray(16).also { inp.readFully(it) }.let { InetAddress.getByAddress(it).hostAddress!! }
            else -> { sock.close(); return }
        }
        val port = inp.readUnsignedShort()
        if (cmd != 1) { out.write(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0)); sock.close(); return }
        val ch = try { client.newDirectConnection(host, port) } catch (e: Exception) {
            out.write(byteArrayOf(5, 5, 0, 1, 0, 0, 0, 0, 0, 0)); sock.close(); return
        }
        out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)); out.flush()
        val a = thread(isDaemon = true) { pipe(sock.getInputStream(), ch.outputStream) }
        pipe(ch.inputStream, out)
        try { ch.close() } catch (_: Exception) {}
        try { sock.close() } catch (_: Exception) {}
        a.interrupt()
    }

    private fun pipe(i: InputStream, o: OutputStream) {
        val buf = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = i.read(buf); if (n < 0) break
                o.write(buf, 0, n); o.flush()
            }
        } catch (_: Exception) {
        } finally {
            try { o.close() } catch (_: Exception) {}
        }
    }
}
