import java.net.URI
import java.util.concurrent.TimeUnit
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream


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

/** gyan.dev's static Windows build of ffmpeg. The same source the runtime fetcher uses. */
val FFMPEG_URL = "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip"

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)

    // The libtorrent4j JVM artifact carries no native code, so :core stays
    // platform neutral and each app adds the native library it needs.
    implementation(libs.libtorrent4j)
    implementation("org.libtorrent4j:libtorrent4j-windows:2.1.0-39")
    // Colours the native title bar to match the theme (DwmSetWindowAttribute).
    implementation("net.java.dev.jna:jna:5.6.0")

    testImplementation(libs.junit)
}

val ytBinDir = layout.buildDirectory.dir("ytbin")

/**
 * Downloads a URL to a file.
 *
 * Uses curl.exe rather than java.net: GitHub redirects release assets to a
 * different host and HttpURLConnection times out on that redirect from some
 * networks, while curl's -L follows it. curl ships with Windows 10 1803+, and the
 * JVM is only a fallback.
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


/**
 * Downloads the standalone yt-dlp executable for Windows.
 *
 * ffmpeg is shipped as well, but as a single file - see [fetchFfmpeg] for why that
 * distinction is the whole difficulty. Android still fetches it on first use, because
 * an APK is a different thing to be large.
 */
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

        // The "latest/download" alias redirects unreliably, so the tag is resolved
        // through the API and the asset is fetched from a pinned URL.
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
/**
 * Fetches ffmpeg for Windows, and keeps only the one executable.
 *
 * ffmpeg was bundled once and removed, and the reason is written down here because it is
 * the whole difficulty with this task: the earlier build shipped the *extracted tree* -
 * ffmpeg, ffprobe, presets, headers, documentation, roughly a thousand files - and a
 * thousand files is what a security agent fights with, so the install died with "Failed to
 * launch JVM" and nothing in the log said why. Size was not the problem; file count was,
 * and cutting the download from 150 MB to 78 MB did not help.
 *
 * So this ships **one file**. The archive is 109 MB and the executable inside it is about
 * 80 MB, and what actually lands in the package is a single `ffmpeg.exe` next to the
 * single `yt-dlp.exe` that is already there. That keeps the property that made the first
 * attempt work - YouTube works on first run, with nothing to fetch and nothing to wait for
 * - without the property that made it fail.
 *
 * Still worth knowing: ffmpeg is a third-party binary the project does not build, so it is
 * fetched from gyan.dev at build time and pinned by nothing. A build that must be
 * reproducible should hash what it fetched, and this does not.
 */
val fetchFfmpeg by tasks.registering {
    description = "Downloads ffmpeg for Windows and keeps only the executable."
    group = "build setup"
    val outputDir = ytBinDir
    val target = "ffmpeg.exe"
    outputs.file(outputDir.map { it.file(target) })
    doLast {
        val dir = outputDir.get().asFile
        dir.mkdirs()
        val out = File(dir, target)
        if (out.isFile && out.length() > 1_000_000L) return@doLast

        val archive = File(dir, "ffmpeg-download.zip")
        try {
            logger.lifecycle("Fetching ffmpeg (109 MB, one build step)")
            downloadTo(FFMPEG_URL, archive)
            require(archive.isFile && archive.length() > 1_000_000L) {
                "the ffmpeg archive looks truncated (${archive.length()} bytes)"
            }
            // The archive's top folder is named after the build, so it is matched rather
            // than hard-coded - which is the mistake that made the runtime unpacker
            // silently find nothing.
            ZipFile(archive).use { zip ->
                val entry = zip.stream().toList().firstOrNull { candidate ->
                    !candidate.isDirectory &&
                        candidate.name.replace('\\', '/').substringAfterLast('/') == target &&
                        candidate.name.replace('\\', '/').contains("/bin/")
                } ?: error("no $target in the ffmpeg archive")
                val partial = File(dir, "$target.part")
                partial.delete()
                zip.getInputStream(entry).use { input ->
                    partial.outputStream().use { sink -> input.copyTo(sink) }
                }
                if (partial.length() < 1_000_000L) {
                    partial.delete()
                    error("the extracted $target looks truncated (${partial.length()} bytes)")
                }
                out.delete()
                if (!partial.renameTo(out)) {
                    partial.delete()
                    error("could not move $target into place")
                }
            }
            logger.lifecycle("ffmpeg staged: ${out.length()} bytes, one file")
        } finally {
            archive.delete()
        }
    }
}


