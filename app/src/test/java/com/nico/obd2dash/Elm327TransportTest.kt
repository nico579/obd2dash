package com.nico.obd2dash

import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** TCP local uniquement : aucun test ne contacte une sonde ni un véhicule. */
class Elm327TransportTest {
    private class FakeElm(private val reply: (String, OutputStream) -> Unit) : AutoCloseable {
        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = listener.localPort
        val commands = CopyOnWriteArrayList<String>()
        private val failure = AtomicReference<Throwable?>()
        @Volatile private var accepted: Socket? = null
        private val worker = thread(name = "fake-elm-loopback", isDaemon = true) {
            try {
                listener.accept().use { socket ->
                    accepted = socket
                    val input = socket.getInputStream()
                    val output = socket.getOutputStream()
                    val command = StringBuilder()
                    while (true) {
                        val next = input.read()
                        if (next < 0) break
                        if (next.toChar() == '\r') {
                            val text = command.toString()
                            command.setLength(0)
                            commands.add(text)
                            reply(text, output)
                        } else command.append(next.toChar())
                    }
                }
            } catch (_: IOException) {
                // Le client ferme exprès le transport lors des cas timeout/annulation.
            } catch (e: Throwable) {
                failure.set(e)
            }
        }

        override fun close() {
            listener.close()
            accepted?.close()
            worker.join(6_000)
            check(!worker.isAlive) { "Le simulateur ne s'est pas arrêté" }
            failure.get()?.let { throw AssertionError("Échec du simulateur", it) }
        }
    }

    private fun standardReply(command: String, out: OutputStream) {
        out.reply(when (command) {
            "ATZ" -> "ELM327 v1.5"
            "ATDPN" -> "A5"
            else -> "OK"
        })
    }

    private fun OutputStream.reply(value: String) {
        write("$value\r>".toByteArray())
        flush()
    }

    private suspend fun withElm(
        timeouts: ElmTimeouts = ElmTimeouts(),
        reply: (String, OutputStream) -> Unit,
        test: suspend (Elm327Client, FakeElm) -> Unit
    ) {
        FakeElm(reply).use { server ->
            val client = Elm327Client(ConnectionTarget.Wifi("127.0.0.1", server.port), timeouts)
            try {
                client.connect()
                test(client, server)
            } finally {
                client.disconnect()
            }
        }
    }

    @Test
    fun `premiere reponse apres 4359 ms acceptee et bitmap nul distingue de NO DATA`() = runBlocking {
        withElm(reply = { command, out ->
            if (command == "0100") {
                // Délai observé dans la capture Trafic ; le défaut précédent coupait à 3 s.
                Thread.sleep(4_359)
                out.reply("SEARCHING...\r41 00 00 00 00 00")
            } else standardReply(command, out)
        }) { client, server ->
            val result = client.discoverPidSupport()
            assertEquals(emptySet<Int>(), result.supportedPids)
            assertTrue(result.vehicleResponseObserved)
            assertTrue(result.mode01BitmapReceived)
            assertTrue(result.isComplete)
            assertTrue(client.isConnected)
            assertFalse(client.isCanProtocol)
            assertEquals(listOf("0100", "ATDPN"), server.commands.takeLast(2))
        }
    }

    @Test
    fun `decouverte unit les bitmaps de plusieurs repondants dans les deux ordres`() = runBlocking {
        for (reverse in listOf(false, true)) {
            val replies = listOf("410000000000", "410000100001")
            withElm(reply = { command, out ->
                when (command) {
                    "0100" -> out.reply((if (reverse) replies.reversed() else replies).joinToString("\r"))
                    "0120" -> out.reply("412080000000")
                    else -> standardReply(command, out)
                }
            }) { client, server ->
                val result = client.discoverPidSupport()
                assertEquals(setOf(0x0C, 0x20, 0x21), result.supportedPids)
                assertTrue(result.isComplete)
                assertTrue(result.mode01BitmapReceived)
                assertTrue(result.vehicleResponseObserved)
                assertEquals(listOf("0100", "0120"), server.commands.filter { it.startsWith("01") })
                assertFalse(server.commands.contains("ATH1"))
            }
        }
    }

