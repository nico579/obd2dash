package com.nico.obd2dash
import android.app.Application

import android.bluetooth.BluetoothDevice
import androidx.lifecycle.auditDispatcher
import kotlinx.coroutines.*
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
    FakeElm().use { initial -> FakeElm(bitmap = "410000000000", blockDiscovery = true).use { empty -> FakeElm().use { next ->
        val vm = makeVm(initial, dir)
        try {
            start(vm)
            awaitUntil { vm.state.value.recordingSamples > 0 }
            val file = csvFile(dir)
            val before = file.readBytes()
            val samples = vm.state.value.recordingSamples
            val sessionId = checkNotNull(vm.state.value.recordingSessionId)
            val writer = privateField(vm, "recordingWriter")
            reconnect(vm, empty)
            awaitUntil { empty.discoveryBlocked.count == 0L }
            // An unfinished discovery still pauses the current capture: it has
            // not yet established whether this is the same vehicle.
            delay(ONE_CSV_INTERVAL_PLUS_MARGIN)
            assertPaused(vm, dir, before, samples)
            check(vm.state.value.recordingSessionId == sessionId)
            check(privateField(vm, "recordingWriter") === writer)
            empty.releaseDiscovery.countDown()
            awaitUntil { vm.state.value.connectionState == ConnectionState.CONNECTED }
            check(vm.state.value.dataAvailability == ObdDataAvailability.NO_STANDARD_MEASUREMENTS)
            check(vm.state.value.vin == null)
            val discoveryCommands = synchronized(empty.commands) { empty.commands.toList() }
            // The zero bitmap permits only discovery/adapter identification;
            // VIN, live measurements and diagnostics must not be queried.
            check(discoveryCommands.filterNot { it.startsWith("AT") }
                .all { it in setOf("0100", "STI", "STDI") }) {
                "Unexpected operational query after empty discovery: $discoveryCommands"
            }
            suspend fun assertStopped() = withContext(auditDispatcher) {
                val state = vm.state.value
                check(!state.isRecording) { "Unverifiable identity kept the old capture active" }
                check(state.recordingSessionId == null)
                check(privateField(vm, "recordingWriter") == null)
                check(privateField(vm, "recordingJob") == null)
                check(state.recordingSamples == samples)
                val reason = state.recordingError.orEmpty()
                check(reason.contains("Enregistrement arrêté") && reason.contains("identité") &&
                    reason.contains("conservé")) { "Missing explicit identity stop reason: $reason" }
                check(csvFile(dir) == file) { "An automatic replacement CSV was created" }
                check(file.readBytes().contentEquals(before)) { "The previous CSV was changed" }
                check(state.recordings.size == 1)
            }
            // A confirmed zero bitmap cannot verify the prior VIN. The old
            // file must be closed and retained, rather than paused indefinitely.
            assertStopped()
            delay(ONE_CSV_INTERVAL_PLUS_MARGIN)
            assertStopped()
            check(synchronized(empty.commands) { empty.commands.toList() } == discoveryCommands) {
                "Automatic queries continued after confirmed empty discovery"
            }
            reconnect(vm, next)
            awaitUntil {
                vm.state.value.connectionState == ConnectionState.CONNECTED &&
                    vm.state.value.values.isNotEmpty()
            }
            check(vm.state.value.vin == "1D4GP00R55B123456")
            assertStopped() // Returning to the old VIN does not restart a closed CSV.
            // The user can explicitly start a separate capture on the recovered
            // vehicle; the already closed CSV remains untouched.
            start(vm)
            check(vm.state.value.recordingSessionId != sessionId)
            awaitUntil { vm.state.value.recordingSamples > 0 }
            val files = File(dir, "recordings").listFiles()!!.filter { it.extension == "csv" }
            check(files.size == 2 && file in files)
            check(file.readBytes().contentEquals(before))
            check(files.single { it != file }.readBytes().isNotEmpty())
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
    val allowStoredSuccess = AtomicBoolean(false)
    FakeElm(bitmap = "410080100000", replyOverride = { command ->
        when (command) {
            "0101" -> "410181000000"
            "03" -> { storedAttempts.incrementAndGet(); if (allowStoredSuccess.get()) "43010087" else "NO DATA" }
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
            allowStoredSuccess.set(true)
            awaitUntil(timeoutMs = 35_000) { vm.state.value.storedDtcs == listOf("P0087") }
            check(storedAttempts.get() >= 2) { "A failed code read was not retried" }
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

private suspend fun traficDiagnosticRefusals(dir: File) {
    FakeElm(bitmap = "410000000000", replyOverride = { command ->
        when (command) {
            "ATDPN" -> "A5"
            "0101" -> "7F0112"
            "03" -> "43000000"
            "07" -> "7F0711"
            else -> null
        }
    }).use { server ->
        val vm = withContext(auditDispatcher) { ObdViewModel(Application(dir.apply { mkdirs() })) }
        try {
            withContext(auditDispatcher) { vm.connect("127.0.0.1", server.port) }
            awaitUntil { vm.state.value.connectionState == ConnectionState.CONNECTED }
            withContext(auditDispatcher) { vm.refreshDtcs() }
            awaitUntil { !vm.state.value.dtcLoading && vm.state.value.freezeFrameLastSuccessAtMs != null }
            val state = vm.state.value
            check(state.milOn == null && state.milLastSuccessAtMs == null)
            check(state.storedDtcs == null && state.storedDtcsLastSuccessAtMs == null)
            check(state.pendingDtcs == null && state.pendingDtcsLastSuccessAtMs == null)
            check(state.readinessLastSuccessAtMs == null)
            check(state.freezeFrameDtc == null && state.freezeFrame.isEmpty())
            check(state.dtcHistory.isEmpty() && state.dtcLastSuccessAtMs == null)
            check(state.diagnosticReadErrors.keys == setOf(DiagnosticRead.MIL, DiagnosticRead.STORED,
                DiagnosticRead.PENDING, DiagnosticRead.READINESS))
            check(state.connectionState == ConnectionState.CONNECTED)
            val commands = synchronized(server.commands) { server.commands.toList() }
            check(commands.containsAll(listOf("0101", "03", "07", "020200")))
            File(dir, "commands.txt").writeText(commands.joinToString("\n"))
        } finally { finish(vm) }
    }
}

private suspend fun partialDiagnosticDates(dir: File) {
    val mil = AtomicReference("410100000000")
    FakeElm(replyOverride = { command -> if (command == "0101") mil.get() else null }).use { server ->
        val vm = makeVm(server, dir)
        try {
            withContext(auditDispatcher) { vm.refreshDtcs() }
            awaitUntil { !vm.state.value.dtcLoading && vm.state.value.dtcLastSuccessAtMs != null }
            val previous = vm.state.value
            mil.set("7F0112")
            withContext(auditDispatcher) { vm.refreshDtcs() }
            awaitUntil { !vm.state.value.dtcLoading && vm.state.value.dtcError != null }
            val next = vm.state.value
            check(next.milLastSuccessAtMs == previous.milLastSuccessAtMs)
            check(next.readinessLastSuccessAtMs == previous.readinessLastSuccessAtMs)
            check(next.dtcLastSuccessAtMs == previous.dtcLastSuccessAtMs)
            check(next.storedDtcsLastSuccessAtMs!! > previous.storedDtcsLastSuccessAtMs!!)
            check(next.pendingDtcsLastSuccessAtMs!! > previous.pendingDtcsLastSuccessAtMs!!)
            check(next.freezeFrameLastSuccessAtMs!! > previous.freezeFrameLastSuccessAtMs!!)
            check(next.diagnosticReadErrors.keys == setOf(DiagnosticRead.MIL, DiagnosticRead.READINESS))
        } finally { finish(vm) }
    }
}

private suspend fun temporaryDiagnosticStops(dir: File) {
    FakeElm(bitmap = "410000000000", replyOverride = { command ->
        if (command == "0101") "7F0178" else null
    }).use { server ->
        val vm = withContext(auditDispatcher) { ObdViewModel(Application(dir.apply { mkdirs() })) }
        try {
            withContext(auditDispatcher) { vm.connect("127.0.0.1", server.port) }
            awaitUntil { vm.state.value.connectionState == ConnectionState.CONNECTED }
            withContext(auditDispatcher) { vm.refreshDtcs() }
            awaitUntil { vm.state.value.connectionState == ConnectionState.RECONNECTING }
            withContext(auditDispatcher) { vm.disconnect() }
            check(!server.commands.contains("03") && !server.commands.contains("07"))
        } finally { finish(vm) }
    }
}

private suspend fun automaticCodesDespiteMilRefusal(dir: File) {
    FakeElm(bitmap = "410080100000", replyOverride = { command ->
        when (command) { "0101" -> "7F0112"; "03" -> "43010087"; else -> null }
    }).use { server ->
        val vm = makeVm(server, dir)
        try {
            awaitUntil { vm.state.value.storedDtcs == listOf("P0087") }
            check(vm.state.value.milOn == null && vm.state.value.milLastSuccessAtMs == null)
            check(vm.state.value.storedDtcsLastSuccessAtMs != null)
            check(vm.state.value.dtcLastSuccessAtMs == null)
            check(vm.state.value.connectionState == ConnectionState.CONNECTED)
        } finally { finish(vm) }
    }
}

private fun warningBitmap(command: String, pids: Set<Int>): String? {
    if (command !in listOf("0100", "0120", "0140", "0160", "0180")) return null
    val base = command.takeLast(2).toInt(16)
    val bitmap = pids.filter { it > base && it <= base + 32 }.fold(0L) { bits, pid ->
        bits or (1L shl (32 - (pid - base)))
    }
    return "41%02X%08X".format(base, bitmap)
}

private fun captureTable(file: File): Pair<List<String>, List<List<String>>> {
    val lines = file.readLines()
    val header = lines.indexOfFirst { it.startsWith("\"Horodatage\";") }
    check(header >= 0)
    fun cells(line: String) = Regex("\"((?:\"\"|[^\"])*)\"(?:;|$)").findAll(line)
        .map { it.groupValues[1].replace("\"\"", "\"") }.toList()
    val columns = cells(lines[header])
    val rows = lines.drop(header + 1).filter { it.isNotBlank() }.map(::cells)
    check(rows.all { it.size == columns.size }) { "CSV columns shifted during recording/reconnection" }
    return columns to rows
}

private suspend fun automaticWarningCapture(dir: File) {
    val pids = setOf(0x01, 0x0C, 0x20, 0x40, 0x60, 0x65, 0x80, 0x90, 0x91, 0x94)
    val phase = AtomicInteger(0)
    FakeElm(replyOverride = { command ->
        warningBitmap(command, pids) ?: when (command) {
            "0101" -> when (phase.get()) { 0 -> "410100000000"; 2 -> "7F0112"; else -> "410181000000" }
            "03" -> if (phase.get() == 0) "4300" else "43010087"
            "0165" -> when (phase.get()) { 0, 3 -> "41650808"; 1 -> "41650800"; else -> "NO DATA" }
            "0190" -> "41900C0000"
            "0191" -> "41910200000000"
            "0194" -> when (phase.get()) { 1 -> "41940100" + "00".repeat(10); 2 -> "41940100"; else -> "41940101" + "00".repeat(10) }
            else -> null
        }
    }).use { server -> FakeElm().use { next ->
        val vm = makeVm(server, dir)
        try {
            val glow = StandardWarningLight.GLOW_PLUG
            val nox = StandardWarningLight.NOX_WARNING
            awaitUntil { vm.state.value.standardWarnings.size == 4 }
            val initial = vm.state.value
            check(initial.standardWarnings[glow]?.status == StandardWarningStatus.ON)
            check(initial.standardWarnings[nox]?.status == StandardWarningStatus.ON)
            check(initial.standardWarnings[StandardWarningLight.WWH_VEHICLE_MI]?.status == StandardWarningStatus.CONTINUOUS)
            check(initial.standardWarnings[StandardWarningLight.WWH_ECU_MI]?.status == StandardWarningStatus.SHORT)
            start(vm)
            awaitUntil { vm.state.value.recordingSamples > 0 }
            val file = csvFile(dir)
            val columns = captureTable(file).first
            fun index(label: String) = columns.indexOf(label).also { check(it >= 0) }
            check("ABS" !in columns && "Airbag" !in columns)
            check(columns.take(6) == listOf("Horodatage", "État", "MIL", "Codes stockés", "Lecture MIL", "Lecture codes stockés"))
            check(columns.last() == PidCatalog.defs.single { it.pid == 0x0C }.label)
            check(captureTable(file).second.any { it[index("Préchauffage")] == "allumé" && it[index("Alerte NOx")] == "active" })

            phase.set(1)
            awaitUntil { vm.state.value.standardWarnings[glow]?.status == StandardWarningStatus.OFF &&
                vm.state.value.milOn == true && vm.state.value.storedDtcs == listOf("P0087") }
            awaitUntil { captureTable(file).second.any { it[index("Préchauffage")] == "éteint" &&
                it[index("Alerte NOx")] == "inactive" && it[index("MIL")] == "allumé" && it[index("Codes stockés")] == "P0087" } }
            phase.set(2)
            awaitUntil { vm.state.value.standardWarnings[glow]?.error != null && vm.state.value.standardWarnings[nox]?.error != null &&
                vm.state.value.milReadError != null }
            val failed = vm.state.value
            // Une réponse déjà en vol lors du changement de phase peut encore réussir.
            // Comparer après le premier échec confirmé, puis un nouvel échec réel.
            awaitUntil { (vm.state.value.milLastAttemptAtMs ?: 0) > (failed.milLastAttemptAtMs ?: 0) &&
                (vm.state.value.standardWarnings[glow]?.lastAttemptAtMs ?: 0) > (failed.standardWarnings[glow]?.lastAttemptAtMs ?: 0) &&
                (vm.state.value.standardWarnings[nox]?.lastAttemptAtMs ?: 0) > (failed.standardWarnings[nox]?.lastAttemptAtMs ?: 0) }
            val repeatedFailure = vm.state.value
            check(repeatedFailure.standardWarnings[glow]?.lastSuccessAtMs == failed.standardWarnings[glow]?.lastSuccessAtMs)
            check(repeatedFailure.standardWarnings[nox]?.lastSuccessAtMs == failed.standardWarnings[nox]?.lastSuccessAtMs)
            check(repeatedFailure.milLastSuccessAtMs == failed.milLastSuccessAtMs)
            check(failed.connectionState == ConnectionState.CONNECTED) { "An unsupported lamp read blocked the other measurements" }
            awaitUntil { captureTable(file).second.any { it[index("Erreur Préchauffage")].isNotEmpty() &&
                it[index("Erreur Alerte NOx")].isNotEmpty() && it[index("Erreur MIL")].isNotEmpty() } }
            val errorRow = captureTable(file).second.last { it[index("Erreur Préchauffage")].isNotEmpty() }
            val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ssXXX", java.util.Locale.FRANCE)
            check(errorRow[index("Préchauffage")] == "éteint")
            check(date.parse(errorRow[index("Lecture Préchauffage")])!!.before(date.parse(errorRow[index("Tentative Préchauffage")])))

            phase.set(3)
            awaitUntil { vm.state.value.standardWarnings[glow]?.status == StandardWarningStatus.ON &&
                vm.state.value.standardWarnings[glow]?.error == null && vm.state.value.milReadError == null &&
                vm.state.value.standardWarnings[nox]?.status == StandardWarningStatus.ON && vm.state.value.standardWarnings[nox]?.error == null }
            awaitUntil { captureTable(file).second.any { it[index("Préchauffage")] == "allumé" && it[index("MIL")] == "allumé" &&
                it[index("Erreur Préchauffage")].isEmpty() && it[index("Erreur MIL")].isEmpty() } }
            check(vm.state.value.pendingDtcs == null && vm.state.value.dtcLastSuccessAtMs == null)
            val samples = vm.state.value.recordingSamples
            reconnect(vm, next)
            awaitUntil { vm.state.value.connectionState == ConnectionState.CONNECTED && vm.state.value.values.isNotEmpty() }
            check(vm.state.value.standardWarnings.isEmpty()) { "Warning states leaked into the next connection" }
            awaitUntil { vm.state.value.recordingSamples > samples }
            check(csvFile(dir) == file)
            check(captureTable(file).first == columns)
            val resumed = captureTable(file).second.last()
            check(resumed[index("Préchauffage")] == "non lu" && resumed[index("Lecture Préchauffage")].isEmpty())
            check(next.commands.none { it in listOf("0101", "0165", "0190", "0191", "0194") })
            val commands = synchronized(server.commands) { server.commands.toList() }
            check(commands.filter { !it.startsWith("AT") }.all {
                it.startsWith("01") || it in listOf("03", "0902", "STI", "STDI")
            })
            check(commands.none { it in listOf("01651", "01901", "01911", "01941") })
            File(dir, "commands.txt").writeText(commands.joinToString("\n"))
        } finally { finish(vm) }
    } }
}

private suspend fun warningDiagnosticPause(dir: File) {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val pids = setOf(0x0C, 0x20, 0x40, 0x60, 0x65)
    FakeElm(replyOverride = { command ->
        warningBitmap(command, pids) ?: when (command) {
            "0165" -> "41650808"
            "03" -> { entered.countDown(); check(release.await(15, TimeUnit.SECONDS)); "4300" }
            else -> null
        }
    }).use { server ->
        val vm = makeVm(server, dir)
        try {
            awaitUntil { vm.state.value.standardWarnings.isNotEmpty() }
            val before = vm.state.value.standardWarnings[StandardWarningLight.GLOW_PLUG]?.lastSuccessAtMs
            start(vm)
            // Le diagnostic couvre l'échéance CSV/alerte de 5 s sans dépasser
            // l'échéance de transport de 3 s sur une commande individuelle.
            delay(RECORDING_WARNING_INTERVAL_MS - 1_000)
            withContext(auditDispatcher) { vm.refreshDtcs() }
            awaitUntil { entered.count == 0L }
            val commandsBefore = synchronized(server.commands) { server.commands.toList() }
            delay(1_600)
            check(synchronized(server.commands) { server.commands.toList() } == commandsBefore) { "Warning polling ran during an exclusive diagnostic" }
            check(captureTable(csvFile(dir)).second.any { it[1] == "pause diagnostic" })
            release.countDown()
            awaitUntil { !vm.state.value.dtcLoading &&
                (vm.state.value.standardWarnings[StandardWarningLight.GLOW_PLUG]?.lastSuccessAtMs ?: 0) > (before ?: 0) }
            check(vm.state.value.isRecording)
        } finally { release.countDown(); finish(vm) }
    }
}

private suspend fun gaugeOrderPreferences(dir: File) {
    val firstVin = "1D4GP00R55B123456"
    val secondVin = "1D4GP00R55B123457"
    fun vinReply(vin: String) = "490201" + vin.map { "%02X".format(it.code) }.joinToString("")
    FakeElm(bitmap = "410008180000", replyOverride = { if (it == "0902") vinReply(firstVin) else null }).use { first ->
        FakeElm(bitmap = "410008180000", replyOverride = { if (it == "0902") vinReply(secondVin) else null }).use { second ->
            val app = Application(dir.apply { mkdirs() })
            val prefs = app.getSharedPreferences("obd2dash", android.content.Context.MODE_PRIVATE)
            val vm = withContext(auditDispatcher) { ObdViewModel(app) }
            val firstOrder = listOf(0x05, 0x0C, 0x0D, 0x42)
            try {
                reconnect(vm, first)
                awaitUntil { vm.state.value.connectionState == ConnectionState.CONNECTED }
                check(vm.state.value.vin == firstVin)
                withContext(auditDispatcher) {
                    vm.setBigGaugePidSelected(0x42, true) // Choix conservé, indisponible dans ce bitmap.
                    vm.setBigGaugePidSelected(0x42, true) // Pas de doublon après migration Set -> List.
                    vm.reorderBigGaugePids(listOf(0x05, 0x0C, 0x0D))
                }
                check(vm.state.value.bigGaugePids == firstOrder)
                check(prefs.getString("big_gauge_pids:$firstVin", null) == firstOrder.joinToString(","))
                withContext(auditDispatcher) { vm.reorderBigGaugePids(listOf(0x0C, 0x0D)) }
                check(vm.state.value.bigGaugePids == firstOrder) // Geste incomplet refusé.
                reconnect(vm, second)
                awaitUntil { vm.state.value.connectionState == ConnectionState.CONNECTED }
                check(vm.state.value.vin == secondVin)
                check(vm.state.value.bigGaugePids == PidCatalog.PRIMARY_PIDS.toList())
                withContext(auditDispatcher) { vm.reorderBigGaugePids(listOf(0x0D, 0x05, 0x0C)) }
                check(prefs.getString("big_gauge_pids:$firstVin", null) == firstOrder.joinToString(","))
                reconnect(vm, first)
                awaitUntil { vm.state.value.connectionState == ConnectionState.CONNECTED }
                check(vm.state.value.bigGaugePids == firstOrder)
            } finally { finish(vm) }
            val restarted = withContext(auditDispatcher) { ObdViewModel(app) }
            try {
                reconnect(restarted, first)
                awaitUntil { restarted.state.value.connectionState == ConnectionState.CONNECTED }
                check(restarted.state.value.bigGaugePids == firstOrder)
            } finally { finish(restarted) }
        }
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
        "diagnostic_dates" to ::automaticReadPreservesFullScanDate,
        "trafic_diagnostic_refusals" to ::traficDiagnosticRefusals,
        "partial_diagnostic_dates" to ::partialDiagnosticDates,
        "temporary_diagnostic_stops" to ::temporaryDiagnosticStops,
        "automatic_codes_despite_mil_refusal" to ::automaticCodesDespiteMilRefusal,
        "gauge_order_preferences" to ::gaugeOrderPreferences,
        "automatic_warning_capture" to ::automaticWarningCapture,
        "warning_diagnostic_pause" to ::warningDiagnosticPause
    )
    val selected = args.getOrNull(1)?.let { name -> mapOf(name to cases.getValue(name)) } ?: cases
    var failed = 0
    for ((name, runCase) in selected) {
        try {
            withTimeout(if (name == "automatic_warning_capture") 60_000 else 40_000) { runCase(File(root, name)) }
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