/**
 * Copies the bundled executables into the runtime resources so they land in the
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

/**
 * Ships the browser extension as a runtime resource.
 *
 * Chrome and Edge will only load an extension from a folder the user picks, and a
 * store listing would mean publishing it somewhere first, so the app carries its
 * own copy and unpacks it on first run. It travels as a classpath resource for the
 * same reason the bundled yt-dlp does: no packaging hook, and it works from the
 * MSI, the portable zip and the IDE alike.
 */
val stageExtension by tasks.registering(Copy::class) {
    description = "Ships the browser extensions as runtime resources."
    group = "build setup"
    from(layout.projectDirectory.dir("browser-extension"))
    into(layout.buildDirectory.dir("resources/main/browser-extension"))
}

tasks.named("processResources") { dependsOn(stageYtBin, stageExtension) }

/**
 * The extension as a zip, one per browser, for people who would rather not dig
 * the folder out of the installed app.
 *
 * Until now the only copy was unpacked into the installation and the user loaded
 * it from there, which is a folder path only they have and which changes when
 * they reinstall. A release asset is a URL anyone can be sent, and it is what
 * the store submission uploads from, so the same bytes do both jobs.
 *
 * A zip per browser rather than one, because they are different extensions: MV3
 * still has no Firefox service worker support, so Firefox needs background.scripts
 * and the Chromium build needs service_worker. Loading the wrong one gives a
 * user an extension that silently does nothing.
 */
val packageExtension by tasks.registering {
    description = "Zips each browser extension for release and store upload."
    group = "distribution"
    val sourceDir = layout.projectDirectory.dir("browser-extension")
    val outputDir = layout.buildDirectory.dir("extensions")
    val appVersion: String = (project.findProperty("appVersion") as String?) ?: "1.0.0"
    // Chrome Web Store and Firefox both reject a version with more than four
    // dot-separated numbers, and "1.4.25" is three - but "1.4.25-beta" is not a
    // version either, so the app version is used verbatim and only trimmed to the
    // four-number shape if a build ever gives it more.
    val extensionVersion = Regex("^\\d+(\\.\\d+){0,3}$")
        .let { if (it.matches(appVersion)) appVersion else appVersion.substringBefore('-') }
    // Declared as an input, or the task is UP-TO-DATE on a second run: the output
    // directory already exists and nothing it watches has changed, so no zip is
    // written for the new version and the release finds none and stops. The version
    // is the only thing that varies between runs, so it has to be one of the inputs.
    inputs.property("appVersion", appVersion)
    outputs.dir(outputDir)
    doLast {
        val from = sourceDir.asFile
        val into = outputDir.get().asFile
        into.mkdirs()
        // Exactly two files should live here: this version's Chrome zip and this
        // version's Firefox .xpi. Anything else is left over - an earlier version,
        // or the Firefox .zip from before it became an .xpi - and is deleted by
        // name rather than by version, because filtering on the version keeps the
        // stale file from the *same* version.
        val expected = setOf(
            "1-download-manager-extension-chrome-$extensionVersion.zip",
            "1-download-manager-extension-firefox-$extensionVersion.xpi"
        )
        into.listFiles()?.forEach { stale ->
            if (!expected.contains(stale.name)) stale.delete()
        }
        listOf("chromium" to "chrome", "firefox" to "firefox").forEach { (folder, label) ->
            val dir = File(from, folder)
            require(dir.isDirectory) { "browser-extension/$folder is missing" }
            // Read back and rewritten rather than copied, so the version in the
            // manifest is the app's. A store update needs a *higher* version than
            // the last upload, and a fixed 1.0.0 can never be updated once.
            val manifest = File(dir, "manifest.json")
            val text = manifest.readText()
            val stamped = Regex("(\"version\"\\s*:\\s*\")[^\"]+(\")")
                .replace(text) { "${it.groupValues[1]}$extensionVersion${it.groupValues[2]}" }
            val staged = File(into.parentFile, "staging-$label")
            staged.deleteRecursively()
            staged.mkdirs()
            dir.listFiles()?.forEach { file -> file.copyTo(File(staged, file.name), overwrite = true) }
            File(staged, "manifest.json").writeText(stamped)
            // Chromium gets a .zip, Firefox gets an .xpi. The bytes are identical -
            // an .xpi *is* a zip - but the extension matters: Firefox and Zen will
            // not accept a .zip from "Install Add-on From File", and asking someone
            // to rename the download is asking them to know that.
            val suffix = if (label == "firefox") "xpi" else "zip"
            val zip = File(into, "1-download-manager-extension-$label-$extensionVersion.$suffix")
            zip.delete()
            ZipOutputStream(zip.outputStream().buffered()).use { sink ->
                staged.walkTopDown().filter { file -> file.isFile }.forEach { file ->
                    sink.putNextEntry(ZipEntry(file.relativeTo(staged).invariantSeparatorsPath))
                    file.inputStream().use { input -> input.copyTo(sink) }
                    sink.closeEntry()
                }
            }
            staged.deleteRecursively()
            logger.lifecycle("Extension packaged: ${zip.name}")
        }
    }
}

