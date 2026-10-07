package com.nico.obd2dash

import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Audit observations, not acceptance tests: baseline unsafe behavior is reported.
 * Real, unmodified Elm327Client and TCP transport; server bound to 127.0.0.1 only.
 * Compile with the existing offline_viewmodel production sources/Android doubles.
 * No Android Bluetooth, Wi-Fi network request, hardware address or vehicle access.
 */
internal object TransportAuditProbe {
    private class Server(private val reply: (String, OutputStream) -> Unit) : AutoCloseable {
        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = listener.localPort
        val commands = CopyOnWriteArrayList<String>()
        private val unexpectedFailure = AtomicReference<Throwable?>()
        @Volatile private var accepted: Socket? = null
        private val worker = thread(name = "transport-audit-loopback", isDaemon = true) {
            try {
                listener.accept().use { socket ->
                    check(socket.inetAddress.isLoopbackAddress)
                    accepted = socket
                    val input = socket.getInputStream()
                    val output = socket.getOutputStream()
                    val command = StringBuilder()
                    while (true) {
                        val byte = input.read()
                        if (byte < 0) break
                        if (byte == 13) {
                            val text = command.toString()
                            command.setLength(0)
                            commands.add(text)
                            reply(text, output)
                        } else command.append(byte.toChar())
                    }
                }
            } catch (_: IOException) {
                // Explicit client closure is also the desired safe NRC78 outcome.
            } catch (error: Throwable) {
                unexpectedFailure.set(error)
            }
        }

        override fun close() {
            listener.close()
            accepted?.close()
            worker.join(5_000)
            check(!worker.isAlive) { "Loopback server did not stop" }
            unexpectedFailure.get()?.let { throw AssertionError("Loopback server failed", it) }
        }
    }

    private fun OutputStream.reply(payload: String) {
        write((payload + "\r>").toByteArray(Charsets.US_ASCII))
        flush()
    }

    private fun initReply(command: String, output: OutputStream) {
        output.reply(when (command) {
            "ATZ" -> "ELM327 audit loopback"
            "ATDPN" -> "A6"
            else -> if (command.startsWith("AT")) "OK" else "NO DATA"
        })
    }

    private fun client(server: Server) = Elm327Client(
        ConnectionTarget.Wifi("127.0.0.1", server.port),
        ElmTimeouts(connectMs = 5_000, atMs = 5_000, initialObdMs = 5_000, establishedReadMs = 5_000)
    )

    /**
     * The late RPM reply is released only after the next speed request is received.
     * This causal ordering avoids fragile sleep-based timing. Each result terminates
     * with an ELM prompt, modeling the same pending/prompt condition already covered
     * by diagnostic NRC78 tests. A physical adapter producing it is not claimed.
     */
    private suspend fun ordinaryPending(): Map<String, String> {
        var speedRequests = 0
        val secondSpeedSeen = CountDownLatch(1)
        Server { command, output ->
            when (command) {
                "010C" -> output.reply("7F0178")
                "010D" -> {
                    speedRequests++
                    if (speedRequests == 1) {
                        output.reply("410C1AF8") // late positive for the previous RPM request
                        output.reply("410D28")   // first speed request: 40 km/h
                    } else {
                        secondSpeedSeen.countDown()
                        output.reply("410D50")   // second speed request: 80 km/h
                    }
                }
                else -> initReply(command, output)
            }
        }.use { server ->
            val client = client(server)
            try {
                client.connect()
                val rpm = runCatching { client.readPidBytes(0x0C) }
                val connectedAfterPending = client.isConnected
                var firstSpeed: Result<List<Int>?>? = null
                var secondSpeed: Result<List<Int>?>? = null
                if (connectedAfterPending) {
                    firstSpeed = runCatching { client.readPidBytes(0x0D) }
                    if (client.isConnected) {
                        secondSpeed = runCatching { client.readPidBytes(0x0D) }
                        check(secondSpeedSeen.await(5, TimeUnit.SECONDS)) { "Second speed request was not received" }
                    }
                }
                return linkedMapOf(
                    "method" to "real Elm327Client; deterministic delayed-reply TCP loopback simulation",
                    "rpm_result" to outcome(rpm),
                    "connected_after_nrc78" to connectedAfterPending.toString(),
                    "first_speed_result" to outcome(firstSpeed),
                    "second_speed_result" to outcome(secondSpeed),
                    "simulated_current_second_speed" to "[80]",
                    "previous_speed_accepted_for_second_request" to (secondSpeed?.getOrNull() == listOf(40)).toString(),
                    "commands" to server.commands.toList().joinToString(","),
                    "physical_adapter_behavior_verified" to "false"
                )
            } finally { client.disconnect() }
        }
    }

    /**
     * Server contract explicitly models an adapter honoring the response limit.
     * Full functional replies contain two contradictory ECU payloads. Limited
     * requests deliver the first one only. This proves the client-policy gap under
     * that contract, not the response-count behavior of l'utilisateur's physical adapters.
     */
    private suspend fun responseLimit(firstValue: String, secondValue: String): Map<String, String> {
        Server { command, output ->
            output.reply(when (command) {
                "ATZ" -> "ELM327 audit loopback"
                "01001" -> "410000100000"
                "010C" -> "$firstValue\r$secondValue"
                "010C1" -> firstValue
                else -> if (command.startsWith("AT")) "OK" else "NO DATA"
            })
        }.use { server ->
            val client = client(server)
            try {
                client.connect()
                val before = client.readPidBytes(0x0C)
                client.detectResponseCountSupport()
                val after = client.readPidBytes(0x0C)
                return linkedMapOf(
                    "method" to "real Elm327Client; simulated adapter honors first-response limit",
                    "first_ecu_payload" to firstValue,
                    "second_ecu_payload" to secondValue,
                    "full_response_result" to before.toString(),
                    "response_count_supported" to client.supportsResponseCount.toString(),
                    "limited_response_result" to after.toString(),
                    "conflict_hidden_by_limit" to (before == null && after != null).toString(),
                    "commands" to server.commands.toList().joinToString(","),
                    "physical_adapter_behavior_verified" to "false"
                )
            } finally { client.disconnect() }
        }
    }

    private fun outcome(result: Result<List<Int>?>?): String = when {
        result == null -> "not_sent"
        result.isSuccess -> result.getOrNull().toString()
        else -> result.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }.orEmpty()
    }

    suspend fun run(): Map<String, Map<String, String>> = linkedMapOf(
        "ordinary_pid_nrc78_late_reply" to ordinaryPending(),
        "response_limit_ecu_order_a" to responseLimit("410C1AF8", "410C2EE0"),
        "response_limit_ecu_order_b" to responseLimit("410C2EE0", "410C1AF8")
    )

    private fun quote(text: String): String = "\"" + buildString {
        for (char in text) append(when (char) {
            '\\' -> "\\\\"
            '"' -> "\\\""
            '\n' -> "\\n"
            '\r' -> "\\r"
            '\t' -> "\\t"
            else -> if (char.code < 32) "\\u%04x".format(char.code) else char.toString()
        })
    } + "\""

    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val results = run()
        println(results.entries.joinToString(prefix = "{", postfix = "}") { (name, facts) ->
            quote(name) + ":" + facts.entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
                quote(key) + ":" + quote(value)
            }
        })
    }
}
