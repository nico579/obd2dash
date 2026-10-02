package com.nico.obd2dash

import android.bluetooth.BluetoothDevice
import androidx.lifecycle.auditDispatcher
import kotlinx.coroutines.*
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private const val ONE_CSV_INTERVAL_PLUS_MARGIN = 6_100L

private suspend fun start(vm: ObdViewModel) = withContext(auditDispatcher) {
    vm.startRecording()
    check(vm.state.value.isRecording)
}

private suspend fun reconnect(vm: ObdViewModel, server: FakeElm) = withContext(auditDispatcher) {
    vm.updateHost("127.0.0.1")
    vm.updatePort(server.port)
    vm.connect("127.0.0.1", server.port)
}

private suspend fun manualReconnect(dir: File) {
    FakeElm().use { initial -> FakeElm(blockDiscovery = true).use { next ->
        val vm = makeVm(initial, dir)
        try {
            start(vm)
            awaitUntil { vm.state.value.recordingSamples > 0 }
            val file = csvFile(dir)
            val before = file.readBytes()
            val samples = vm.state.value.recordingSamples
            val writer = privateField(vm, "recordingWriter")
            reconnect(vm, next)
            awaitUntil { next.discoveryBlocked.count == 0L }
            // A second request while CONNECTING must not restart the capture/session.
            reconnect(vm, next)
            delay(ONE_CSV_INTERVAL_PLUS_MARGIN)
            assertPaused(vm, dir, before, samples)
            next.releaseDiscovery.countDown()
            awaitUntil { vm.state.value.connectionState == ConnectionState.CONNECTED }
            awaitUntil { vm.state.value.recordingSamples > samples }
            check(privateField(vm, "recordingWriter") === writer)
            check(csvFile(dir) == file)
            check(file.readBytes().take(before.size).toByteArray().contentEquals(before))
            check(!file.readText().contains("aucune mesure"))
            check(initial.closedPeers.get() > 0)
        } finally { finish(vm) }
    } }
}

private suspend fun stopDuringReconnect(dir: File) {
    FakeElm().use { initial -> FakeElm(blockDiscovery = true).use { next ->
        val vm = makeVm(initial, dir)
        try {
            start(vm)
            reconnect(vm, next)
            awaitUntil { next.discoveryBlocked.count == 0L }
            withContext(auditDispatcher) { vm.stopRecording() }
            val before = csvFile(dir).readBytes()
            val samples = vm.state.value.recordingSamples
            next.releaseDiscovery.countDown()
            awaitUntil { vm.state.value.connectionState == ConnectionState.CONNECTED }
            delay(ONE_CSV_INTERVAL_PLUS_MARGIN)
            check(!vm.state.value.isRecording)
            check(privateField(vm, "recordingWriter") == null)
            check(privateField(vm, "recordingJob") == null)
            check(vm.state.value.recordingSamples == samples)
            check(csvFile(dir).readBytes().contentEquals(before))
            check(vm.state.value.recordings.size == 1)
        } finally { finish(vm) }
    } }
}

private suspend fun noMeasurements(dir: File) {
    FakeElm().use { initial -> FakeElm(bitmap = "410000000000").use { empty -> FakeElm().use { next ->
        val vm = makeVm(initial, dir)
        try {
            start(vm)
            val before = csvFile(dir).readBytes()
            reconnect(vm, empty)
            awaitUntil { vm.state.value.connectionState == ConnectionState.CONNECTED }
            check(vm.state.value.dataAvailability == ObdDataAvailability.NO_STANDARD_MEASUREMENTS)
            delay(ONE_CSV_INTERVAL_PLUS_MARGIN)
            assertPaused(vm, dir, before, 0)
            reconnect(vm, next)
            awaitUntil { vm.state.value.recordingSamples > 0 }
            check(csvFile(dir).readBytes().take(before.size).toByteArray().contentEquals(before))
        } finally { finish(vm) }
    } } }
}

private suspend fun failedReconnect(dir: File) {
    FakeElm().use { initial -> FakeElm(failDiscovery = true).use { failed ->
        val vm = makeVm(initial, dir)
        try {
            start(vm)
            val before = csvFile(dir).readBytes()
            reconnect(vm, failed)
            awaitUntil { failed.commands.contains("0100") && vm.state.value.connectionState != ConnectionState.CONNECTING }
            delay(ONE_CSV_INTERVAL_PLUS_MARGIN)
            assertPaused(vm, dir, before, 0)
        } finally { finish(vm) }
    } }
}

private suspend fun transportLoss(dir: File) {
    FakeElm().use { server ->
        val vm = makeVm(server, dir)
        try {
            start(vm)
            val before = csvFile(dir).readBytes()
            server.blockDiscovery = true
            server.cutConnections()
            awaitUntil { privateField(vm, "recordingJob") == null }
            // Exercise the real automatic retry loop, with the next discovery held.
            awaitUntil { server.discoveryBlocked.count == 0L }
            delay(ONE_CSV_INTERVAL_PLUS_MARGIN)
            assertPaused(vm, dir, before, 0)
            server.releaseDiscovery.countDown()
            awaitUntil { vm.state.value.recordingSamples > 0 }
            check(csvFile(dir).readBytes().take(before.size).toByteArray().contentEquals(before))
        } finally { finish(vm) }
    }
}