/**
 * Puts the version in the jar manifest.
 *
 * The app reads its own version at runtime from `Package.getImplementationVersion()`,
 * which comes from here. Without it the app has always reported "1.0.0" - in the
 * window title, in the menu bar, and, worse, in the update check, which compares
 * the newest release against this number and so has always believed there was an
 * update available even when running the latest build.
 *
 * `packageVersion` on the distribution only names the .msi and the zip; it does not
 * reach the code, so the two could - and did - disagree.
 */
tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Implementation-Title" to "1 download manager",
            "Implementation-Version" to appVersion
        )
    }
}

/**
 * Post-processes the unpacked app: drops the startup check beside the launcher and
 * removes the runtime stubs that make "Failed to launch JVM" so common.
 *
 * These are two small things but they share one reason to be a single task: both
 * modify the directory that `createDistributable` produces and rewrites on every run,
 * so both must happen after it and before anything is packaged. Keeping them together
 * means there is one task to order, rather than two that can drift apart.
 */
// Resolved to plain Files here because `layout` is not in scope on a bare Task
// receiver, and reaching for it inside doLast does not compile. The paths are known
// at configuration time; only their contents change between runs.
val packagedAppImage = file("build/compose/binaries/main/app/1DownloadManager")
val packagedRuntimeBin = File(packagedAppImage, "runtime/bin")
val troubleshootSource = file("dist-tools")

