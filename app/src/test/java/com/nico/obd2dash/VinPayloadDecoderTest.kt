package com.nico.obd2dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VinPayloadDecoderTest {
    private val vin = "1D4GP00R55B123456"
    private val vinHex = vin.map { "%02X".format(it.code) }.joinToString("")
    // Les deux exemples ELM327DS, p. 43, décrivent le même VIN.
    private val can = "014\r0:490201314434\r1:47503030523535\r2:42313233343536"
    private val nonCan = listOf("49020100000031", "49020244344750", "49020330305235", "49020435423132", "49020533343536")

    @Test fun `le VIN CAN documente est decode sans octet perdu`() {
        assertEquals(vin, VinPayloadDecoder.decode(can, isCan = true))
    }

    @Test fun `le VIN non CAN documente est assemble selon les cinq indices`() {
        assertEquals(vin, VinPayloadDecoder.decode(nonCan.joinToString("\r"), isCan = false))
        assertEquals(vin, VinPayloadDecoder.decode(nonCan.reversed().joinToString("\r"), isCan = false))
    }

    @Test fun `un compteur CAN different de un est refuse`() {
        for (count in listOf("00", "02")) {
            assertNull(VinPayloadDecoder.decode("4902$count$vinHex", isCan = true))
        }
    }

    @Test fun `un octet nul intrus ne peut pas etre retire pour fabriquer un VIN`() {
        assertNull(VinPayloadDecoder.decode("49020100$vinHex", isCan = true))
        assertNull(VinPayloadDecoder.decode("490201${vinHex.take(10)}00${vinHex.drop(10)}", isCan = true))
    }

    @Test fun `la ponctuation et les lettres interdites ne constituent pas un VIN`() {
        for (character in listOf('!', 'I', 'O', 'Q', 'a')) {
            val invalid = "%02X".format(character.code).repeat(17)
            assertNull(VinPayloadDecoder.decode("490201$invalid", isCan = true))
        }
    }

    @Test fun `une longueur VIN incorrecte est refusee`() {
        assertNull(VinPayloadDecoder.decode("490201${vinHex.dropLast(2)}", isCan = true))
        assertNull(VinPayloadDecoder.decode("490201${vinHex}31", isCan = true))
    }

    @Test fun `un segment non CAN absent ou malforme invalide l'identite`() {
        for (frames in listOf(nonCan.dropLast(1), nonCan + "49020330305234", nonCan.map { it.replace("000000", "000001") })) {
            assertNull(VinPayloadDecoder.decode(frames.joinToString("\r"), isCan = false))
        }
    }

    @Test fun `des VIN contradictoires ne sont jamais fusionnes`() {
        assertNull(VinPayloadDecoder.decode("490201$vinHex\r490201${vinHex.dropLast(2)}37", isCan = true))
        assertEquals(vin, VinPayloadDecoder.decode("490201$vinHex\r490201$vinHex", isCan = true))
    }

    @Test fun `une reponse refusee ou tronquee reste inconnue`() {
        assertNull(VinPayloadDecoder.decode("$can\r7F0911", isCan = true))
        assertNull(VinPayloadDecoder.decode(can.substringBefore("\r2:"), isCan = true))
        assertNull(VinPayloadDecoder.decode("490201${vinHex}GG", isCan = true))
    }
}