    @Test
    fun `bitmap valide voisin de reponse tronquee ou invalide reste incomplet`() = runBlocking {
        for (bad in listOf("410000", "4100000", "4100GG000000", "4100+1000000", "41000000000000", "7F01GG")) {
            for (reverse in listOf(false, true)) {
                val replies = listOf("410000100000", bad)
                withElm(reply = { command, out ->
                    if (command == "0100") out.reply((if (reverse) replies.reversed() else replies).joinToString("\r"))
                    else standardReply(command, out)
                }) { client, _ ->
                    val result = client.discoverPidSupport()
                    assertEquals(setOf(0x0C), result.supportedPids)
                    assertTrue(result.mode01BitmapReceived)
                    assertFalse(result.isComplete)
                }
            }
        }
    }

    // Sans en-tête, des réponses différentes de plusieurs calculateurs se complètent
    // (chacun rapporte SON voyant et SES codes, cas réel du 24/09 : P0087 voyant éteint).
    // Intégration 0.6 : elles sont réunies (voyant = OU, codes = union) au lieu d'être
    // déclarées illisibles. L'invariant d'origine tient : jamais « éteint, 0 défaut » ni
    // « aucun DTC » confirmé à tort ; seul un refus (7F) empêche de conclure à l'absence.

    @Test
    fun `MIL contradictoire ne devient jamais eteint avec zero defaut`() = runBlocking {
        for (reverse in listOf(false, true)) {
            val replies = listOf("410100000000", "410181076500")
            withElm(reply = { command, out ->
                if (command == "0101") out.reply((if (reverse) replies.reversed() else replies).joinToString("\r"))
                else standardReply(command, out)
            }) { client, _ ->
                // Voyant demandé par l'un des calculateurs, 1 code : allumé, quel que soit l'ordre.
                assertEquals(true to 1, client.readMilStatus())
                assertTrue(client.isConnected)
            }
        }
    }

    @Test
    fun `MIL eteint avec refus d'un autre calculateur reste illisible`() = runBlocking {
        withElm(reply = { command, out ->
            if (command == "0101") out.reply("410100000000\r7F0111")
            else standardReply(command, out)
        }) { client, _ ->
            try {
                client.readMilStatus()
                fail("Un refus laisse le voyant de ce calculateur inconnu")
            } catch (_: IOException) { }
            assertTrue(client.isConnected)
        }
    }

    @Test
    fun `DTC contradictoires ne deviennent jamais une liste vide confirmee`() = runBlocking {
        for (mode in listOf("03", "07")) {
            val prefix = if (mode == "03") "43" else "47"
            for (response in listOf(
                "${prefix}00\r${prefix}010087",
                "${prefix}010087\r${prefix}00",
                "${prefix}00\r004\r0:${prefix}010087"
            )) {
                withElm(reply = { command, out ->
                    if (command == mode) out.reply(response) else standardReply(command, out)
                }) { client, _ ->
                    val codes = if (mode == "03") client.readStoredDtcs() else client.readPendingDtcs()
                    assertEquals(listOf("P0087"), codes)
                    assertTrue(client.isConnected)
                }
            }
            withElm(reply = { command, out ->
                if (command == mode) out.reply("${prefix}00\r7F${mode}11") else standardReply(command, out)
            }) { client, _ ->
                try {
                    if (mode == "03") client.readStoredDtcs() else client.readPendingDtcs()
                    fail("Un refus ne doit pas laisser confirmer l'absence de DTC")
                } catch (_: IOException) { }
                assertTrue(client.isConnected)
            }
        }
    }

