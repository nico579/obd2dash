package com.nico.obd2dash.updates

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpdateReleaseTest {
    private fun metadata(): JSONObject = JSONObject().put("tag_name", "v0.17").put("draft", false).put("prerelease", false)
        .put("body", "Nouveautés")
        .put("assets", JSONArray().put(JSONObject().put("id", 123).put("name", "app-debug.apk").put("state", "uploaded")
            .put("size", 123456).put("digest", "sha256:" + "a".repeat(64))
            .put("browser_download_url", "$UPDATE_REPOSITORY/releases/download/v0.17/app-debug.apk")))

    @Test fun officialStablePublicationIsParsed() {
        val release = parseUpdateRelease(metadata().toString())
        assertEquals("0.17", release.versionName)
        assertEquals(123456L, release.size)
        assertEquals("a".repeat(64), release.sha256)
    }
    @Test fun draftsAndPrereleasesAreRejected() {
        for (flag in listOf("draft", "prerelease")) {
            assertThrows(IllegalArgumentException::class.java) { parseUpdateRelease(metadata().put(flag, true).toString()) }
        }
    }
    @Test fun absentOrDuplicateApksAreRejected() {
        val missing = metadata().put("assets", JSONArray())
        assertThrows(IllegalArgumentException::class.java) { parseUpdateRelease(missing.toString()) }
        val duplicate = metadata()
        val assets = duplicate.getJSONArray("assets")
        assets.put(assets.getJSONObject(0))
        assertThrows(IllegalArgumentException::class.java) { parseUpdateRelease(duplicate.toString()) }
    }
    @Test fun uploadingAssetIsNotInstallable() {
        val root = metadata()
        root.getJSONArray("assets").getJSONObject(0).put("state", "new")
        assertThrows(IllegalArgumentException::class.java) { parseUpdateRelease(root.toString()) }
    }
    @Test fun missingChecksumIsRejected() {
        val root = metadata()
        root.getJSONArray("assets").getJSONObject(0).remove("digest")
        assertThrows(Exception::class.java) { parseUpdateRelease(root.toString()) }
    }
    @Test fun assetFromAnotherPublicationIsRejected() {
        val root = metadata()
        root.getJSONArray("assets").getJSONObject(0).put("browser_download_url", "$UPDATE_REPOSITORY/releases/download/v0.18/app-debug.apk")
        assertThrows(IllegalArgumentException::class.java) { parseUpdateRelease(root.toString()) }
    }
    @Test fun malformedResponseCannotEnableInstallation() {
        for (json in listOf("{}", "not JSON", "[]")) assertThrows(Exception::class.java) { parseUpdateRelease(json) }
    }
}
