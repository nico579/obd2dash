package com.nico.obd2dash.profiles

import org.junit.Assert.*
import org.junit.Test

/** Toutes les définitions de mesures sont synthétiques et restent dans les sources de test. */
class ManufacturerProfilesTest {
    private val endpoint = EcuEndpoint(ProfileProtocol.KWP_FAST, "7A", "7A")
    private val identity = EcuFingerprint(endpoint, "renault_std_a_candidate", mapOf(
        "diagnostic_version" to "25", "supplier" to "037", "software" to "00CB", "version" to "1200"
    ))
    private val signal = LinearSignal("example_speed", "rpm", 0, 2, multiplier = 0.25)
    private val read = ProfileRead("synthetic", IdentifierRead.KWP_LOCAL_IDENTIFIER, 0x42,
        2, listOf(signal), 1000, 2000, listOf(ReadExample("61421000", mapOf("example_speed" to 1024.0))))
    private fun profile() = ManufacturerProfile("test-only", "1", identity,
        ProfileProvenance("local:synthetic-fixture", "v1", "a".repeat(64), "test-only", "Synthetic unit test"),
        ProfileEvidence.REPLAY_VERIFIED, ProfileSession.DEFAULT, "Synthetic session, no vehicle", listOf(read))

    private fun select(profile: ManufacturerProfile = profile(), fingerprint: EcuFingerprint = identity) =
        ManufacturerProfiles(listOf(profile)).select(fingerprint)

    private fun assertBlocked(profile: ManufacturerProfile) {
        val result = select(profile)
        assertEquals(result.reasons.toString(), ProfileMatchStatus.BLOCKED, result.status)
        assertTrue(result.reads.isEmpty())
    }