    @Test
    fun `mesure contradictoire illisible et reponse suivante unique exploitable`() = runBlocking {
        var reads = 0
        withElm(reply = { command, out ->
            if (command == "010C") out.reply(if (reads++ == 0) "410C1AF8\r410C2EE0" else "41 0C 2E E0")
            else standardReply(command, out)
        }) { client, _ ->
            assertNull(client.readPidBytes(0x0C))
            assertEquals(listOf(0x2E, 0xE0), client.readPidBytes(0x0C))
            assertTrue(client.isConnected)
        }
    }

    @Test
    fun `NO DATA refus et bitmap tronque conservent des etats distincts`() = runBlocking {
        for ((response, vehicleSeen) in listOf(
            "NO DATA" to false,
            "7F0112" to true,
            "0100\r7F0112" to true,
            "410000" to false
        )) {
            withElm(reply = { command, out ->
                if (command == "0100") out.reply(response) else standardReply(command, out)
            }) { client, _ ->
                val result = client.discoverPidSupport()
                assertEquals(response, vehicleSeen, result.vehicleResponseObserved)
                assertFalse(result.mode01BitmapReceived)
                assertFalse(result.isComplete)
                assertEquals(mapOf(0 to response), result.rawResponses)
            }
        }
    }

    @Test
    fun `refus temporaire initial ferme avant ATDPN meme apres echo residuel`() = runBlocking {
        for (code in listOf("21", "78")) {
            for (echo in listOf("", "0100\r")) {
                withElm(reply = { command, out ->
                    if (command == "0100") out.reply("${echo}7F01$code") else standardReply(command, out)
                }) { client, server ->
                    try {
                        client.discoverPidSupport()
                        fail("Le NRC $code ne doit pas confirmer une absence de mesures")
                    } catch (e: IOException) {
                        assertTrue(e.message.orEmpty().contains("0100"))
                        assertTrue(e.message.orEmpty().contains("NRC $code"))
                    }
                    assertFalse(client.isConnected)
                    assertFalse(server.commands.contains("ATDPN"))
                    assertEquals(listOf("0100"), server.commands.filter { it.startsWith("01") })
                }
            }
        }
    }

    @Test
    fun `refus temporaire banque suivante ne publie pas une decouverte reussie`() = runBlocking {
        for (code in listOf("21", "78")) {
            withElm(reply = { command, out ->
                when (command) {
                    "0100" -> out.reply("410080000001")
                    "0120" -> out.reply("7F01$code")
                    else -> standardReply(command, out)
                }
            }) { client, server ->
                try {
                    client.discoverPidSupport()
                    fail("Une banque temporairement indisponible ne doit pas terminer la connexion")
                } catch (e: IOException) {
                    assertTrue(e.message.orEmpty().contains("0120"))
                    assertTrue(e.message.orEmpty().contains("NRC $code"))
                }
                assertFalse(client.isConnected)
                assertFalse(server.commands.contains("ATDPN"))
                assertEquals(listOf("0100", "0120"), server.commands.filter { it.startsWith("01") })
            }
        }
    }

    @Test
    fun `bitmap valide reste utilisable mais partiel si un autre calculateur est occupe`() = runBlocking {
        withElm(reply = { command, out ->
            if (command == "0100") out.reply("7F0121\r410000000000") else standardReply(command, out)
        }) { client, _ ->
            val result = client.discoverPidSupport()
            assertTrue(result.vehicleResponseObserved)
            assertTrue(result.mode01BitmapReceived)
            assertFalse(result.isComplete)
            assertEquals(emptySet<Int>(), result.supportedPids)
            assertTrue(client.isConnected)
        }
    }

    @Test
    fun `reponse en attente ferme meme si un bitmap valide est present`() = runBlocking {
        for (response in listOf("7F0178\r410000000000", "410000000000\r7F0178")) {
            withElm(reply = { command, out ->
                if (command == "0100") out.reply(response) else standardReply(command, out)
            }) { client, server ->
                try {
                    client.discoverPidSupport()
                    fail("Le bitmap voisin ne garantit pas qu'aucune réponse tardive n'arrivera")
                } catch (e: IOException) {
                    assertTrue(e.message.orEmpty().contains("0100"))
                    assertTrue(e.message.orEmpty().contains("NRC 78"))
                }
                assertFalse(client.isConnected)
                assertFalse(server.commands.contains("ATDPN"))
                assertEquals(listOf("0100"), server.commands.filter { it.startsWith("01") })
            }
        }
    }

