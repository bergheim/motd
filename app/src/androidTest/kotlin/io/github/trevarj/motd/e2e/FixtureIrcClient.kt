package io.github.trevarj.motd.e2e

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Small upstream client for deterministic fixture traffic; it never participates in app state. */
class FixtureIrcClient private constructor(
    private val socket: Socket,
    private val reader: BufferedReader,
    private val writer: BufferedWriter,
) : Closeable {
    companion object {
        private val sequence = AtomicInteger()

        fun connect(args: FixtureArgs): FixtureIrcClient {
            val socket = Socket().apply {
                connect(InetSocketAddress(args.host, args.ergoPort), 5_000)
                soTimeout = 500
            }
            return FixtureIrcClient(
                socket,
                BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8)),
                BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)),
            ).apply {
                val suffix = args.runId.filter(Char::isLetterOrDigit).takeLast(5)
                send("NICK j${suffix}${sequence.incrementAndGet()}")
                send("USER journey 0 * :motd headless journey")
                await("registration") { it.contains(" 001 ") }
                send("NICKSERV IDENTIFY ${args.ergoUser} ${args.ergoPassword}")
                send("JOIN ${args.channel}")
                await("channel join") { it.contains(" JOIN ${args.channel}") }
            }
        }
    }

    fun sendMessage(target: String, text: String) {
        require('\r' !in text && '\n' !in text)
        send("PRIVMSG $target :$text")
    }

    /** A PING barrier proves Ergo consumed every prior message before the app reconnects. */
    fun flushThroughServer(token: String) {
        send("PING :$token")
        // Ergo may serialize the final PONG parameter with or without a leading colon.
        await("PING barrier") {
            it.contains(" PONG ") && it.substringAfterLast(' ').removePrefix(":") == token
        }
    }

    override fun close() {
        runCatching { send("QUIT :journey complete") }
        runCatching { socket.close() }
    }

    private fun send(line: String) {
        writer.write(line)
        writer.write("\r\n")
        writer.flush()
    }

    private fun await(label: String, timeoutMs: Long = 15_000, predicate: (String) -> Boolean): String {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val tail = ArrayDeque<String>()
        while (System.nanoTime() < deadline) {
            val line = try {
                reader.readLine()
            } catch (_: SocketTimeoutException) {
                continue
            } ?: error("Ergo closed while waiting for $label")
            if (line.startsWith("PING ")) send("PONG ${line.substringAfter(' ')}")
            if (tail.size == 12) tail.removeFirst()
            tail.addLast(line)
            if (predicate(line)) return line
        }
        throw AssertionError("timed out waiting for $label; tail=${tail.joinToString(" | ")}")
    }
}