    private fun assertRejected(block: () -> Unit) {
        try { block(); fail("Réponse invalide acceptée") } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun `exact identity selects a versioned replay plan and decodes numeric values`() {
        val result = select()
        assertEquals(ProfileMatchStatus.READY_FOR_REPLAY, result.status)
        assertEquals("test-only", result.profileId)
        assertEquals("1", result.profileVersion)
        val plan = result.reads.single()
        assertEquals("2142", plan.requestHex)
        assertEquals(1000L, plan.intervalMs)
        assertEquals(2000L, plan.timeoutMs)
        assertEquals(listOf(DecodedSignal("example_speed", "rpm", 1024.0)),
            plan.decode(RecordedReply(endpoint, "2142", "61 42 10 00")))
    }

    @Test
    fun `nearby diagnostic version 24 never matches captured decimal version 25`() {
        val nearby = identity.copy(fields = identity.fields + ("diagnostic_version" to "24"))
        val result = select(profile().copy(identity = nearby))
        assertEquals(ProfileMatchStatus.NO_MATCH, result.status)
        assertTrue(result.reads.isEmpty())
    }

    @Test
    fun `all identity fields and transport addresses must match`() {
        val changed = listOf(
            identity.copy(fields = identity.fields + ("supplier" to "038")),
            identity.copy(fields = identity.fields + ("software" to "00CC")),
            identity.copy(fields = identity.fields + ("version" to "1201")),
            identity.copy(endpoint = endpoint.copy(protocol = ProfileProtocol.KWP_SLOW)),
            identity.copy(endpoint = endpoint.copy(requestAddress = "79")),
            identity.copy(endpoint = endpoint.copy(responseAddress = "79"))
        )
        for (fingerprint in changed) assertEquals(ProfileMatchStatus.NO_MATCH, select(fingerprint = fingerprint).status)
    }

    @Test
    fun `partial identity wildcard and VIN alone cannot select a profile`() {
        val invalid = listOf(
            identity.copy(fields = identity.fields - "software"),
            identity.copy(fields = identity.fields + ("software" to "*")),
            identity.copy(fields = identity.fields + ("diagnostic_version" to "025")),
            identity.copy(fields = identity.fields + ("supplier" to "0\u000037")),
            identity.copy(scheme = "vin_only", fields = mapOf("vin" to "SYNTHETIC")),
            identity.copy(endpoint = endpoint.copy(requestAddress = "7AFF"))
        )
        for (fingerprint in invalid) {
            val result = ManufacturerProfiles(listOf(profile().copy(identity = fingerprint))).select(fingerprint)
            assertEquals(ProfileMatchStatus.INCOMPLETE_IDENTITY, result.status)
            assertTrue(result.reads.isEmpty())
        }
    }

    @Test
    fun `multiple matching profiles remain ambiguous even with different versions`() {
        val result = ManufacturerProfiles(listOf(profile(), profile().copy(version = "2"))).select(identity)
        assertEquals(ProfileMatchStatus.AMBIGUOUS, result.status)
        assertTrue(result.reads.isEmpty())
    }

    @Test
    fun `identification evidence never grants measurement reads`() {
        assertBlocked(profile().copy(evidence = ProfileEvidence.IDENTIFICATION_ONLY))
        assertBlocked(profile().copy(reads = emptyList()))
    }

    @Test
    fun `extended unknown or undocumented session blocks the entire profile`() {
        assertBlocked(profile().copy(session = ProfileSession.EXTENDED))
        assertBlocked(profile().copy(session = ProfileSession.UNKNOWN))
        assertBlocked(profile().copy(defaultSessionEvidence = ""))
    }

    @Test
    fun `missing source revision hash license or attribution blocks the profile`() {
        val source = profile().provenance
        for (incomplete in listOf(source.copy(source = ""), source.copy(revision = ""), source.copy(sha256 = "x"),
            source.copy(license = ""), source.copy(attribution = ""))) {
            assertBlocked(profile().copy(provenance = incomplete))
        }
    }

    @Test
    fun `missing or incorrect replay expected value blocks the whole profile`() {
        assertBlocked(profile().copy(reads = listOf(read.copy(examples = emptyList()))))
        assertBlocked(profile().copy(reads = listOf(read.copy(examples = listOf(
            ReadExample("61421000", mapOf("example_speed" to 4096.0)))))))
        assertBlocked(profile().copy(reads = listOf(read.copy(examples = listOf(
            ReadExample("61421000", emptyMap()))))))
    }

    @Test
    fun `duplicate definitions and invalid byte positions fail closed`() {
        assertBlocked(profile().copy(reads = listOf(read, read)))
        assertBlocked(profile().copy(reads = listOf(read.copy(signals = listOf(signal.copy(offset = 2))))))
        assertBlocked(profile().copy(reads = listOf(read.copy(signals = listOf(signal.copy(byteCount = 5))))))
        assertBlocked(profile().copy(reads = listOf(read.copy(signals = listOf(signal, signal)))))
    }

    @Test
    fun `identifier bounds timing bounds and incompatible service are blocked`() {
        for (invalid in listOf(read.copy(identifier = 256), read.copy(identifier = -1), read.copy(intervalMs = 0),
            read.copy(timeoutMs = 46_000), read.copy(dataLength = 254),
            read.copy(service = IdentifierRead.UDS_DATA_IDENTIFIER))) {
            assertBlocked(profile().copy(reads = listOf(invalid)))
        }
    }

    @Test
    fun `decoder rejects another ECU protocol or request`() {
        val plan = select().reads.single()
        assertRejected { plan.decode(RecordedReply(endpoint.copy(responseAddress = "79"), "2142", "61421000")) }
        assertRejected { plan.decode(RecordedReply(endpoint.copy(protocol = ProfileProtocol.KWP_SLOW), "2142", "61421000")) }
        assertRejected { plan.decode(RecordedReply(endpoint, "2143", "61421000")) }
    }

    @Test
    fun `decoder rejects negative pending wrong identifier truncated and extra responses`() {
        val plan = select().reads.single()
        for (hex in listOf("7F2178", "7F2112", "61431000", "62421000", "614210", "6142100000",
            "6142100", "SEARCHING...61421000", "61421000\r>", "6142\n1000", "6 1 42 10 00")) {
            assertRejected { plan.decode(RecordedReply(endpoint, "2142", hex)) }
        }
    }

    @Test
    fun `signed little endian and additive offset decode without executing a formula string`() {
        val signed = LinearSignal("example_temp", "C", 0, 2, ByteOrder.LITTLE_ENDIAN, true, 0.5, 10.0)
        val definition = read.copy(signals = listOf(signed), examples = listOf(
            ReadExample("6142FCFF", mapOf("example_temp" to 8.0))))
        val plan = select(profile().copy(reads = listOf(definition))).reads.single()
        assertEquals(8.0, plan.decode(RecordedReply(endpoint, "2142", "6142FCFF")).single().value!!, 0.0)
    }

    @Test
    fun `sentinel produces missing value rather than a plausible numeric measurement`() {
        val sentinelSignal = signal.copy(unavailableRawValues = setOf(65535L))
        val definition = read.copy(signals = listOf(sentinelSignal), examples = read.examples +
            ReadExample("6142FFFF", mapOf("example_speed" to null)))
        val plan = select(profile().copy(reads = listOf(definition))).reads.single()
        assertNull(plan.decode(RecordedReply(endpoint, "2142", "6142FFFF")).single().value)
    }

    @Test
    fun `four byte signed and unsigned boundary values retain their numeric meaning`() {
        for ((signed, expected) in listOf(true to -1.0, false to 4294967295.0)) {
            val definition = read.copy(dataLength = 4,
                signals = listOf(signal.copy(byteCount = 4, signed = signed, multiplier = 1.0)),
                examples = listOf(ReadExample("6142FFFFFFFF", mapOf("example_speed" to expected))))
            val plan = select(profile().copy(reads = listOf(definition))).reads.single()
            assertEquals(expected, plan.decode(RecordedReply(endpoint, "2142", "6142FFFFFFFF")).single().value!!, 0.0)
        }
    }

    @Test
    fun `non finite conversion and expected value block replay`() {
        assertBlocked(profile().copy(reads = listOf(read.copy(signals = listOf(signal.copy(multiplier = Double.NaN))))))
        assertBlocked(profile().copy(reads = listOf(read.copy(examples = listOf(
            ReadExample("61421000", mapOf("example_speed" to Double.POSITIVE_INFINITY)))))))
    }

    @Test
    fun `same engine replays an explicitly defined synthetic CAN profile`() {
        val canIdentity = EcuFingerprint(EcuEndpoint(ProfileProtocol.CAN_11, "7E0", "7E8"),
            "synthetic_other_brand", mapOf("ecu_software" to "SYNTH_1", "calibration" to "CAL_2"))
        val canRead = read.copy(service = IdentifierRead.UDS_DATA_IDENTIFIER, identifier = 0x1234,
            examples = listOf(ReadExample("6212341000", mapOf("example_speed" to 1024.0))))
        val result = select(profile().copy(identity = canIdentity, reads = listOf(canRead)), canIdentity)
        assertEquals(ProfileMatchStatus.READY_FOR_REPLAY, result.status)
        assertEquals("221234", result.reads.single().requestHex)
        assertEquals(1024.0, result.reads.single().decode(
            RecordedReply(canIdentity.endpoint, "221234", "6212341000")).single().value!!, 0.0)
    }

    @Test
    fun `mutating imported lists maps or sentinel set cannot change an existing catalog`() {
        val fields = identity.fields.toMutableMap()
        val sentinels = mutableSetOf<Long>()
        val signals = mutableListOf(signal.copy(unavailableRawValues = sentinels))
        val examples = read.examples.toMutableList()
        val reads = mutableListOf(read.copy(signals = signals, examples = examples))
        val profiles = mutableListOf(profile().copy(identity = identity.copy(fields = fields), reads = reads))
        val catalog = ManufacturerProfiles(profiles)
        fields["diagnostic_version"] = "24"
        sentinels.add(4096)
        signals.clear()
        examples.clear()
        reads.clear()
        profiles.clear()
        val selected = catalog.select(identity)
        assertEquals(ProfileMatchStatus.READY_FOR_REPLAY, selected.status)
        assertEquals(1024.0, selected.reads.single().decode(RecordedReply(endpoint, "2142", "61421000")).single().value!!, 0.0)
    }

    @Test
    fun `production catalog contains no synthetic measurements or guessed Trafic profile`() {
        val result = ManufacturerProfiles.installed.select(identity)
        assertEquals(ProfileMatchStatus.NO_MATCH, result.status)
        assertTrue(result.reads.isEmpty())
        assertNull(result.profileId)
    }
}