val prepareDistributable by tasks.registering {
    description = "Prepares the unpacked app for packaging: startup check, no API set stubs."
    group = "distribution"
    // By name, resolved when the task graph is built rather than at configuration
    // time: a Compose distribution task does not exist yet this early.
    dependsOn("createDistributable")
    // createDistributable rewrites the directory every time, so this can never be
    // considered done.
    outputs.upToDateWhen { false }
    doLast {
        val appImage: File = packagedAppImage
        if (!appImage.isDirectory) {
            error("No packaged app at $appImage; run createDistributable first.")
        }

        // --- the startup check ------------------------------------------------
        // "Failed to launch JVM" is all the jpackage launcher can say, whether the
        // cause is a half-extracted zip, a quarantined runtime DLL, or a network
        // drive. The app cannot report this itself - the failure happens before any
        // of its code runs - so the answer has to travel with the package.
        val shipped: List<File> = troubleshootSource
            .listFiles { f: File -> f.isFile }
            ?.sortedBy { it.name }
            ?: emptyList()
        if (shipped.isEmpty()) error("No files in dist-tools; the package would ship without a startup check.")
        shipped.forEach { it.copyTo(File(appImage, it.name), overwrite = true) }
        logger.lifecycle("Shipped ${shipped.size} startup-check file(s) beside the launcher.")

        // --- a real java.exe, and the jli.dll that goes with it ---------------
        // jlink strips the launchers, so the packaged app has no java.exe and the
        // jpackage stub is the only way into the JVM. When the stub cannot create
        // the JVM it can say nothing except "Failed to launch JVM", which is why
        // the error is so useless: it does not say which file was blocked or which
        // path was denied.
        //
        // java.exe is 50 KB and the module image and the other runtime libraries are
        // already in the image. Adding java.exe back gives the package a second way in
        // that reports the actual error, and that is what Start 1DownloadManager.bat
        // uses. Verified by launching the packaged app through it: same window, same
        // behaviour.
        //
        // jli.dll has to be copied from the same JDK, every build, and this is not
        // belt-and-braces. The 1.4.30 zip shipped a jli.dll that was 89,720 bytes of
        // x86-64 machine code with no PE header at all: the right length, so every
        // file-count and size check passed, and every version of the file was
        // 89,720 bytes, so a size comparison would have passed too. The packaged
        // java.exe died instantly with 0xC000012F, STATUS_INVALID_IMAGE_FORMAT - the
        // Windows loader refusing a file that is not a DLL.
        //
        // The app itself was fine. The launcher loads runtime/bin/server/jvm.dll
        // directly and never touches jli.dll, so what was broken was the very tool
        // added to diagnose startup failures, and the only symptom was a jli.dll error
        // from the script a person runs when something else has already gone wrong.
        // jli.dll is also the file security software most often flags - it is an
        // unsigned launcher DLL - and a flagged file can be rewritten in place rather
        // than removed, which is exactly what the bytes looked like.
        //
        // So neither file is trusted. The build JDK's copies are written over them
        // every build, and both are checked for a PE header afterwards. A build that
        // cannot get a working pair stops: shipping this package without it is worse
        // than not shipping it, because the package's diagnostic cannot run.
        val runtimeBinDir = packagedRuntimeBin
        val jdkBins = listOfNotNull(
            System.getProperty("jdk.home"),
            System.getProperty("java.home")
        ).map { File(it, "bin") }.filter { it.isDirectory }

        fun peHeaderIsIntact(file: File): Boolean {
            if (!file.isFile || file.length() < 2L) return false
            val head = file.inputStream().use { input -> ByteArray(2).also { input.read(it) } }
            return head[0] == 0x4D.toByte() && head[1] == 0x5A.toByte()
        }

        listOf("java.exe", "jli.dll").forEach { name ->
            val target = File(runtimeBinDir, name)
            val source = jdkBins.map { File(it, name) }.firstOrNull { it.isFile && peHeaderIsIntact(it) }
                ?: error(
                    "No usable $name in the build JDK; looked in ${jdkBins.joinToString()}. " +
                        "Without it the package cannot report a real startup error, and a " +
                        "java.exe without a matching jli.dll dies with STATUS_INVALID_IMAGE_FORMAT."
                )
            val alreadyGood = target.isFile &&
                peHeaderIsIntact(target) &&
                target.length() == source.length() &&
                target.readBytes().contentEquals(source.readBytes())
            if (!alreadyGood) {
                val hadHeader = target.isFile && peHeaderIsIntact(target)
                val why = when {
                    !target.isFile -> "it was missing"
                    !hadHeader -> "it had no PE header - ${target.length()} bytes that were not a DLL"
                    else -> "it did not match the build JDK"
                }
                source.copyTo(target, overwrite = true)
                logger.lifecycle(
                    "Replaced runtime\\bin\\$name ($why); the package now carries the build " +
                        "JDK's copy, so java.exe and jli.dll are the same build."
                )
            }
        }

        // --- the API set stubs ------------------------------------------------
        // These 45 files are the reason "Failed to launch JVM" was so hard to shake.
        // The packaged app has no java.exe at all: the launcher loads
        // runtime/bin/server/jvm.dll directly, and jvm.dll resolves its API set
        // imports through these local stub DLLs. Block or quarantine any one and the
        // JVM cannot initialise - the exact error, with no other symptom and nothing
        // in the app's own code to point at.
        //
        // They are also the most attractive thing in the package for a security
        // agent: 45 unsigned, near-empty DLLs named like operating-system
        // components. Cutting the download from 150 MB to 78 MB did not fix it,
        // because these files were never about size.
        //
        // On Windows 10 and later they are not needed - the loader resolves API sets
        // from the OS schema rather than by opening these files. Verified by running
        // the packaged app with all 45 removed: it starts normally.
        val runtimeBin: File = packagedRuntimeBin
        if (!runtimeBin.isDirectory) error("No packaged runtime at $runtimeBin.")

        val stubs: List<File> = runtimeBin.listFiles { f: File -> f.isFile && f.name.startsWith("api-ms-") }
            ?.sortedBy { it.name }
            ?: emptyList()
        if (stubs.isNotEmpty()) {
            // Anything this large is a real component, not a forwarder. If a future
            // JDK ships one that happens to be named api-ms-something, this stops the
            // build instead of shipping a JVM that cannot start.
            val suspicious = stubs.filter { it.length() > 512L * 1024L }
            if (suspicious.isNotEmpty()) {
                error(
                    "Refusing to strip, these are too large to be forwarder stubs: " +
                        suspicious.joinToString { "${it.name} (${it.length()} bytes)" }
                )
            }
            val bytes = stubs.sumOf { it.length() }
            val notRemoved = stubs.filterNot { it.delete() }
            if (notRemoved.isNotEmpty()) {
                error("Could not remove ${notRemoved.joinToString { it.name }}; the package is probably locked.")
            }
            logger.lifecycle("Stripped ${stubs.size} redundant API set stubs (${bytes / 1024} KB).")
        } else {
            logger.lifecycle("No API set stubs in the packaged runtime.")
        }

        // Removing a stub is safe; accidentally removing the JVM is not.
        val required = listOf("server/jvm.dll", "java.dll", "ucrtbase.dll", "vcruntime140.dll")
        val missing = required.filterNot { File(runtimeBin, it).isFile }
        if (missing.isNotEmpty()) {
            error("The runtime is missing ${missing.joinToString()}; the JVM cannot start without it.")
        }

        // --- and prove the launcher actually runs -------------------------------
        // Everything above compares files. Comparing files says the package carries the
        // two we meant to ship; it does not say the package can start. Those are not the
        // same claim, and 1.4.30 is the proof: its jli.dll was 89,720 bytes - exactly
        // the right length, so the size check, the file-count check and a size
        // comparison against the JDK all passed - and 89,720 bytes of machine code with
        // no PE header. Nothing that read the file could tell.
        //
        // So the launcher is run. `java.exe -version` is the smallest thing that has to
        // work for the package to be useful, and it fails in about a second.
        //
        // This is the whole point of shipping java.exe at all. It is not there so
        // someone can run a program; it is there so that when the jpackage stub says
        // "Failed to launch JVM" and nothing else, there is a second way in that says
        // what actually happened. A diagnostic that cannot run is worse than no
        // diagnostic, because it turns "the package is fine, your machine is odd" into
        // a fresh mystery. So the build proves it runs before the package leaves.
        //
        // Output goes to a file rather than a pipe, because a pipe nobody is reading
        // fills and deadlocks the child - which would look like a hang rather than a
        // failure, and the timeout below would then report the wrong thing.
        val launcher = File(runtimeBin, "java.exe")
        val probeLog = File.createTempFile("dlm-launcher-probe", ".txt")
        try {
            val probe = ProcessBuilder(listOf(launcher.absolutePath, "-version"))
                .directory(appImage)
                .redirectErrorStream(true)
                .redirectOutput(probeLog)
                .start()
            val finished = probe.waitFor(60L, TimeUnit.SECONDS)
            if (!finished) {
                probe.destroyForcibly()
                error(
                    "The packaged java.exe did not exit within 60 seconds. It is hanging " +
                        "rather than failing, which means the package would hang for " +
                        "anyone who ran the startup check. Output so far:\n" +
                        probeLog.readText().trim()
                )
            }
            val probeOutput = probeLog.readText().trim()
            if (probe.exitValue() != 0) {
                val hex = "0x" + Integer.toHexString(probe.exitValue()).uppercase()
                error(
                    "The packaged java.exe exited ${probe.exitValue()} ($hex). The package " +
                        "cannot report a real startup error, which is the only reason it " +
                        "carries a launcher at all, and jli.dll is the file most likely " +
                        "to be the cause - it is unsigned and security software rewrites " +
                        "it in place rather than removing it. Output:\n$probeOutput"
                )
            }
            val firstLine = probeOutput.lineSequence().firstOrNull { it.isNotBlank() } ?: "(no output)"
            logger.lifecycle("The packaged launcher runs: $firstLine")
        } finally {
            probeLog.delete()
        }
    }
}


