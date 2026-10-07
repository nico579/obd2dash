package com.nico.obd2dash

import com.nico.obd2dash.profiles.strictHexBytes

/**
 * Read-only audit probe. Merely constructs an ELM client; never connects, calls
 * sendRaw, opens sockets, accesses Bluetooth, or changes production sources.
 * Compile in the same Kotlin module as current, unmodified production sources.
 * Observations, rather than assertions, preserve every pre-fix result in one run.
 */
private fun observe(name: String, block: () -> Any?) {
    val result = runCatching(block).fold(
        onSuccess = { "RETURN ${it.toString()}" },
        onFailure = { "THROW ${it.javaClass.simpleName}: ${it.message}" }
    )
    println("$name\t${result.replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n")}")
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

    // Synthetic syntax-valid VIN, not an observation from a vehicle. In October
    // 2026 its year code 6 is forced to 2036 despite also encoding 2006.
    observe("vin_model_year_code_6") {
        VinDecoder.decode("VSSZZZ6JZ6R000000")
    }
    observe("vin_existing_seat_control") {
        VinDecoder.decode("VSSZZZ6JZBR000000")
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
}
