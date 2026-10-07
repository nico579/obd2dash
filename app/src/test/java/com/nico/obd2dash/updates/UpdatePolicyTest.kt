package com.nico.obd2dash.updates

import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    private val release = UpdateRelease.validated("v0.17", "", "$UPDATE_REPOSITORY/releases/download/v0.17/app-debug.apk", 100,
        "sha256:" + "a".repeat(64), 123)
    private val installed = UpdateApkIdentity("com.nico.obd2dash", "0.16", 16, setOf("original-signer"))
    private val candidate = installed.copy(versionName = "0.17", versionCode = 17)

    @Test fun versionsAreNumericAndNormalizeTrailingZero() {
        assertTrue(UpdateVersion.parse("0.10") > UpdateVersion.parse("0.9"))
        assertTrue(UpdateVersion.parse("1.0") > UpdateVersion.parse("0.99"))
        assertEquals(UpdateVersion.parse("0.17"), UpdateVersion.parse("0.17.0"))
        assertTrue(UpdateVersion.parse("0.17.1") > UpdateVersion.parse("0.17"))
    }

    @Test fun invalidVersionsNeverBecomeUpdates() {
        for (value in listOf("", "17", "v0.17", "0.17-beta", "0.17+build", "00.17", "-1.0", "0.17.0.1", "0.9999999999", " 0.17")) {
            assertThrows(IllegalArgumentException::class.java) { UpdateVersion.parse(value) }
        }
    }

    @Test fun compatibleNewApkIsAccepted() { requireCompatibleUpdate(installed, candidate, release) }
    @Test fun foreignPackageIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { requireCompatibleUpdate(installed, candidate.copy(packageName = "other.app"), release) }
    }
    @Test fun sameOrLowerVersionCodeIsRejected() {
        for (code in listOf(15L, 16L)) {
            assertThrows(IllegalArgumentException::class.java) { requireCompatibleUpdate(installed, candidate.copy(versionCode = code), release) }
        }
    }
    @Test fun newerCodeCannotDisguiseOldOrDifferentVersion() {
        for (version in listOf("0.15", "0.16", "0.18")) {
            assertThrows(IllegalArgumentException::class.java) { requireCompatibleUpdate(installed, candidate.copy(versionName = version), release) }
        }
    }
    @Test fun unknownOrDifferentSignersAreRejected() {
        for (signers in listOf(emptySet(), setOf("foreign-signer"), setOf("original-signer", "foreign-signer"))) {
            assertThrows(IllegalArgumentException::class.java) { requireCompatibleUpdate(installed, candidate.copy(signers = signers), release) }
        }
        assertThrows(IllegalArgumentException::class.java) { requireCompatibleUpdate(installed.copy(signers = emptySet()), candidate, release) }
    }
    @Test fun publishedApkMustBelongToExactRepositoryAndTag() {
        for (url in listOf("http://github.com/nico579/obd2dash/releases/download/v0.17/app-debug.apk",
            "$UPDATE_REPOSITORY/releases/download/v0.18/app-debug.apk", "$UPDATE_REPOSITORY/releases/download/v0.17/other.apk",
            "https://github.com/other/obd2dash/releases/download/v0.17/app-debug.apk")) {
            assertThrows(IllegalArgumentException::class.java) { UpdateRelease.validated("v0.17", "", url, 100, "sha256:" + "a".repeat(64), 123) }
        }
    }
    @Test fun missingDigestAndInvalidSizesAreRejected() {
        for (digest in listOf("", "md5:" + "a".repeat(32), "sha256:" + "g".repeat(64), "sha256:" + "a".repeat(63))) {
            assertThrows(IllegalArgumentException::class.java) { UpdateRelease.validated(release.tag, "", release.downloadUrl, 100, digest, 123) }
        }
        for (size in listOf(-1L, 0L, MAX_APK_BYTES + 1)) {
            assertThrows(IllegalArgumentException::class.java) { UpdateRelease.validated(release.tag, "", release.downloadUrl, size, "sha256:" + "a".repeat(64), 123) }
        }
    }
    @Test fun redirectTargetsCannotEscapeGithubHttps() {
        for (url in listOf("http://github.com/file", "https://github.com.evil.test/file", "https://github.com@evil.test/file",
            "https://user@github.com/file", "https://github.com:444/file", "https://127.0.0.1/file", "file:///tmp/file.apk")) {
            assertThrows(IllegalArgumentException::class.java) { requireUpdateUrl(url) }
        }
        requireUpdateUrl("https://release-assets.githubusercontent.com/file?token=example")
        requireUpdateUrl(UPDATE_API)
    }
    @Test fun installationIsBlockedForEveryActiveCaptureOrTest() {
        for (recording in listOf(false, true)) for (testing in listOf(false, true)) for (probing in listOf(false, true)) {
            assertEquals(recording || testing || probing, updateInstallationBlocked(recording, testing, probing))
        }
    }

    @Test fun privateAssetUsesItsApiEndpoint() {
        assertEquals("https://api.github.com/repos/nico579/obd2dash/releases/assets/123", release.assetApiUrl)
        assertThrows(IllegalArgumentException::class.java) {
            UpdateRelease.validated(release.tag, "", release.downloadUrl, release.size, "sha256:" + release.sha256, 0)
        }
    }

    @Test fun authenticationNeverFollowsCdnOrBrowserRedirects() {
        val token = "github_pat_" + "a".repeat(40)
        assertEquals("Bearer $token", updateAuthorization(UPDATE_API, token))
        assertEquals("Bearer $token", updateAuthorization(release.assetApiUrl, token))
        for (url in listOf(release.downloadUrl, "https://release-assets.githubusercontent.com/file", "https://objects.githubusercontent.com/file",
            "https://api.github.com/repos/other/repo/releases/latest")) assertNull(updateAuthorization(url, token))
        assertNull(updateAuthorization(UPDATE_API, ""))
    }

    @Test fun tokenValidationRejectsHeaderInjectionAndNeverEchoesTheSecret() {
        val secret = "github_pat_" + "a".repeat(40)
        assertEquals(secret, validatedUpdateToken("  $secret  "))
        assertEquals("", validatedUpdateToken("  "))
        for (bad in listOf("$secret\r\nInjected: value", "$secret space", "a".repeat(513), "short")) {
            val error = assertThrows(IllegalArgumentException::class.java) { validatedUpdateToken(bad) }
            assertFalse(error.message.orEmpty().contains(secret))
        }
    }
}