    @Test
    fun `MIL tronquee ne devient pas voyant eteint et une lecture complete suivante reste possible`() = runBlocking {
        val invalid = listOf("4101", "410100", "41010000", "4101000000", "41010000000000")
        val replies = (invalid + listOf("410100000000", "410181000000")).iterator()
        withElm(reply = { command, out ->
            if (command == "0101") out.reply(replies.next()) else standardReply(command, out)
        }) { client, _ ->
            for (raw in invalid) {
                try {
                    client.readMilStatus()
                    fail("Réponse PID01 incomplète ou trop longue acceptée : $raw")
                } catch (_: IOException) { }
                assertTrue(client.isConnected)
            }
            assertEquals(false to 0, client.readMilStatus())
            assertEquals(true to 1, client.readMilStatus())
        }
    }

    @Test
    fun `banque annoncee mais absente laisse decouverte partielle et PID deja recus`() = runBlocking {
        withElm(reply = { command, out ->
            when (command) {
                "0100" -> out.reply("410080000001")
                "0120" -> out.reply("NO DATA")
                else -> standardReply(command, out)
            }
        }) { client, _ ->
            val result = client.discoverPidSupport()
            assertEquals(setOf(1, 0x20), result.supportedPids)
            assertTrue(result.vehicleResponseObserved)
            assertTrue(result.mode01BitmapReceived)
            assertFalse(result.isComplete)
            assertEquals(setOf(0, 0x20), result.rawResponses.keys)
        }
    }

    @Test
    fun `banque E0 ne produit jamais commande 01100 hors protocole`() = runBlocking {
        withElm(reply = { command, out ->
            if (command.startsWith("01")) out.reply("41${command.takeLast(2)}00000001")
            else standardReply(command, out)
        }) { client, server ->
            val result = client.discoverPidSupport()
            assertFalse(result.isComplete)
            assertEquals(8, result.rawResponses.size)
            assertTrue(result.supportedPids.all { it <= 0xFF })
            assertFalse(server.commands.contains("01100"))
        }
    }

    @Test
    fun `echeance globale expire malgre octets reguliers et interdit commande suivante`() = runBlocking {
        withElm(ElmTimeouts(initialObdMs = 350), reply = { command, out ->
            if (command == "0100") {
                repeat(20) {
                    out.write('A'.code)
                    out.flush()
                    Thread.sleep(70)
                }
            } else standardReply(command, out)
        }) { client, server ->
            val start = System.nanoTime()
            try {
                client.sendRaw("0100")
                fail("Une réponse sans prompt doit expirer")
            } catch (_: SocketTimeoutException) { }
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            assertTrue("Échéance renouvelée par les octets : $elapsedMs ms", elapsedMs < 1_200)
            assertFalse(client.isConnected)
            try {
                client.sendRaw("010C")
                fail("Session désynchronisée réutilisée")
            } catch (_: IOException) { }
            assertFalse(server.commands.contains("010C"))
        }
    }

    @Test
    fun `lectures etablies utilisent echeance courte apres premier echange`() = runBlocking {
        withElm(ElmTimeouts(initialObdMs = 2_000, establishedReadMs = 150), reply = { command, out ->
            when (command) {
                "0100" -> out.reply("410000000000")
                "010C" -> { Thread.sleep(600); out.reply("410C1AF8") }
                else -> standardReply(command, out)
            }
        }) { client, _ ->
            client.sendRaw("0100")
            val start = System.nanoTime()
            try {
                client.sendRaw("010C")
                fail("La lecture établie doit expirer")
            } catch (_: SocketTimeoutException) { }
            assertTrue((System.nanoTime() - start) / 1_000_000 < 1_000)
            assertFalse(client.isConnected)
        }
    }

