package com.nico.obd2dash

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
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

/** Client et transport TCP de production ; aucune radio, aucune adresse véhicule. */
class Elm327VehicleReplyTest {
    private class Server(private val respond: (String, OutputStream) -> Unit) : AutoCloseable {
        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = listener.localPort
        val commands = CopyOnWriteArrayList<String>()
        private val failure = AtomicReference<Throwable?>()
        @Volatile private var accepted: Socket? = null
        private val worker = thread(name = "vehicle-reply-loopback", isDaemon = true) {
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
                            respond(text, output)
                        } else command.append(byte.toChar())
                    }
                }
            } catch (_: IOException) {
                // Le test ferme volontairement le transport désynchronisé.
            } catch (error: Throwable) { failure.set(error) }
        }
        override fun close() {
            listener.close()
            accepted?.close()
            worker.join(5_000)
            check(!worker.isAlive) { "Serveur local encore actif" }
            failure.get()?.let { throw AssertionError("Échec du serveur local", it) }
        }
    }

    private fun OutputStream.reply(payload: String) {
        write((payload + "\r>").toByteArray(Charsets.US_ASCII))
        flush()
    }
    private fun initReply(command: String, output: OutputStream) {
        output.reply(when (command) {
            "ATZ" -> "ELM327 test"
            "ATDPN" -> "A6"
            else -> if (command.startsWith("AT")) "OK" else "NO DATA"
        })
    }
    private suspend fun withClient(
        respond: (String, OutputStream) -> Unit,
        block: suspend (Elm327Client, Server) -> Unit
    ) {
        Server(respond).use { server ->
            val client = Elm327Client(ConnectionTarget.Wifi("127.0.0.1", server.port),
                ElmTimeouts(connectMs = 5_000, atMs = 5_000, initialObdMs = 5_000, establishedReadMs = 5_000))
            try { client.connect(); block(client, server) }
            finally { client.disconnect() }
        }
    }

    private data class Query(val command: String, val read: suspend (Elm327Client) -> Any?)

    @Test fun `NRC21 et NRC78 ferment les lectures live groupees VIN UDS et brutes`() = runBlocking {
        val queries = listOf(
            Query("010C") { it.readPidBytes(0x0C) },
            Query("010C0D") { it.readPidsBytes(listOf(0x0C, 0x0D)) },
            Query("0902") { it.readVin() },
            Query("22F190") { it.readUdsDid(0xF190) },
            Query("010D") { it.sendRaw("010D") }
        )
        for (query in queries) for (nrc in listOf("21", "78")) {
            withClient({ command, output ->
                if (command == query.command) output.reply("$command\r7F${command.take(2)}$nrc")
                else initReply(command, output)
            }) { client, server ->
                val error = runCatching { query.read(client) }.exceptionOrNull()
                assertTrue("${query.command}, NRC$nrc : $error", error is IOException)
                assertTrue(error!!.message.orEmpty().contains("NRC $nrc"))
                assertFalse(client.isConnected)
                assertTrue(runCatching { client.sendRaw("0105") }.exceptionOrNull() is IOException)
                assertEquals(listOf(query.command), server.commands.filter { !it.startsWith("AT") })
            }
        }
    }

    @Test fun `NRC78 voisin de valeur positive ferme aussi et un refus definitif laisse le fil ouvert`() = runBlocking {
        for (nrc in listOf("21", "78", "12")) {
            withClient({ command, output ->
                if (command == "010C") output.reply("410C1AF8\r7F01$nrc")
                else initReply(command, output)
            }) { client, _ ->
                val result = runCatching { client.readPidBytes(0x0C) }
                if (nrc == "12") {
                    assertTrue(result.isSuccess)
                    assertNull(result.getOrNull())
                    assertTrue(client.isConnected)
                } else {
                    assertTrue(result.exceptionOrNull() is IOException)
                    assertFalse(client.isConnected)
                }
            }
        }
    }

    @Test fun `un NRC temporaire non correle ne ferme pas la mesure demandee`() = runBlocking {
        withClient({ command, output ->
            if (command == "010C") output.reply("7F0978\r410C1AF8") else initReply(command, output)
        }) { client, _ ->
            assertEquals(listOf(0x1A, 0xF8), client.readPidBytes(0x0C))
            assertTrue(client.isConnected)
        }
    }

    @Test fun `NRC78 ferme sous mutex avant une commande deja en attente et sa reponse tardive`() = runBlocking {
        val requested = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            withClient({ command, output ->
                if (command == "010C") {
                    requested.countDown()
                    check(release.await(5, TimeUnit.SECONDS)) { "Réponse retenue trop longtemps" }
                    output.reply("7F0178")
                } else if (command == "010D") {
                    // Avant correction, cette réponse RPM tardive polluait la vitesse.
                    output.reply("410C1AF8")
                    output.reply("410D28")
                } else initReply(command, output)
            }) { client, server ->
                val reading = async { runCatching { client.readPidBytes(0x0C) } }
                assertTrue(withContext(Dispatchers.IO) { requested.await(5, TimeUnit.SECONDS) })
                val queued = async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { client.readPidBytes(0x0D) }
                }
                release.countDown()
                assertTrue(withTimeout(5_000) { reading.await() }.exceptionOrNull() is IOException)
                assertTrue(withTimeout(5_000) { queued.await() }.exceptionOrNull() is IOException)
                assertFalse(client.isConnected)
                assertFalse(server.commands.contains("010D"))
            }
        } finally { release.countDown() }
    }

    @Test fun `la limite de reponse reste inactive et les conflits ne dependent pas de lordre ECU`() = runBlocking {
        for (payloads in listOf("410C1AF8\r410C2EE0", "410C2EE0\r410C1AF8")) {
            withClient({ command, output ->
                when (command) {
                    "01001" -> output.reply("410000100000") // syntaxe admise par ce simulateur
                    "010C" -> output.reply(payloads)
                    "010C1" -> output.reply(payloads.substringBefore('\r'))
                    else -> initReply(command, output)
                }
            }) { client, server ->
                assertNull(client.readPidBytes(0x0C))
                client.detectResponseCountSupport()
                assertFalse(client.supportsResponseCount)
                assertNull(client.readPidBytes(0x0C))
                assertFalse(server.commands.contains("01001"))
                assertFalse(server.commands.contains("010C1"))
                assertEquals(listOf("010C", "010C"), server.commands.filter { !it.startsWith("AT") })
            }
        }
    }

    @Test fun `la suppression du suffixe ne desactive pas le groupage coherent`() = runBlocking {
        withClient({ command, output ->
            if (command == "010C0D") output.reply("410C1AF80D28") else initReply(command, output)
        }) { client, server ->
            client.detectResponseCountSupport()
            client.detectMultiPidSupport(listOf(0x0C, 0x0D))
            assertFalse(client.supportsResponseCount)
            assertTrue(client.supportsMultiPid)
            assertEquals(mapOf(0x0C to listOf(0x1A, 0xF8), 0x0D to listOf(0x28)),
                client.readPidsBytes(listOf(0x0C, 0x0D)))
            assertEquals(listOf("010C0D", "010C0D"), server.commands.filter { !it.startsWith("AT") })
        }
    }

    @Test fun `la capture headers ferme NRC21 et NRC78 CAN11 et CAN29 sans envoyer ATH0 apres ni masquer le NRC`() = runBlocking {
        for ((protocol, id) in listOf("A6" to "7E8", "A7" to "18DAF110")) {
            for (nrc in listOf("21", "78")) for (positiveNeighbor in listOf(false, true)) {
                var headers = false
                withClient({ command, output ->
                    when (command) {
                        "ATDPN" -> output.reply(protocol)
                        "ATH1" -> { headers = true; output.reply("OK") }
                        "ATH0" -> { headers = false; output.reply("OK") }
                        "0100" -> if (headers) {
                            val negative = "$id\t03 7F 01 $nrc"
                            val neighbor = if (positiveNeighbor) "${id}06410000100000\r" else ""
                            output.reply(neighbor + negative)
                        } else output.reply("410000100000")
                        else -> initReply(command, output)
                    }
                }) { client, server ->
                    client.detectProtocol()
                    val before = server.commands.size
                    val error = runCatching { client.probeHeaderFormat() }.exceptionOrNull()
                    assertTrue("$protocol, NRC$nrc : $error", error is IOException)
                    assertTrue(error!!.message.orEmpty().contains("NRC $nrc"))
                    assertFalse(client.isConnected)
                    assertEquals(listOf("0100", "ATH1", "0100"), server.commands.drop(before))
                }
            }
        }
    }

    @Test fun `une capture positive CAN11 et CAN29 restaure les headers sur la liaison vivante`() = runBlocking {
        for ((protocol, id) in listOf("A8" to "7E8", "A9" to "18DAF110")) {
            var headers = false
            withClient({ command, output ->
                when (command) {
                    "ATDPN" -> output.reply(protocol)
                    "ATH1" -> { headers = true; output.reply("OK") }
                    "ATH0" -> { headers = false; output.reply("OK") }
                    "0100" -> output.reply(if (headers) "${id}06410000100000" else "410000100000")
                    "010C" -> output.reply("410C1AF8")
                    else -> initReply(command, output)
                }
            }) { client, server ->
                client.detectProtocol()
                val before = server.commands.size
                val capture = client.probeHeaderFormat()
                assertTrue(capture.contains("${id}06410000100000"))
                assertTrue(client.isConnected)
                assertEquals(listOf("0100", "ATH1", "0100", "ATH0"), server.commands.drop(before))
                assertEquals(listOf(0x1A, 0xF8), client.readPidBytes(0x0C))
            }
        }
    }

    @Test fun `le mapping de capture exige le numero complet dun protocole CAN documente`() {
        for (protocol in listOf("6", "A6", "8", "A8")) {
            assertEquals(CanIdFormat.CAN_11, headerCaptureFormatForProtocol(protocol))
        }
        for (protocol in listOf("7", "A7", "9", "A9")) {
            assertEquals(CanIdFormat.CAN_29, headerCaptureFormatForProtocol(protocol))
        }
        for (protocol in listOf(null, "", "?", "A0", "1", "A5", "AA", "6ERROR", "A6ERROR", "A6\rA7")) {
            assertNull(protocol, headerCaptureFormatForProtocol(protocol))
        }
    }

    @Test fun `un protocole inconnu ou nonCAN refuse la capture avant ATH1 sans toucher les lectures standard`() = runBlocking {
        for (protocol in listOf(null, "A5", "A0", "?", "6ERROR", "A6ERROR", "A6\rA7")) {
            withClient({ command, output ->
                when (command) {
                    "ATDPN" -> output.reply(protocol ?: "?")
                    "010C" -> output.reply("410C1AF8")
                    else -> initReply(command, output)
                }
            }) { client, server ->
                if (protocol != null) client.detectProtocol()
                val before = server.commands.size
                val error = runCatching { client.probeHeaderFormat() }.exceptionOrNull()
                assertTrue(error is IOException)
                assertTrue(error!!.message.orEmpty().contains("format CAN non établi"))
                assertTrue(client.isConnected)
                assertTrue(server.commands.drop(before).isEmpty())
                assertEquals(listOf(0x1A, 0xF8), client.readPidBytes(0x0C))
            }
        }
    }

    @Test fun `annuler une capture headers bloquee propage annulation et ne restaure pas un transport ferme`() = runBlocking {
        val headersRequested = CountDownLatch(1)
        var headers = false
        withClient({ command, output ->
            when (command) {
                "ATH1" -> { headers = true; output.reply("OK") }
                "ATH0" -> { headers = false; output.reply("OK") }
                "0100" -> if (headers) headersRequested.countDown() else output.reply("410000100000")
                else -> initReply(command, output)
            }
        }) { client, server ->
            client.detectProtocol()
            val before = server.commands.size
            val capture = async { client.probeHeaderFormat() }
            assertTrue(withContext(Dispatchers.IO) { headersRequested.await(5, TimeUnit.SECONDS) })
            withTimeout(5_000) { capture.cancelAndJoin() }
            assertTrue(capture.isCancelled)
            assertFalse(client.isConnected)
            assertEquals(listOf("0100", "ATH1", "0100"), server.commands.drop(before))
        }
    }
}
