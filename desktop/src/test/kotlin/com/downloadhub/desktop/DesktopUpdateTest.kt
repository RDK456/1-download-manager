package com.downloadhub.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The updater has to be careful about two things: never offering a build that is not
 * newer, and never offering the Android APK to a Windows user.
 */
class DesktopUpdateTest {

    @Test
    fun versionsCompareNumericallyNotAsText() {
        // The bug this guards: "1.10.0" < "1.9.0" as a string compare.
        assertTrue(isNewerVersion("1.10.0", "1.9.0"))
        assertFalse(isNewerVersion("1.9.0", "1.10.0"))
        assertTrue(isNewerVersion("2.0.0", "1.99.99"))
        assertFalse(isNewerVersion("1.4.0", "1.4.0"))
    }

    @Test
    fun aLeadingVIsIgnored() {
        assertEquals(0, compareVersions("v1.4.0", "1.4.0"))
        assertTrue(isNewerVersion("v1.5.0", "1.4.0"))
    }

    @Test
    fun missingSegmentsCountAsZero() {
        assertEquals(0, compareVersions("1.4", "1.4.0"))
        assertEquals(0, compareVersions("1", "1.0.0"))
        assertTrue(isNewerVersion("1.4.1", "1.4"))
    }

    @Test
    fun aPrereleaseIsNeverOfferedOverTheFinalRelease() {
        // The suffix is stripped before comparing, so a prerelease and its final
        // release come out equal. That is deliberate: "not newer" keeps the updater
        // quiet instead of offering a build it should not.
        assertFalse(isNewerVersion("1.5.0-beta", "1.5.0"))
        assertFalse(isNewerVersion("1.5.0", "1.5.0-beta"))
        // A later prerelease is still newer than an earlier release.
        assertTrue(isNewerVersion("1.6.0-beta", "1.5.0"))
    }

    @Test
    fun nonNumericSegmentsDoNotCrashTheComparison() {
        // A tag like "release-1.4" must not throw in the user's face.
        assertEquals(0, compareVersions("release", "0.0.0"))
        assertTrue(isNewerVersion("1.4.0", "nonsense"))
    }

    @Test
    fun onlyTheWindowsInstallerIsOffered() {
        val release = GithubRelease(
            tag = "v1.5.0",
            name = "1 download manager 1.5.0",
            assets = listOf(
                GithubAsset("1-download-manager-1.5.0.apk", "https://x/apk", 132_000_000),
                GithubAsset("1DownloadManager-1.5.0.msi", "https://x/msi", 158_000_000)
            )
        )
        val asset = release.installer()
        assertNotNull("the msi should be found", asset)
        assertTrue("the apk must never be offered on Windows", asset!!.name.endsWith(".msi"))
    }

    @Test
    fun aReleaseWithOnlyAnApkSaysSoRatherThanOfferingIt() {
        val release = GithubRelease(
            tag = "v1.5.0",
            assets = listOf(GithubAsset("1-download-manager-1.5.0.apk", "https://x/apk", 1))
        )
        assertNull("an android-only release has no windows installer", release.installer())
    }

    @Test
    fun theVersionComesOffTheTagWithoutTheV() {
        assertEquals("1.5.0", GithubRelease(tag = "v1.5.0").version)
        assertEquals("1.5.0", GithubRelease(tag = "1.5.0").version)
    }

    @Test
    fun aNamedReleaseKeepsItsNameAndFallsBackToTheVersion() {
        assertEquals("Nice release", GithubRelease(tag = "v1.5.0", name = "Nice release").displayName)
        assertEquals("Version 1.5.0", GithubRelease(tag = "v1.5.0").displayName)
    }

    @Test
    fun theReleaseJsonShapeIsUnderstood() {
        // Guards the field names against a rename in the API, which would otherwise
        // surface as "no update" instead of a parse failure.
        val json = """
            {
              "tag_name": "v9.9.9",
              "name": "1 download manager 9.9.9",
              "draft": false,
              "prerelease": false,
              "assets": [
                {"name": "1DownloadManager-9.9.9.msi", "browser_download_url": "https://x/a.msi", "size": 42}
              ],
              "unexpected_field": {"ignored": true}
            }
        """.trimIndent()
        val parsed = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString<GithubRelease>(json)
        assertEquals("9.9.9", parsed.version)
        assertEquals(1, parsed.assets.size)
        assertEquals("https://x/a.msi", parsed.installer()?.downloadUrl)
    }
}
