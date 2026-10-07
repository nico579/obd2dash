package com.nico.obd2dash

import com.nico.obd2dash.profiles.strictHexBytes
import java.io.IOException

/**
 * Read-only audit probe. Merely constructs an ELM client; never connects, calls
 * sendRaw, opens sockets, accesses Bluetooth, or changes production sources.
 * Compile in the same Kotlin module as current, unmodified production sources.
 * Every observation now checks the corrected contract and the valid controls.
 */
private val rejected = setOf("can_count_one_empty_slot", "can_count_two_one_empty_slot",
    "can_zero_plus_count_one_empty_slot", "noncan_bare_stored_service", "noncan_bare_pending_service",
    "dtc_zero_plus_buffer_full", "dtc_zero_plus_can_error", "mil_off_plus_data_error", "mil_off_plus_buffer_full")
private val expected = mapOf("vin_model_year_code_6" to "2006", "vin_existing_seat_control" to "2011",
    "profile_tab_separated_payload" to "[97, 66, 16, 0]", "control_can_zero" to "[]",
    "control_can_one_plus_padding" to "[P0087]", "control_noncan_zero_padding" to "[]",
    "control_profile_space_separated" to "[97, 66, 16, 0]", "control_profile_compact" to "[97, 66, 16, 0]",
    "control_dtc_zero_plus_searching" to "[]", "freeze_frame_trigger_only_exported" to "true",
    "control_freeze_frame_trigger_and_measure_exported" to "true")
private val failures = mutableListOf<String>()

private fun observe(name: String, block: () -> Any?) {
    val outcome = runCatching(block)
    val result = outcome.fold(
        onSuccess = { "RETURN ${it.toString()}" },
        onFailure = { "THROW ${it.javaClass.simpleName}: ${it.message}" }
    )
    println("$name\t${result.replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n")}")
    val valid = if (name in rejected) outcome.exceptionOrNull() is IOException
        else outcome.isSuccess && outcome.getOrNull().toString() == expected.getValue(name)
    if (!valid) failures += name
}

fun main() {
    val client = Elm327Client("127.0.0.1", 1)

    observe("can_count_one_empty_slot") {
        client.parseDtcResponses("43010000", "43", isCan = true)
    }
    observe("can_count_two_one_empty_slot") {
        client.parseDtcResponses("430200870000", "43", isCan = true)
    }
    observe("can_zero_plus_count_one_empty_slot") {
        client.parseDtcResponses("4300\r43010000", "43", isCan = true)
    }
    observe("noncan_bare_stored_service") {
        client.parseDtcResponses("43", "43", isCan = false)
    }
    observe("noncan_bare_pending_service") {
        client.parseDtcResponses("47", "47", isCan = false)
    }
    observe("dtc_zero_plus_buffer_full") {
        client.parseDtcResponses("4300\rBUFFER FULL", "43", isCan = true)
    }
    observe("dtc_zero_plus_can_error") {
        client.parseDtcResponses("4300\rCAN ERROR", "43", isCan = true)
    }
    observe("mil_off_plus_data_error") {
        client.parseMilStatus("410100000000\rDATA ERROR")
    }
    observe("mil_off_plus_buffer_full") {
        client.parseMilStatus("410100000000\rBUFFER FULL")
    }

    // Synthetic syntax-valid VIN, not an observation from a vehicle.
    observe("vin_model_year_code_6") {
        val info = VinDecoder.decode("VSSZZZ6JZ6R000000", referenceYear = 2026)
        check(info.modelYearIsEstimate && info.modelYearCandidates == listOf(2006))
        info.modelYear
    }
    observe("vin_existing_seat_control") {
        val info = VinDecoder.decode("VSSZZZ6JZBR000000", referenceYear = 2026)
        check(info.modelYearIsEstimate && info.modelYearCandidates == listOf(1981, 2011))
        info.modelYear
    }
    observe("profile_tab_separated_payload") {
        strictHexBytes("61\t42\t10\t00")
    }

    observe("control_can_zero") {
        client.parseDtcResponses("4300", "43", isCan = true)
    }
    observe("control_can_one_plus_padding") {
        client.parseDtcResponses("43010087AAAA", "43", isCan = true)
    }
    observe("control_noncan_zero_padding") {
        client.parseDtcResponses("43000000000000", "43", isCan = false)
    }
    observe("control_profile_space_separated") {
        strictHexBytes("61 42 10 00")
    }
    observe("control_profile_compact") {
        strictHexBytes("61421000")
    }
    observe("control_dtc_zero_plus_searching") {
        client.parseDtcResponses("SEARCHING...\r4300", "43", isCan = true)
    }
    observe("freeze_frame_trigger_only_exported") {
        buildDiagnosticReport(ObdUiState(
            freezeFrameDtc = "P0087", freezeFrameLastSuccessAtMs = 1_000L
        )).contains("Code déclencheur : P0087")
    }
    observe("control_freeze_frame_trigger_and_measure_exported") {
        buildDiagnosticReport(ObdUiState(
            freezeFrameDtc = "P0087", freezeFrame = mapOf(0x0C to "1305 rpm"),
            freezeFrameLastSuccessAtMs = 1_000L
        )).contains("Code déclencheur : P0087")
    }
    check(failures.isEmpty()) { "Parser/export regressions: ${failures.joinToString()}" }
    println("PASS parser/export: ${rejected.size + expected.size} contracts")
}
