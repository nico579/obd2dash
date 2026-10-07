package com.nico.obd2dash

import androidx.lifecycle.auditDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Real VM/TCP observations using public operations and the existing Android doubles.
 * No reflection, timestamp substitution, configuration mutation or vehicle address.
 */
object VmIdentityFreshnessProbe {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        require(args.isNotEmpty()) { "Usage: VmIdentityFreshnessProbe OUTPUT_DIR [identity_graph|grouped_freshness|all]" }
        val root = File(args[0]).apply { mkdirs() }
        val selected = args.getOrElse(1) { "all" }
        val cases: Map<String, suspend (File) -> Unit> = linkedMapOf(
            "identity_graph" to ::identityGraph,
            "grouped_freshness" to ::groupedFreshness
        )
        require(selected == "all" || selected in cases) { "Unknown case: $selected" }
        val failures = mutableListOf<String>()
        for ((name, test) in cases) {
            if (selected != "all" && selected != name) continue
            val dir = File(root, name).apply { mkdirs() }
            try {
                test(dir)
                println("PASS $name")
            } catch (error: Throwable) {
                failures += name
                File(dir, "failure.txt").writeText(error.stackTraceToString())
                println("FAIL $name: ${error.message}")
            }
        }
        check(failures.isEmpty()) { "Failed VM probes: ${failures.joinToString()}" }
    }

    private fun vinReply(vin: String): String =
        "490201" + vin.map { "%02X".format(Locale.ROOT, it.code) }.joinToString("")

    private suspend fun identityGraph(dir: File) {
        val vinA = "1D4GP00R55B123456"
        val vinB = "1D4GP00R55B123457"
        FakeElm(replyOverride = { command -> when (command) {
            "0902" -> vinReply(vinA)
            "010C" -> "410C0FA0" // 1000 rpm, vehicle A only
            else -> null
        } }).use { serverA ->
            FakeElm(replyOverride = { command -> when (command) {
                "0902" -> vinReply(vinB)
                "010C" -> "410C1F40" // 2000 rpm, vehicle B only
                else -> null
            } }).use { serverB ->
                val vm = makeVm(serverA, dir)
                try {
                    check(vm.state.value.vin == vinA) { "Precondition: VIN A was not decoded" }
                    withContext(auditDispatcher) { vm.selectGraphPid(0x0C) }
                    awaitUntil { vm.state.value.graphHistory.count { it.value == 1000.0 } >= 2 }
                    val before = vm.state.value
                    val beforeHistory = before.graphHistory.toList()
                    withContext(auditDispatcher) { vm.connect("127.0.0.1", serverB.port) }
                    awaitUntil {
                        vm.state.value.connectionState == ConnectionState.CONNECTED &&
                            vm.state.value.vin == vinB &&
                            vm.state.value.graphHistory.any { it.value == 2000.0 }
                    }
                    val after = vm.state.value
                    val retainedA = after.graphHistory.filter { it.value == 1000.0 }
                    File(dir, "observations.csv").writeText(
                        "Phase;VIN;Horodatage point;Valeur\n" +
                            beforeHistory.joinToString("") { "A;$vinA;${it.atMs};${it.value}\n" } +
                            after.graphHistory.joinToString("") { "B;$vinB;${it.atMs};${it.value}\n" }
                    )
                    File(dir, "commands_A.txt").writeText(synchronized(serverA.commands) { serverA.commands.joinToString("\n") })
                    File(dir, "commands_B.txt").writeText(synchronized(serverB.commands) { serverB.commands.joinToString("\n") })
                    println("VIN transition: ${before.vin} -> ${after.vin}; " +
                        "A points before=${beforeHistory.size}, A points retained under B=${retainedA.size}")
                    check(retainedA.isEmpty()) {
                        "BUG: graph for confirmed VIN B contains ${retainedA.size} points acquired from confirmed VIN A"
                    }
                } finally {
                    finish(vm)
                }
            }
        }
    }

    private suspend fun groupedFreshness(dir: File) {
        val groups = AtomicInteger()
        val firstFallbackReceivedAtMs = AtomicLong()
        FakeElm(bitmap = "410000180000", replyOverride = { command -> when (command) {
            "010C0D" -> when (groups.incrementAndGet()) {
                1 -> "410C0FA00D2A" // Real detector sees both PIDs and enables grouping.
                2 -> "410D2A" // First real poll returns speed, omits the preceding RPM PID.
                else -> "NO DATA" // No newer speed value can replace the observed sample.
            }
            "010C" -> {
                if (groups.get() == 2 && firstFallbackReceivedAtMs.compareAndSet(0L, System.currentTimeMillis())) {
                    // The client has already received the group before it issues fallback.
                    // Delay stays below production transport deadlines; no constants change.
                    Thread.sleep(750)
                }
                "NO DATA"
            }
            else -> null
        } }).use { server ->
            val vm = makeVm(server, dir)
            try {
                check(vm.state.value.supportsMultiPid == true) { "Precondition: real grouping detector did not enable grouping" }
                val speed = vm.state.value.values[0x0D] ?: error("No speed sample observed")
                val fallbackAt = firstFallbackReceivedAtMs.get()
                check(fallbackAt != 0L && speed.text == "42 km/h") { "Precondition: incomplete-group/fallback sequence not observed" }
                val deltaMs = speed.updatedAtMs - fallbackAt
                File(dir, "observations.csv").writeText(
                    "Valeur;Horodatage mesure;Repli RPM reçu par serveur;Delta ms\n" +
                        "${speed.text};${speed.updatedAtMs};$fallbackAt;$deltaMs\n"
                )
                File(dir, "commands.txt").writeText(synchronized(server.commands) { server.commands.joinToString("\n") })
                println("Grouped speed=${speed.text}, recordedAt=${speed.updatedAtMs}, " +
                    "first fallback request observedAt=$fallbackAt, delta=${deltaMs}ms")
                check(deltaMs <= 100L) {
                    "BUG: grouped speed timestamp is ${deltaMs}ms after fallback began, although speed had already been received before that request"
                }
            } finally {
                finish(vm)
            }
        }
    }
}