private suspend fun invalidTarget(dir: File) {
    FakeElm().use { server ->
        val vm = makeVm(server, dir)
        try {
            start(vm)
            withContext(auditDispatcher) { vm.connect("", "not-a-port") }
            check(vm.state.value.connectionState == ConnectionState.CONNECTED) { "Invalid replacement hid the working session" }
            check(vm.state.value.errorMessage != null)
            awaitUntil { vm.state.value.recordingSamples > 0 }
            check(server.closedPeers.get() == 0)
            check(!csvFile(dir).readText().contains("aucune mesure"))
        } finally { finish(vm) }
    }
}

private suspend fun bluetoothWithoutDevice(dir: File) {
    FakeElm().use { server ->
        val vm = makeVm(server, dir)
        try {
            start(vm)
            val before = csvFile(dir).readBytes()
            withContext(auditDispatcher) { vm.switchConnectionMode(ConnectionMode.BLUETOOTH) }
            check(vm.state.value.connectionState == ConnectionState.DISCONNECTED)
            check(vm.state.value.connectionMode == ConnectionMode.BLUETOOTH)
            delay(ONE_CSV_INTERVAL_PLUS_MARGIN)
            assertPaused(vm, dir, before, 0)
            check(server.closedPeers.get() > 0) { "Previous Wi-Fi connection remains open" }
            check(privateField(vm, "client") == null)
        } finally { finish(vm) }
    }
}

private suspend fun bluetoothFailure(dir: File) {
    FakeElm().use { server ->
        val vm = makeVm(server, dir)
        try {
            start(vm)
            val before = csvFile(dir).readBytes()
            // The Android Bluetooth double always throws; it cannot open hardware.
            withContext(auditDispatcher) { vm.connectBluetooth(BluetoothDevice()) }
            awaitUntil { vm.state.value.connectionState == ConnectionState.ERROR }
            delay(ONE_CSV_INTERVAL_PLUS_MARGIN)
            assertPaused(vm, dir, before, 0)
            check(server.closedPeers.get() > 0)
        } finally { finish(vm) }
    }
}

private suspend fun manualDiagnostics(dir: File) {
    FakeElm().use { server ->
        val vm = makeVm(server, dir)
        try {
            start(vm)
            withContext(auditDispatcher) { vm.refreshDtcs() }
            awaitUntil { vm.state.value.dtcLastSuccessAtMs != null }
            withContext(auditDispatcher) { vm.stopAutoTest() }
            check(vm.state.value.isRecording)
            awaitUntil { vm.state.value.recordingSamples > 0 }
            check(vm.state.value.connectionState == ConnectionState.CONNECTED)
        } finally { finish(vm) }
    }
}

/** Hold IO between write() and newLine(), after releasing the actual writer's lock. */
private class GatedWriter(private val delegate: BufferedWriter) : BufferedWriter(delegate) {
    val written = CountDownLatch(1)
    val release = CountDownLatch(1)
    override fun write(value: String, offset: Int, length: Int) {
        delegate.write(value, offset, length)
        written.countDown()
        check(release.await(20, TimeUnit.SECONDS)) { "Writer gate timed out" }
    }
    override fun newLine() = delegate.newLine()
    override fun flush() = delegate.flush()
    override fun close() = delegate.close()
}

private suspend fun stopAndRestartDuringWrite(dir: File) {
    FakeElm().use { server ->
        val vm = makeVm(server, dir)
        var gated: GatedWriter? = null
        try {
            start(vm)
            val oldFile = csvFile(dir)
            // Pause before the first interval, replace only storage with a gated real
            // writer, then invoke the actual loop. Production source is not rewritten.
            withContext(auditDispatcher) {
                val pause = ObdViewModel::class.java.getDeclaredMethod("pauseRecordingForReconnect")
                pause.isAccessible = true
                pause.invoke(vm)
                gated = GatedWriter(privateField(vm, "recordingWriter") as BufferedWriter)
                ObdViewModel::class.java.getDeclaredField("recordingWriter").apply {
                    isAccessible = true
                    set(vm, gated)
                }
                ObdViewModel::class.java.getDeclaredMethod("resumeRecordingLoop").apply {
                    isAccessible = true
                    invoke(vm)
                }
            }
            val oldJob = privateField(vm, "recordingJob") as Job
            awaitUntil { gated!!.written.count == 0L }
            withContext(auditDispatcher) {
                vm.stopRecording()
                vm.startRecording()
                check(vm.state.value.isRecording)
            }
            val nextFile = File(dir, "recordings").listFiles()!!.single { it != oldFile }
            val before = nextFile.readBytes()
            gated!!.release.countDown()
            awaitUntil { oldJob.isCompleted }
            check(nextFile.readBytes().contentEquals(before)) { "Old CSV loop wrote into the new file" }
            check(vm.state.value.recordingSamples == 0)
            check(vm.state.value.recordingError == null)
            awaitUntil { vm.state.value.recordingSamples > 0 }
            check(vm.state.value.isRecording)
        } finally {
            gated?.release?.countDown()
            finish(vm)
        }
    }
}

