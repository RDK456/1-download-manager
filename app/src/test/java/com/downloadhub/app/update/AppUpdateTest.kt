package com.downloadhub.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateTest {
    @Test
    fun comparesVersionsNumerically() {
        assertTrue(compareVersions("1.0.1", "1.0.0") > 0)
        assertTrue(compareVersions("1.10.0", "1.9.9") > 0)
        assertTrue(compareVersions("2.0.0", "1.99.99") > 0)
        assertTrue(compareVersions("1.0.0", "1.0.1") < 0)
        assertEquals(0, compareVersions("1.0.0", "v1.0.0"))
        assertEquals(0, compareVersions("1.2", "1.2.0"))
        assertEquals(0, compareVersions("1.2.0+build7", "1.2.0"))
    }

    @Test
    fun releaseKnowsIfItIsNewer() {
        val release = ReleaseInfo(
            tag = "v1.2.0",
            name = "1 download manager v1.2.0",
            notes = "notes",
            pageUrl = "https://github.com/RDK456/1-download-manager/releases/tag/v1.2.0",
            publishedAt = "2026-09-26T00:00:00Z",
            assets = emptyList()
        )
        assertEquals("1.2.0", release.version)
        assertTrue(release.isNewerThan("1.1.0"))
        assertFalse(release.isNewerThan("1.2.0"))
        assertFalse(release.isNewerThan("1.3.0"))
    }

    @Test
    fun skippedVersionIsHonoured() {
        val release = release("v1.2.0")
        assertFalse(release.isSkipped(null))
        assertFalse(release.isSkipped(""))
        assertTrue(release.isSkipped("1.2.0"))
        assertTrue(release.isSkipped("1.3.0"))
        assertFalse(release.isSkipped("1.1.0"))
    }

    @Test
    fun picksApkAssetFirst() {
        val release = release(
            tag = "v1.2.0",
            assets = listOf(
                ReleaseAsset("checksums.txt", "https://example.com/checksums.txt", 12),
                ReleaseAsset("DownloadHub-debug.apk", "https://example.com/app.apk", 1400)
            )
        )
        assertEquals("DownloadHub-debug.apk", release.installAsset()?.name)
        assertNull(release("v1.2.0", emptyList()).installAsset())
    }

    /** The APK built for the phone's processor, half the size; the universal one otherwise. */
    @Test
    fun picksTheApkForThisProcessor() {
        val release = release(
            tag = "v1.5.6",
            assets = listOf(
                ReleaseAsset("1-download-manager-1.5.6.apk", "https://example.com/u.apk", 132),
                ReleaseAsset("1-download-manager-1.5.6-arm64-v8a.apk", "https://example.com/a.apk", 70),
                ReleaseAsset("1-download-manager-1.5.6-x86_64.apk", "https://example.com/x.apk", 72)
            )
        )
        assertEquals("1-download-manager-1.5.6-arm64-v8a.apk", release.installAsset(listOf("arm64-v8a", "armeabi-v7a"))?.name)
        assertEquals("1-download-manager-1.5.6-x86_64.apk", release.installAsset(listOf("x86_64", "x86"))?.name)
        assertEquals("1-download-manager-1.5.6.apk", release.installAsset(listOf("armeabi-v7a"))?.name)
        assertEquals("1-download-manager-1.5.6.apk", release.installAsset(emptyList())?.name)
    }

    @Test
    fun parsesGitHubReleasePayload() {
        val json = """
            {
              "tag_name": "v1.3.0",
              "name": "1 download manager v1.3.0",
              "body": "Added breadcrumbs",
              "html_url": "https://github.com/RDK456/1-download-manager/releases/tag/v1.3.0",
              "published_at": "2026-09-26T10:00:00Z",
              "prerelease": false,
              "assets": [
                {
                  "name": "DownloadHub-debug.apk",
                  "browser_download_url": "https://github.com/RDK456/1-download-manager/releases/download/v1.3.0/DownloadHub-debug.apk",
                  "size": 140113422,
                  "content_type": "application/vnd.android.package-archive"
                }
              ]
            }
        """.trimIndent()

        val parsed = parseRelease(json)
        assertEquals("v1.3.0", parsed.tag)
        assertEquals("1.3.0", parsed.version)
        assertEquals("Added breadcrumbs", parsed.notes)
        assertFalse(parsed.prerelease)
        assertEquals(1, parsed.assets.size)
        val asset = parsed.assets.first()
        assertEquals("DownloadHub-debug.apk", asset.name)
        assertEquals(140113422L, asset.size)
        assertNotNull(parsed.installAsset())
        assertTrue(parsed.isNewerThan("1.2.0"))
    }

    @Test
    fun parsesReleaseWithoutAssets() {
        val parsed = parseRelease("""{"tag_name":"v1.0.0","assets":[]}""")
        assertEquals("1.0.0", parsed.version)
        assertTrue(parsed.assets.isEmpty())
    }

    @Test
    fun comparesDateBasedYtDlpVersions() {
        assertTrue(compareVersions("2026.08.19", "2025.11.12") > 0)
        assertTrue(compareVersions("2026.08.19", "2026.08.19") == 0)
        assertTrue(compareVersions("2026.8.19", "2026.08.19") == 0)
        assertTrue(compareVersions("2026.08.19", "2026.08.20") < 0)
    }

    @Test
    fun ytDlpUpdateOnlyOfferedWhenNewerVersionExists() {
        val installed = "2026.08.19"
        val latest = "2026.09.01"
        assertTrue(YtDlpUpdateState.Available(installed, latest).hasUpdate)
        assertFalse(YtDlpUpdateState.UpToDate(installed).hasUpdate)
        assertFalse(YtDlpUpdateState.Idle.hasUpdate)
        assertFalse(YtDlpUpdateState.Checking.hasUpdate)
        assertFalse(YtDlpUpdateState.Failed("offline").hasUpdate)

        val older = "2025.11.12"
        assertFalse(compareVersions(older, installed) > 0)
    }

    private fun release(tag: String, assets: List<ReleaseAsset> = emptyList()) = ReleaseInfo(
        tag = tag,
        name = "release $tag",
        notes = "",
        pageUrl = "https://example.com",
        publishedAt = "",
        assets = assets
    )
}
