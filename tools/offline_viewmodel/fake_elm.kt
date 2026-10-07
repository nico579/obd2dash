package com.nico.obd2dash

import android.app.Application
import androidx.lifecycle.auditDispatcher
import kotlinx.coroutines.*
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** TCP loopback only. No vehicle address, Android network or Bluetooth connection. */
class FakeElm(
    private val bitmap: String = "410000100000",
    blockDiscovery: Boolean = false,
    private val failDiscovery: Boolean = false,
    private val replyOverride: ((String) -> String?)? = null
) : AutoCloseable {
    private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val port: String get() = listener.localPort.toString()
    val commands = Collections.synchronizedList(mutableListOf<String>())
    val discoveryBlocked = CountDownLatch(1)
    val releaseDiscovery = CountDownLatch(1)
    val closedPeers = AtomicInteger()
    @Volatile var blockDiscovery = blockDiscovery
    private val peers = Collections.synchronizedList(mutableListOf<Socket>())

    init {
        Thread({
            while (!listener.isClosed) {
                val socket = try { listener.accept() } catch (_: Exception) { break }
                check(socket.inetAddress.isLoopbackAddress)
                peers.add(socket)
                Thread({ serve(socket) }, "fake-elm-peer").apply { isDaemon = true; start() }
            }
        }, "fake-elm-listener").apply { isDaemon = true; start() }
    }

    private fun serve(socket: Socket) {
        try {
            socket.use {
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                val command = StringBuilder()
                var headers = false
                while (true) {
                    val b = input.read()
                    if (b < 0) break
                    if (b != 13) { command.append(b.toChar()); continue }
                    val text = command.toString()
                    command.setLength(0)
                    commands.add(text)
                    if (text == "0100") {
                        if (blockDiscovery) {
                            discoveryBlocked.countDown()
                            check(releaseDiscovery.await(30, TimeUnit.SECONDS)) { "Discovery gate timed out" }
                        }
                        if (failDiscovery) break
                    }
                    val reply = replyOverride?.invoke(text) ?: when (text) {
                        "ATZ" -> "ELM327 v1.5"
                        "ATH1" -> { headers = true; "OK" }
                        "ATH0" -> { headers = false; "OK" }
                        "ATDPN" -> "A6"
                        "0100" -> if (headers) "7E806$bitmap" else bitmap
                        // A stable, documented VIN makes reconnect tests positive
                        // same-vehicle controls. Unknown-identity probes override this.
                        "0902" -> "4902013144344750303052353542313233343536"
                        "010C" -> "410C0CE4"
                        "0101" -> if (headers) "7E806410100000000" else "410100000000"
                        "03" -> "4300"
                        "07" -> "4700"
                        "020200" -> "4202000000"
                        else -> if (text.startsWith("AT")) "OK" else "NO DATA"
                    }
                    output.write((reply + "\r>").toByteArray(Charsets.US_ASCII))
                    output.flush()
                }
            }
        } catch (_: Exception) {
            // Closing the real client/socket is part of the tested scenarios.
        } finally {
            runCatching { socket.close() }
            closedPeers.incrementAndGet()
        }
    }

    fun cutConnections() {
        synchronized(peers) { peers.toList() }.forEach { runCatching { it.close() } }
    }

    override fun close() {
        releaseDiscovery.countDown()
        listener.close()
        cutConnections()
    }
}

suspend fun awaitUntil(timeoutMs: Long = 12_000, predicate: () -> Boolean) {
    withTimeout(timeoutMs) { while (!predicate()) delay(10) }
}

suspend fun makeVm(server: FakeElm, dir: File): ObdViewModel {
    val vm = withContext(auditDispatcher) { ObdViewModel(Application(dir.apply { mkdirs() })) }
    withContext(auditDispatcher) {
        vm.updateHost("127.0.0.1")
        vm.updatePort(server.port)
        vm.connect("127.0.0.1", server.port)
    }
    awaitUntil { vm.state.value.connectionState == ConnectionState.CONNECTED && vm.state.value.values.isNotEmpty() }
    return vm
}

suspend fun finish(vm: ObdViewModel) {
    withContext(auditDispatcher) {
        vm.disconnect()
        vm.auditScope.cancel()
    }
}

fun privateField(vm: ObdViewModel, name: String): Any? =
    ObdViewModel::class.java.getDeclaredField(name).let { it.isAccessible = true; it.get(vm) }

fun csvFile(dir: File): File = File(dir, "recordings").listFiles()!!.single { it.extension == "csv" }

suspend fun assertPaused(vm: ObdViewModel, dir: File, expectedBytes: ByteArray, expectedSamples: Int) {
    withContext(auditDispatcher) {
        check(vm.state.value.isRecording) { "Manual recording was stopped" }
        check(privateField(vm, "recordingWriter") != null) { "Writer was closed" }
        check(privateField(vm, "recordingJob") == null) { "CSV sampling was not paused" }
        check(vm.state.value.recordingSamples == expectedSamples) { "Sample count changed during interruption" }
        check(csvFile(dir).readBytes().contentEquals(expectedBytes)) { "CSV changed during interruption" }
    }
}
