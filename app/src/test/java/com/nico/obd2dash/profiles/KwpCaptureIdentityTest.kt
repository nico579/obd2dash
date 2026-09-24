package com.nico.obd2dash.profiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KwpCaptureIdentityTest {
    @Test
    fun `saved Trafic frame extracts the exact candidate signature without a VIN`() {
        val identity = KwpCaptureIdentity.parse(CAPTURED_FRAME, 0x7A, 0xF1)
        assertEquals(CapturedStdAIdentity(0x7A, 0xF1, 25, "037", "00CB", "1200"), identity)
        assertEquals(0x19, identity.diagnosticVersion)
    }

    @Test
    fun `compact lowercase and horizontal whitespace preserve the same fields`() {
        val expected = KwpCaptureIdentity.parse(CAPTURED_FRAME)
        for (text in listOf(
            CAPTURED_FRAME.replace(" ", "").lowercase(),
            " \t" + CAPTURED_FRAME.replace(" ", " \t ") + "\t "
        )) assertEquals(expected, KwpCaptureIdentity.parse(text))
    }

    @Test
    fun `addresses are returned without forcing a Trafic source`() {
        val text = frame(identityPayload(), source = 0x10, destination = 0xF0)
        val parsed = KwpCaptureIdentity.parse(text)
        assertEquals(0x10, parsed.sourceAddress)
        assertEquals(0xF0, parsed.testerAddress)
        assertEquals(parsed, KwpCaptureIdentity.parse(text, expectedSource = 0x10))
        assertEquals(parsed, KwpCaptureIdentity.parse(text, expectedDestination = 0xF0))
        rejected(text, "source", source = 0x7A)
        rejected(text, "destination", destination = 0xF1)
    }

    @Test
    fun `expected addresses must be unsigned octets`() {
        for (invalid in listOf(-1, 256, Int.MAX_VALUE, Int.MIN_VALUE)) {
            rejected(CAPTURED_FRAME, "octet", source = invalid)
            rejected(CAPTURED_FRAME, "octet", destination = invalid)
        }
    }

    @Test
    fun `explicit lengths support the full 255 byte payload and 260 byte frame`() {
        for (size in listOf(20, 63, 64, 255)) {
            val text = frame(identityPayload(size), explicitLength = true)
            assertEquals(size + 5, text.split(' ').size)
            assertEquals("00CB", KwpCaptureIdentity.parse(text).software)
        }
        assertEquals(260, frame(identityPayload(255)).split(' ').size)
        rejected(frame(identityPayload(255)) + " 00", "260")
    }

    @Test
    fun `embedded length supports the 63 byte boundary`() {
        val text = frame(identityPayload(63))
        assertTrue(text.startsWith("BF "))
        assertEquals(67, text.split(' ').size)
        assertEquals(25, KwpCaptureIdentity.parse(text).diagnosticVersion)
    }

    @Test
    fun `twenty bytes are sufficient but nineteen bytes cannot supply every field`() {
        val payload = identityPayload(20)
        assertEquals("1200", KwpCaptureIdentity.parse(frame(payload)).version)
        rejected(frame(payload.copyOf(19)), "trop courte")
        rejected(frame(intArrayOf(0x61, 0x80)), "trop courte")
    }

    @Test
    fun `diagnostic version is unsigned and leading zeroes are preserved`() {
        val payload = identityPayload()
        payload[7] = 0xFF
        payload[16] = 0
        payload[17] = 1
        payload[18] = 0
        payload[19] = 0
        val parsed = KwpCaptureIdentity.parse(frame(payload))
        assertEquals(255, parsed.diagnosticVersion)
        assertEquals("037", parsed.supplier)
        assertEquals("0001", parsed.software)
        assertEquals("0000", parsed.version)
    }

    @Test
    fun `different diagnostic versions remain different signatures`() {
        val payload = identityPayload()
        val original = KwpCaptureIdentity.parse(frame(payload))
        for (diagnosticVersion in listOf(24, 0x25)) {
            payload[7] = diagnosticVersion
            val other = KwpCaptureIdentity.parse(frame(payload))
            assertFalse(original == other)
            assertEquals(diagnosticVersion, other.diagnosticVersion)
        }
    }

    @Test
    fun `supplier is strict printable ASCII and is never trimmed`() {
        val payload = identityPayload()
        payload[8] = 0x20
        payload[9] = 'A'.code
        payload[10] = 0x7E
        assertEquals(" A~", KwpCaptureIdentity.parse(frame(payload)).supplier)
        for (invalid in listOf(0x00, 0x09, 0x1F, 0x7F, 0x80, 0xFF)) {
            for (offset in 8..10) {
                val malformed = identityPayload()
                malformed[offset] = invalid
                rejected(frame(malformed), "ASCII")
            }
        }
    }

    @Test
    fun `status text prompts multiple frames and malformed hex are never cleaned up`() {
        for (text in listOf(
            "", " \t ", "SEARCHING...", "BUS INIT: OK", "NO DATA",
            CAPTURED_FRAME + ">", CAPTURED_FRAME + "\r", CAPTURED_FRAME + "\n",
            CAPTURED_FRAME + "\r\n" + CAPTURED_FRAME,
            CAPTURED_FRAME.replace(" ", "") + CAPTURED_FRAME.replace(" ", ""),
            "9AF1 7A", "9A F17A", "9 A F1", "9AF17", "9A F1 GX",
            "9A\u00A0F1", "９Ａ F1", "9A F1 7A #"
        )) rejected(text)
    }

    @Test
    fun `truncated headers and unsupported addressing are rejected`() {
        for (text in listOf("80", "80 F1", "80 F1 7A")) rejected(text, "En-tête")
        for (format in listOf(0x03, 0x43, 0xC3)) {
            val bytes = intArrayOf(format, 0xF1, 0x7A, 0x61, 0x80, 0x00)
            rejected(checksummed(bytes), "adressage")
        }
    }

    @Test
    fun `declared length must match the complete frame exactly`() {
        rejected("80 F1 7A 00 EB", "vide")
        rejected(CAPTURED_FRAME.substringBeforeLast(' '), "tronquée")
        rejected("80 F1 7A 40 61 80", "tronquée")
        rejected(CAPTURED_FRAME + " 00", "supplémentaires")
    }

    @Test
    fun `checksum failure prevents any interpretation of otherwise valid fields`() {
        rejected(CAPTURED_FRAME.substringBeforeLast(' ') + " 00", "Checksum")
        rejected(CAPTURED_FRAME.replace(" 19 30", " 18 30"), "Checksum")
    }

    @Test
    fun `positive replies must be from service 21 identifier 80`() {
        for (payload in listOf(
            intArrayOf(0x41, 0, 0, 0, 0, 0),
            intArrayOf(0x50, 0xC0),
            intArrayOf(0x61),
            intArrayOf(0x61, 0x81, 0)
        )) rejected(frame(payload))
        val wrongIdentifier = identityPayload().also { it[1] = 0x81 }
        rejected(frame(wrongIdentifier), "identifiant 80")
    }

    @Test
    fun `negative pending and wrong service responses cannot produce an identity`() {
        rejected(frame(intArrayOf(0x7F, 0x21, 0x12)), "refusée")
        rejected(frame(intArrayOf(0x7F, 0x21, 0x78)), "en attente")
        rejected(frame(intArrayOf(0x7F, 0x10, 0x11)), "autre service")
        for (payload in listOf(
            intArrayOf(0x7F), intArrayOf(0x7F, 0x21), intArrayOf(0x7F, 0x21, 0x12, 0)
        )) rejected(frame(payload), "mal formée")
    }

    @Test
    fun `error messages do not expose the captured frame or identifying payload`() {
        val invalid = CAPTURED_FRAME.substringBeforeLast(' ') + " 00"
        val error = rejected(invalid)
        assertFalse(error.message.orEmpty().contains(CAPTURED_FRAME))
        assertFalse(error.message.orEmpty().contains("82 00 40 25 78"))
        assertFalse(error.message.orEmpty().contains("037"))
    }

    private fun rejected(
        text: String,
        messageFragment: String? = null,
        source: Int? = null,
        destination: Int? = null
    ): IllegalArgumentException {
        val error = assertThrows(IllegalArgumentException::class.java) {
            KwpCaptureIdentity.parse(text, source, destination)
        }
        assertFalse(error.message.isNullOrBlank())
        if (messageFragment != null) {
            assertTrue(error.message, error.message.orEmpty().contains(messageFragment))
        }
        return error
    }

    /** Synthetic response data; only CAPTURED_FRAME below comes from the saved Trafic exchange. */
    private fun identityPayload(size: Int = 20): IntArray {
        require(size >= 20)
        return IntArray(size) { (it * 3) and 0xFF }.also {
            it[0] = 0x61
            it[1] = 0x80
            it[7] = 0x19
            it[8] = '0'.code
            it[9] = '3'.code
            it[10] = '7'.code
            it[16] = 0x00
            it[17] = 0xCB
            it[18] = 0x12
            it[19] = 0x00
        }
    }

    private fun frame(
        payload: IntArray,
        source: Int = 0x7A,
        destination: Int = 0xF1,
        explicitLength: Boolean = false
    ): String {
        require(payload.size in 1..255)
        val header = if (explicitLength || payload.size > 63) {
            intArrayOf(0x80, destination, source, payload.size)
        } else {
            intArrayOf(0x80 or payload.size, destination, source)
        }
        return checksummed(header + payload)
    }

    private fun checksummed(bytes: IntArray): String =
        (bytes + (bytes.sum() and 0xFF)).joinToString(" ") {
            it.toString(16).uppercase().padStart(2, '0')
        }

    private companion object {
        const val CAPTURED_FRAME =
            "9A F1 7A 61 80 82 00 40 25 78 19 30 33 37 82 00 35 51 75 00 CB 12 00 83 A1 7C 01 CA 12 CF"
    }
}
