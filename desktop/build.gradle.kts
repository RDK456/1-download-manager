import java.net.URI
import java.util.zip.ZipFile
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.jetbrains.compose)
    // By id: the version comes from the root classpath, where the Android module
    // already pins it, so both builds compile with the same Compose compiler.
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Shared with the Android APK so the two never drift apart.
val appVersion: String = (project.findProperty("appVersion") as String?) ?: "1.0.0"

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)

    // Torrrents reuse the same engine as Android, with the Windows native library
    // instead of the Android ABIs. The JVM artifact itself is identical.
    implementation("org.libtorrent4j:libtorrent4j:2.1.0-39")
    implementation("org.libtorrent4j:libtorrent4j-windows:2.1.0-39")

    testImplementation(libs.junit)
}

/**
 * Fetches the standalone yt-dlp and ffmpeg executables and stages them in
 * `build/ytbin`, which is copied into the app's `lib` folder by the packaging
 * task. They are downloaded rather than committed so the repository stays small,
 * and the build fails loudly if a fetch breaks rather than shipping a build with
 * YouTube silently missing.
 */
val ytBinDir = layout.buildDirectory.dir("ytbin")

/**
 * Downloads a URL to a file.
 *
 * Uses curl.exe rather than java.net: GitHub redirects release assets to a
 * different host and HttpURLConnection times out on that redirect from this
 * network, while curl's -L follows it. curl ships with Windows 10 1803+, and the
 * JVM is only a fallback for unusual setups.
 */
fun downloadTo(url: String, destination: File) {
    destination.parentFile.mkdirs()
    val partial = File(destination.parentFile, destination.name + ".part")
    partial.delete()
    val curl = File("C:/Windows/System32/curl.exe")
    val viaCurl = curl.isFile && runCatching {
        val process = ProcessBuilder(
            curl.absolutePath, "-sSL", "--fail", "--retry", "3",
            "--connect-timeout", "30", "--max-time", "900", "-o", partial.absolutePath, url
        ).redirectErrorStream(true).start()
        process.inputStream.readBytes()
        process.waitFor() == 0
    }.getOrDefault(false)

    if (!viaCurl) {
        logger.lifecycle("curl unavailable or failed; falling back to the JVM")
        partial.outputStream().use { sink ->
            URI(url).toURL().openStream().use { it.copyTo(sink) }
        }
    }
    // Only replace a previously good copy once the new one is fully written, so an
    // interrupted download cannot leave a truncated executable behind.
    require(partial.isFile && partial.length() > 0L) { "Download of $url produced nothing" }
    destination.delete()
    require(partial.renameTo(destination)) { "Could not move the download into place" }
}

val fetchYtDlp by tasks.registering {
    description = "Downloads the standalone yt-dlp executable for Windows."
    group = "build setup"
    val outputDir = ytBinDir
    val target = "yt-dlp.exe"
    outputs.file(outputDir.map { it.file(target) })
    doLast {
        val dir = outputDir.get().asFile
        dir.mkdirs()
        val out = File(dir, target)
        if (out.isFile && out.length() > 1_000_000L) return@doLast

        // The "latest/download" alias also redirects unreliably, so the tag is
        // resolved through the API and the asset is fetched from a pinned URL.
        val api = URI("https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest")
            .toURL()
            .openConnection()
            .apply {
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "1-download-manager-build")
                connectTimeout = 20_000
                readTimeout = 20_000
            }
        val tag = api.getInputStream().bufferedReader().use { reader ->
            Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").find(reader.readText())?.groupValues?.get(1)
        }
        require(!tag.isNullOrBlank()) { "Could not resolve the latest yt-dlp release" }

        logger.lifecycle("Fetching yt-dlp $tag")
        downloadTo("https://github.com/yt-dlp/yt-dlp/releases/download/$tag/$target", out)
        require(out.length() > 1_000_000L) { "$target looks truncated (${out.length()} bytes)" }
        logger.lifecycle("yt-dlp $tag staged: ${out.length()} bytes")
    }
}

val fetchFfmpeg by tasks.registering {
    description = "Downloads a static ffmpeg build for Windows."
    group = "build setup"
    val outputDir = ytBinDir
    val target = "ffmpeg.exe"
    val url = "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip"
    val workDir = layout.buildDirectory.dir("ffmpeg-zip")
    outputs.file(outputDir.map { it.file(target) })
    doLast {
        val dir = outputDir.get().asFile
        dir.mkdirs()
        val out = File(dir, target)
        if (out.isFile && out.length() > 1_000_000L) return@doLast

        val zip = File(workDir.get().asFile, "ffmpeg.zip")
        logger.lifecycle("Fetching ffmpeg from gyan.dev")
        downloadTo(url, zip)

        var found = false
        ZipFile(zip).use { zf ->
            for (entry in zf.entries()) {
                if (!entry.isDirectory && entry.name.endsWith("ffmpeg.exe")) {
                    zf.getInputStream(entry).use { input ->
                        out.outputStream().use { input.copyTo(it) }
                    }
                    found = true
                    break
                }
            }
        }
        require(found && out.length() > 1_000_000L) { "ffmpeg.exe was not found in the archive" }
        logger.lifecycle("ffmpeg staged: ${out.length()} bytes")
    }
}

compose.desktop {
    application {
        mainClass = "com.downloadhub.desktop.MainKt"


        nativeDistributions {
            // The user asked for a standard installer, so Msi is the primary target.
            targetFormats(TargetFormat.Msi, TargetFormat.Deb)
            packageName = "1DownloadManager"
            packageVersion = appVersion
            description = "All-in-one download manager for HTTP, YouTube and torrents"
            vendor = "1 download manager"
            windows {
                menu = true
                perUserInstall = true
                // A stable upgrade path: replacing an install keeps settings because
                // they live in the user profile, not the install directory.
                upgradeUuid = "6f2c9a54-2f1b-4a7d-9b3e-1c8d5e7a4f20"
            }
        }
    }
}

/**
 * Copies the staged executables into the runtime resources so they land in the
 * packaged app's `lib` folder. Must match processResources' own output dir, or the
 * installer ships without them and YouTube silently fails.
 */
val stageYtBin by tasks.registering(Copy::class) {
    description = "Stages yt-dlp and ffmpeg into the runtime resources."
    group = "build setup"
    dependsOn(fetchYtDlp, fetchFfmpeg)
    from(ytBinDir)
    into(layout.buildDirectory.dir("resources/main/lib"))
}

tasks.named("processResources") { dependsOn(stageYtBin) }