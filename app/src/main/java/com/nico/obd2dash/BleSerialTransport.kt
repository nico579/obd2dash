package com.nico.obd2dash

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.ArrayDeque

enum class BluetoothTransport { AUTO, CLASSIC, BLE }

/** Android TYPE_LE = 2. Les appareils classiques/doubles gardent SPP en mode auto. */
internal fun resolveBluetoothTransport(preference: BluetoothTransport, deviceType: Int): BluetoothTransport =
    if (preference != BluetoothTransport.AUTO) preference
    else if (deviceType == 2) BluetoothTransport.BLE else BluetoothTransport.CLASSIC

/** Adaptation Android séparée pour vérifier le cycle GATT sans radio ni véhicule. */
internal interface BleGattDriver {
    interface Events {
        fun connected()
        fun servicesDiscovered()
        fun notificationsEnabled()
        fun bytesReceived(value: ByteArray)
        fun writeCompleted()
        fun failed(error: IOException)
    }
    fun connect(events: Events)
    fun discoverServices(): Boolean
    fun enableNotifications(): Boolean
    fun write(value: ByteArray): Boolean
    fun close()
}

/**
 * Flux ELM sur GATT. L'échéance et l'annulation sont celles d'Elm327Client : close()
 * réveille connexion, écriture et lecture bloquantes. Aucun envoi avant l'accusé CCCD,
 * aucune réémission d'une écriture incertaine. MTU minimal : paquets de 20 octets.
 */
internal class BleSerialTransport(
    private val driver: BleGattDriver,
    private val maxBufferedBytes: Int = 65_536
) : Closeable {
    private enum class Stage { NEW, CONNECTING, DISCOVERING, SUBSCRIBING, READY, CLOSED }
    private val lock = java.lang.Object()
    private val writeLock = Any()
    private var stage = Stage.NEW
    private var failure: IOException? = null
    private val chunks = ArrayDeque<ByteArray>()
    private var headOffset = 0
    private var bufferedBytes = 0
    private var writing = false

    val isConnected: Boolean get() = synchronized(lock) { stage == Stage.READY }

    private val events = object : BleGattDriver.Events {
        override fun connected() {
            if (advance(Stage.CONNECTING, Stage.DISCOVERING)) operation("Découverte BLE refusée") {
                driver.discoverServices()
            }
        }
        override fun servicesDiscovered() {
            if (advance(Stage.DISCOVERING, Stage.SUBSCRIBING)) operation("Notifications BLE refusées") {
                driver.enableNotifications()
            }
        }
        override fun notificationsEnabled() {
            advance(Stage.SUBSCRIBING, Stage.READY)
        }
        override fun bytesReceived(value: ByteArray) {
            var overflow = false
            synchronized(lock) {
                if (stage != Stage.READY || value.isEmpty()) return
                if (value.size > maxBufferedBytes - bufferedBytes) {
                    overflow = true
                } else {
                    chunks.addLast(value.copyOf())
                    bufferedBytes += value.size
                    lock.notifyAll()
                }
            }
            if (overflow) fail(IOException("Réception BLE trop volumineuse"))
        }
        override fun writeCompleted() {
            synchronized(lock) {
                if (stage == Stage.READY && writing) {
                    writing = false
                    lock.notifyAll()
                }
            }
        }
        override fun failed(error: IOException) = fail(error)
    }

    fun connect() {
        synchronized(lock) {
            check(stage == Stage.NEW) { "Transport BLE déjà utilisé" }
            stage = Stage.CONNECTING
        }
        operation("Ouverture BLE refusée") { driver.connect(events); true }
        synchronized(lock) {
            while (stage != Stage.READY) {
                checkOpen()
                lock.wait()
            }
        }
    }

    val input: InputStream = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            read(one, 0, 1)
            return one[0].toInt() and 0xFF
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
            if (length == 0) return 0
            synchronized(lock) {
                while (bufferedBytes == 0) {
                    checkOpen()
                    lock.wait()
                }
                checkOpen()
                var copied = 0
                while (copied < length && chunks.isNotEmpty()) {
                    val head = chunks.first
                    val count = minOf(length - copied, head.size - headOffset)
                    head.copyInto(bytes, offset + copied, headOffset, headOffset + count)
                    copied += count
                    headOffset += count
                    bufferedBytes -= count
                    if (headOffset == head.size) { chunks.removeFirst(); headOffset = 0 }
                }
                return copied
            }
        }
        override fun close() = this@BleSerialTransport.close()
    }

    val output: OutputStream = object : OutputStream() {
        override fun write(value: Int) = write(byteArrayOf(value.toByte()))
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
            synchronized(writeLock) {
                var position = offset
                while (position < offset + length) {
                    synchronized(lock) {
                        checkOpen()
                        if (stage != Stage.READY) throw IOException("Connexion BLE non prête")
                        writing = true
                    }
                    val end = minOf(position + 20, offset + length)
                    operation("Écriture BLE refusée") { driver.write(bytes.copyOfRange(position, end)) }
                    synchronized(lock) {
                        while (writing) { checkOpen(); lock.wait() }
                        checkOpen()
                    }
                    position = end
                }
            }
        }
        override fun close() = this@BleSerialTransport.close()
    }

    private fun advance(expected: Stage, next: Stage): Boolean = synchronized(lock) {
        if (stage != expected) false else { stage = next; lock.notifyAll(); true }
    }

    private fun operation(message: String, block: () -> Boolean) {
        try {
            if (!block()) fail(IOException(message))
        } catch (e: Exception) {
            fail(if (e is IOException) e else IOException(message, e))
        }
    }

    private fun checkOpen() { failure?.let { throw it } }

    private fun fail(error: IOException) {
        synchronized(lock) {
            if (stage == Stage.CLOSED) return
            failure = error
            stage = Stage.CLOSED
            chunks.clear()
            bufferedBytes = 0
            lock.notifyAll()
        }
        driver.close()
    }

    override fun close() = fail(IOException("Connexion BLE fermée"))
}
