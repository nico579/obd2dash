package com.nico.obd2dash

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class BleSerialTransportTest {
    private class Driver : BleGattDriver {
        lateinit var events: BleGattDriver.Events
        val opened = CountDownLatch(1)
        val subscribed = CountDownLatch(1)
        val written = CountDownLatch(1)
        val packets = mutableListOf<ByteArray>()
        val sent = LinkedBlockingQueue<ByteArray>()
        var autoConnect = true
        var autoSubscribe = true
        var autoWrite = true
        var acceptSubscription = true
        var acceptWrite = true
        var closes = 0
        override fun connect(events: BleGattDriver.Events) {
            this.events = events
            opened.countDown()
            if (autoConnect) events.connected()
        }
        override fun discoverServices(): Boolean { events.servicesDiscovered(); return true }
        override fun enableNotifications(): Boolean {
            subscribed.countDown()
            if (acceptSubscription && autoSubscribe) events.notificationsEnabled()
            return acceptSubscription
        }
        override fun write(value: ByteArray): Boolean {
            packets.add(value.copyOf())
            sent.add(value.copyOf())
            written.countDown()
            if (acceptWrite && autoWrite) events.writeCompleted()
            return acceptWrite
        }
        override fun close() { closes++ }
    }

    private fun <T> async(block: () -> T): FutureTask<T> = FutureTask(block).also {
        Thread(it, "fake-gatt-test").apply { isDaemon = true }.start()
    }
    private fun assertIoFailure(task: FutureTask<*>) {
        try { task.get(2, TimeUnit.SECONDS); fail("Une erreur de transport était attendue") }
        catch (e: ExecutionException) { assertTrue(e.cause is IOException) }
    }

    @Test fun `attend abonnement avant tout envoi et accepte ack synchrone`() {
        val driver = Driver().apply { autoSubscribe = false }
        BleSerialTransport(driver).use { transport ->
            val connecting = async { transport.connect() }
            assertTrue(driver.subscribed.await(2, TimeUnit.SECONDS))
            assertFalse(transport.isConnected)
            assertFalse(connecting.isDone)
            try { transport.output.write("ATZ\r".toByteArray()); fail("Envoi avant abonnement") }
            catch (_: IOException) { }
            assertTrue(driver.packets.isEmpty())
            driver.events.notificationsEnabled()
            connecting.get(2, TimeUnit.SECONDS)
            transport.output.write("ATZ\r".toByteArray())
            assertEquals("ATZ\r", driver.packets.single().decodeToString())
        }
    }

    @Test fun `assemble notifications fragmentees et copie les valeurs du callback`() {
        val driver = Driver()
        BleSerialTransport(driver).use { transport ->
            transport.connect()
            val fragment = "41".toByteArray()
            driver.events.bytesReceived(fragment)
            fragment.fill(0)
            driver.events.bytesReceived("0C1A".toByteArray())
            driver.events.bytesReceived("F8\r>".toByteArray())
            val bytes = ByteArray(10)
            assertEquals(10, transport.input.read(bytes))
            assertEquals("410C1AF8\r>", bytes.decodeToString())
        }
    }

    @Test fun `ecriture decoupee respecte MTU minimal et attend chaque callback`() {
        val driver = Driver().apply { autoWrite = false }
        BleSerialTransport(driver).use { transport ->
            transport.connect()
            val value = ByteArray(21) { (it + 1).toByte() }
            val writing = async { transport.output.write(value) }
            assertEquals(20, driver.sent.poll(2, TimeUnit.SECONDS)?.size)
            assertFalse(writing.isDone)
            assertEquals(20, driver.packets.single().size)
            driver.events.writeCompleted()
            assertEquals(1, driver.sent.poll(2, TimeUnit.SECONDS)?.size)
            assertFalse(writing.isDone)
            driver.events.writeCompleted()
            writing.get(2, TimeUnit.SECONDS)
            assertEquals(listOf(20, 1), driver.packets.map { it.size })
            assertArrayEquals(value, driver.packets.fold(byteArrayOf()) { all, packet -> all + packet })
        }
    }

    @Test fun `fermeture reveille une connexion sans callback`() {
        val driver = Driver().apply { autoConnect = false }
        val transport = BleSerialTransport(driver)
        val connecting = async { transport.connect() }
        assertTrue(driver.opened.await(2, TimeUnit.SECONDS))
        transport.close()
        assertIoFailure(connecting)
        driver.events.connected()
        driver.events.notificationsEnabled()
        assertFalse(transport.isConnected)
        transport.close()
        assertEquals(1, driver.closes)
    }

    @Test fun `fermeture reveille lecture et jette les octets de la session perdue`() {
        val driver = Driver()
        val transport = BleSerialTransport(driver)
        transport.connect()
        val reading = async { transport.input.read() }
        transport.close()
        assertIoFailure(reading)
        driver.events.bytesReceived("OK>".toByteArray())
        try { transport.input.read(); fail("Octets tardifs acceptés") } catch (_: IOException) { }
    }

    @Test fun `deconnexion reveille ecriture incertaine sans la reemettre`() {
        val driver = Driver().apply { autoWrite = false }
        BleSerialTransport(driver).use { transport ->
            transport.connect()
            val writing = async { transport.output.write("0100\r".toByteArray()) }
            assertTrue(driver.written.await(2, TimeUnit.SECONDS))
            driver.events.failed(IOException("GATT 133"))
            assertIoFailure(writing)
            assertEquals(1, driver.packets.size)
            assertFalse(transport.isConnected)
        }
    }

    @Test fun `refus abonnement ferme avant commande ELM`() {
        val driver = Driver().apply { acceptSubscription = false }
        BleSerialTransport(driver).use { transport ->
            try { transport.connect(); fail("Refus ignoré") } catch (_: IOException) { }
            assertFalse(transport.isConnected)
            assertTrue(driver.packets.isEmpty())
            assertEquals(1, driver.closes)
        }
    }

    @Test fun `refus ecriture ferme et ne retente pas`() {
        val driver = Driver().apply { acceptWrite = false }
        BleSerialTransport(driver).use { transport ->
            transport.connect()
            try { transport.output.write("03\r".toByteArray()); fail("Refus ignoré") } catch (_: IOException) { }
            assertFalse(transport.isConnected)
            assertEquals(1, driver.packets.size)
        }
    }

    @Test fun `debordement reception ferme au lieu de supprimer une partie de reponse`() {
        val driver = Driver()
        BleSerialTransport(driver, maxBufferedBytes = 4).use { transport ->
            transport.connect()
            driver.events.bytesReceived("1234".toByteArray())
            driver.events.bytesReceived(">".toByteArray())
            assertFalse(transport.isConnected)
            try { transport.input.read(); fail("Réponse tronquée publiée") } catch (_: IOException) { }
        }
    }

    @Test fun `auto preserve classique et preference explicite pour les appareils doubles`() {
        assertEquals(BluetoothTransport.BLE, resolveBluetoothTransport(BluetoothTransport.AUTO, 2))
        for (type in listOf(0, 1, 3)) {
            assertEquals(BluetoothTransport.CLASSIC, resolveBluetoothTransport(BluetoothTransport.AUTO, type))
        }
        assertEquals(BluetoothTransport.BLE, resolveBluetoothTransport(BluetoothTransport.BLE, 3))
        assertEquals(BluetoothTransport.CLASSIC, resolveBluetoothTransport(BluetoothTransport.CLASSIC, 2))
    }
}