/**
 * Zips the unpacked app so it can be run without installing anything.
 *
 * Windows Installer failed on a machine whose security agent locks files the
 * installer is writing ("Could not set file security ... Error: 5"), and there is
 * no fix for that from inside the installer. Unzipping and running the exe needs no
 * elevation, no registry, and no Windows Installer at all, so it works where the MSI
 * cannot.
 */
val packageZip by tasks.registering(Zip::class) {
    description = "Builds a portable, install-free zip of the Windows app."
    group = "distribution"
    // By name, resolved when the task graph is built rather than at configuration
    // time: a Compose distribution task does not exist yet this early, and naming it
    // eagerly fails the whole build with "cannot reference task by name".
    dependsOn("createDistributable", "prepareDistributable")
    from(layout.buildDirectory.dir("compose/binaries/main/app/1DownloadManager"))
    // The folder is already named 1DownloadManager; wrapping it again produced
    // 1DownloadManager/1DownloadManager/.
    archiveFileName.set("1DownloadManager-$appVersion-portable.zip")
    isPreserveFileTimestamps = false
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

compose.desktop {
    application {
        mainClass = "com.downloadhub.desktop.MainKt"

        nativeDistributions {
            // Msi is the installer; the portable zip above is the fallback for
            // machines where an installer cannot run at all.
            targetFormats(TargetFormat.Msi, TargetFormat.Deb)
            packageName = "1DownloadManager"
            packageVersion = appVersion
            description = "All-in-one download manager for HTTP, YouTube and torrents"
            vendor = "1 download manager"
            windows {
                menu = true
                menuGroup = "1 download manager"
                shortcut = true
                // Per user: installs without an admin prompt, which is also what lets the
                // updater run msiexec /qn with nothing on screen. Silent install by hand:
                //   msiexec /i 1-download-manager-x.y.z.msi /qn
                perUserInstall = true
                // Without this the exe, the Start Menu entry and the taskbar all get
                // jpackage's default Java cup. The artwork is the same as the Android
                // launcher icon; see desktop/dist-tools/Generate-AppIcon.ps1.
                iconFile.set(file("dist-tools/app-icon.ico"))
                // A stable upgrade code, so replacing an install keeps the entry in
                // Apps and features rather than leaving a second, stale copy.
                upgradeUuid = "6f2c9a54-2f1b-4a7d-9b3e-1c8d5e7a4f20"
            }
        }
    }
}