private suspend fun milPublishedBeforeDtcRetry(dir: File) {
    val storedAttempts = AtomicInteger()
    FakeElm(bitmap = "410080100000", replyOverride = { command ->
        when (command) {
            "0101" -> "410181000000"
            "03" -> if (storedAttempts.incrementAndGet() == 1) "NO DATA" else "43010087"
            else -> null
        }
    }).use { server ->
        val vm = makeVm(server, dir)
        try {
            awaitUntil { storedAttempts.get() == 1 && vm.state.value.milLastSuccessAtMs != null }
            check(vm.state.value.milOn == true) { "A failed DTC read hid the successful MIL reading" }
            check(vm.state.value.dtcCount == 1)
            check(vm.state.value.storedDtcs == null)
            check(vm.state.value.dtcLastSuccessAtMs == null)
            start(vm)
            awaitUntil { vm.state.value.recordingSamples > 0 }
            val initialCsv = csvFile(dir).readText()
            check(initialCsv.contains("\"Lecture MIL\";\"Lecture codes stockés\""))
            check(initialCsv.contains("\"allumé\";\"1 annoncé(s), détail non lu\""))
            awaitUntil(timeoutMs = 35_000) { vm.state.value.storedDtcs == listOf("P0087") }
            check(storedAttempts.get() == 2) { "A failed code read was not retried" }
            check(vm.state.value.storedDtcsLastSuccessAtMs != null)
            check(vm.state.value.dtcLastSuccessAtMs == null) { "An automatic code read became a full scan" }
            check(vm.state.value.pendingDtcs == null)
        } finally { finish(vm) }
    }
}

private suspend fun automaticReadPreservesFullScanDate(dir: File) {
    val mil = AtomicReference("410100000000")
    val stored = AtomicReference("4300")
    FakeElm(bitmap = "410080100000", replyOverride = { command ->
        when (command) { "0101" -> mil.get(); "03" -> stored.get(); else -> null }
    }).use { server ->
        val vm = makeVm(server, dir)
        try {
            awaitUntil { vm.state.value.storedDtcs != null }
            withContext(auditDispatcher) { vm.refreshDtcs() }
            awaitUntil { vm.state.value.dtcLastSuccessAtMs != null && !vm.state.value.dtcLoading }
            val fullScanAt = vm.state.value.dtcLastSuccessAtMs
            val pending = vm.state.value.pendingDtcs
            check(pending == emptyList<String>())
            mil.set("410181000000")
            stored.set("43010087")
            awaitUntil(timeoutMs = 35_000) { vm.state.value.storedDtcs == listOf("P0087") }
            check(vm.state.value.milOn == true)
            check(vm.state.value.storedDtcsLastSuccessAtMs!! > fullScanAt!!)
            check(vm.state.value.dtcLastSuccessAtMs == fullScanAt) { "The old pending/readiness snapshot was given a new date" }
            check(vm.state.value.pendingDtcs == pending)
        } finally { finish(vm) }
    }
}

fun main(args: Array<String>) = runBlocking {
    val root = File(args[0]).apply { mkdirs() }
    val cases: Map<String, suspend (File) -> Unit> = linkedMapOf(
        "manual_reconnect" to ::manualReconnect,
        "stop_during_reconnect" to ::stopDuringReconnect,
        "no_measurements" to ::noMeasurements,
        "failed_reconnect" to ::failedReconnect,
        "transport_loss" to ::transportLoss,
        "invalid_target" to ::invalidTarget,
        "bluetooth_without_device" to ::bluetoothWithoutDevice,
        "bluetooth_failure" to ::bluetoothFailure,
        "manual_diagnostics" to ::manualDiagnostics,
        "stop_restart_during_write" to ::stopAndRestartDuringWrite,
        "mil_retry" to ::milPublishedBeforeDtcRetry,
        "diagnostic_dates" to ::automaticReadPreservesFullScanDate
    )
    val selected = args.getOrNull(1)?.let { name -> mapOf(name to cases.getValue(name)) } ?: cases
    var failed = 0
    for ((name, runCase) in selected) {
        try {
            withTimeout(40_000) { runCase(File(root, name)) }
            println("PASS $name")
        } catch (e: Throwable) {
            failed++
            println("FAIL $name: $e")
            e.printStackTrace()
        }
    }
    println("RESULT ${selected.size} scenarios, $failed failures; real Kotlin, loopback TCP, Android doubles")
    check(failed == 0) { "$failed recording lifecycle scenario(s) failed" }
}