    @Test
    fun `annulation interrompt lecture bloquee et aucune commande en attente ne part`() = runBlocking {
        val received = CountDownLatch(1)
        withElm(reply = { command, out ->
            if (command == "0100") received.countDown() else standardReply(command, out)
        }) { client, server ->
            val reading = launch { client.sendRaw("0100") }
            assertTrue(withContext(Dispatchers.IO) { received.await(2, TimeUnit.SECONDS) })
            val waiting = launch { runCatching { client.sendRaw("010C") } }
            withTimeout(2_000) { reading.cancelAndJoin() }
            withTimeout(2_000) { waiting.join() }
            assertFalse(client.isConnected)
            assertFalse(server.commands.contains("010C"))
        }
    }

    @Test
    fun `disconnect interrompt immediatement lecture bloquee`() = runBlocking {
        val received = CountDownLatch(1)
        withElm(reply = { command, out ->
            if (command == "0100") received.countDown() else standardReply(command, out)
        }) { client, _ ->
            val reading = async { runCatching { client.sendRaw("0100") } }
            assertTrue(withContext(Dispatchers.IO) { received.await(2, TimeUnit.SECONDS) })
            client.disconnect()
            assertTrue(withTimeout(2_000) { reading.await() }.exceptionOrNull() is IOException)
            assertFalse(client.isConnected)
        }
    }

    @Test
    fun `EOF avant prompt est une erreur de transport et non une reponse partielle`() = runBlocking {
        withElm(reply = { command, out ->
            if (command == "0100") { out.write("410000000000".toByteArray()); out.close() }
            else standardReply(command, out)
        }) { client, _ ->
            try {
                client.sendRaw("0100")
                fail("EOF accepté comme réponse complète")
            } catch (_: IOException) { }
            assertFalse(client.isConnected)
        }
    }

    @Test
    fun `gardien annule apres succes ne ferme pas echange suivant`() = runBlocking {
        // L'objectif est d'attendre au-delà du gardien AT annulé, pas de contraindre
        // le démarrage du thread simulateur à 250 ms sur une machine occupée par Lint.
        withElm(ElmTimeouts(atMs = 2_000, initialObdMs = 10_000), reply = { command, out ->
            if (command == "0100") { Thread.sleep(2_500); out.reply("410000000000") }
            else standardReply(command, out)
        }) { client, _ ->
            assertEquals("410000000000", client.sendRaw("0100"))
            assertTrue(client.isConnected)
            // Les gardiens des AT précédents auraient déjà expiré s'ils étaient encore actifs.
        }
    }

    @Test
    fun `init refusee ferme connexion et arrete sequence`() = runBlocking {
        FakeElm { command, out ->
            if (command == "ATH0") out.reply("?") else standardReply(command, out)
        }.use { server ->
            val client = Elm327Client("127.0.0.1", server.port)
            try {
                client.connect()
                fail("Une configuration refusée ne doit pas être ignorée")
            } catch (_: IOException) { }
            finally { client.disconnect() }
            assertFalse(server.commands.contains("ATSP0"))
        }
    }

    @Test
    fun `echec transport ATDPN remonte avec phase et octets partiels`() = runBlocking {
        withElm(ElmTimeouts(atMs = 200), reply = { command, out ->
            when (command) {
                "0100" -> out.reply("410000000000")
                "ATDPN" -> { out.write("A".toByteArray()); out.flush() }
                else -> standardReply(command, out)
            }
        }) { client, _ ->
            try {
                client.discoverPidSupport()
                fail("ATDPN en échec ne doit pas laisser une découverte réussie")
            } catch (e: SocketTimeoutException) {
                assertTrue(e.message.orEmpty().contains("ATDPN (200 ms)"))
                assertTrue(e.message.orEmpty().contains("réponse partielle=A"))
            }
            assertFalse(client.isConnected)
        }
    }
}
