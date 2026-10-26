package app.daiyu.android.model.oauth

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/** Loopback only; WebView callback interception remains available if the port is occupied. */
internal class CodexCallbackServer(private val callback: (String) -> Unit) : AutoCloseable {
    private var sockets = emptyList<ServerSocket>()
    @Volatile private var closed = false
    private val clients = mutableSetOf<Socket>()
    @Synchronized internal fun activeClientCount(): Int = clients.size
    @Synchronized internal fun listenerCount(): Int = sockets.count { it.isBound && !it.isClosed }
    @Synchronized fun start() {
        check(!closed && sockets.isEmpty())
        sockets = listOf("127.0.0.1", "::1").mapNotNull { host ->
            val socket = ServerSocket()
            try { socket.bind(InetSocketAddress(InetAddress.getByName(host), 1455)); socket }
            catch (_: Exception) { socket.close(); null }
        }
        sockets.forEach { server -> Thread({
            while (!closed) {
                try {
                    val client = server.accept()
                    synchronized(this) { if (closed) { client.close(); return@Thread }; clients += client }
                    try { client.use(::handle) } finally { synchronized(this) { clients -= client } }
                } catch (_: Exception) { if (closed) return@Thread }
            }
        }, "daiyu-oauth-loopback").apply { isDaemon = true }.start() }
    }
    private fun handle(socket: Socket) {
        socket.soTimeout = 2000
        val input = socket.getInputStream()
        val bytes = java.io.ByteArrayOutputStream()
        while (bytes.size() < 8192) {
            val value = input.read(); if (value < 0 || value == 10) break; bytes.write(value)
        }
        val line = bytes.toString("US-ASCII").trimEnd('\r').split(' ')
        val path = line.getOrNull(1).orEmpty()
        val url = "http://localhost:1455$path"
        val valid = line.firstOrNull() == "GET" && CodexOAuthProtocol.isCallback(url)
        val body = if (valid) "Authorization received. Return to the app." else "Not found"
        socket.getOutputStream().write(("HTTP/1.1 " + (if (valid) "200 OK" else "404 Not Found") +
            "\r\nContent-Type: text/plain\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body").toByteArray())
        synchronized(this) { if (valid && !closed) callback(url) }
    }
    @Synchronized override fun close() {
        closed = true; sockets.forEach { runCatching { it.close() } }; sockets = emptyList()
        clients.toList().forEach { runCatching { it.close() } }
    }
}
