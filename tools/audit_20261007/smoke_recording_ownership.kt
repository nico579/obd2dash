package com.nico.obd2dash

import androidx.lifecycle.auditDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

/** Exercise the real smoke-test path; no ownership fields or production constants are changed. */
fun main(args: Array<String>) = runBlocking {
    val dir = File(args.single()).apply { mkdirs() }
    FakeElm().use { server ->
        val vm = makeVm(server, dir)
        try {
            withContext(auditDispatcher) { vm.runAutoTest() }
            awaitUntil {
                vm.state.value.isAutoTesting &&
                    vm.state.value.autoTestChecks.getOrNull(5)?.status == AutoTestStatus.EN_COURS &&
                    vm.state.value.isRecording
            }
            withContext(auditDispatcher) {
                check(privateField(vm, "autoTestOwnsRecording") == true) {
                    "Precondition failed: the real smoke test did not start its own CSV"
                }
                val smokeWriter = privateField(vm, "recordingWriter")
                val smokeFile = csvFile(dir)
                println("Smoke CSV: ${smokeFile.name}; recording=${vm.state.value.isRecording}")

                // These are the same public methods used by the visible REC button.
                vm.stopRecording()
                check(!vm.state.value.isRecording)
                vm.startRecording()
                check(vm.state.value.isRecording)
                val manualWriter = privateField(vm, "recordingWriter")
                check(manualWriter != null && manualWriter !== smokeWriter)
                val manualFile = File(dir, "recordings").listFiles()!!
                    .single { it.extension == "csv" && it != smokeFile }
                println("New manual CSV: ${manualFile.name}; owner flag=${privateField(vm, "autoTestOwnsRecording")}")

                vm.stopAutoTest()
                println("After stopping smoke: manual recording=${vm.state.value.isRecording}; " +
                    "same manual writer=${privateField(vm, "recordingWriter") === manualWriter}")
                check(vm.state.value.isRecording && privateField(vm, "recordingWriter") === manualWriter) {
                    "BUG: stopping the smoke test closed a different CSV started manually after its own CSV was stopped"
                }
            }
            println("PASS smoke_recording_ownership")
        } finally {
            finish(vm)
        }
    }
}
