package com.downloadhub.desktop

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.serialization.Serializable

/** One file of an installed release, as the release's file list describes it. */
@Serializable
data class UpdateFile(val path: String, val sha256: String, val size: Long = 0L)

/** The delta zip published beside a release: the files that changed since [from]. */
@Serializable
data class UpdateDelta(val asset: String, val from: String = "", val paths: List<String> = emptyList())

/** A release's `-files.json`: every file of the installed app, and the delta if there is one. */
@Serializable
data class UpdateManifest(val version: String, val files: List<UpdateFile>, val delta: UpdateDelta? = null)

/**
 * Updates by replacing only the files that changed, instead of the whole installer.
 *
 * A release lists every file of the app with its SHA-256, and publishes a zip of the files
 * that differ from the release before. The installed app hashes its own files against the
 * new list; when every file that differs is in that zip, it downloads the zip alone - a few
 * megabytes - and swaps those files in after it exits. Anything else (a version further
 * back, a modified install, a missing asset) falls back to the full installer.
 */
object DeltaUpdate {
    fun manifestAsset(release: GithubRelease): GithubAsset? =
        release.assets.firstOrNull { it.name.endsWith("-files.json", ignoreCase = true) }

    fun parse(text: String): UpdateManifest = DesktopJson.format.decodeFromString(UpdateManifest.serializer(), text)

    /** The files whose installed copy is missing or differs from the new release's. */
    fun changedFiles(manifest: UpdateManifest, root: File, hashOf: (File) -> String = ::sha256): List<UpdateFile> =
        manifest.files.filter { file ->
            val local = File(root, file.path)
            !local.isFile || local.length() != file.size || hashOf(local) != file.sha256
        }

    /** True when the delta holds every file that differs, so the zip alone completes the update. */
    fun covers(manifest: UpdateManifest, changed: List<UpdateFile>): Boolean {
        val delta = manifest.delta ?: return false
        val inZip = delta.paths.toSet()
        return changed.all { it.path in inZip }
    }

    /**
     * Jars in `app` that the new release does not have: the old builds of jars that are
     * renamed by their content hash whenever they change. Only jars, only in `app`, so
     * nothing a user put in the folder is ever touched.
     */
    fun staleJars(manifest: UpdateManifest, root: File): List<File> {
        val keep = manifest.files.map { it.path }.toSet()
        return File(root, "app").listFiles { f -> f.isFile && f.name.endsWith(".jar", ignoreCase = true) }
            .orEmpty()
            .filter { "app/${it.name}" !in keep }
    }

    /** Extracts [changed] from the delta zip into [into], checking each against its hash. */
    fun stage(zip: File, changed: List<UpdateFile>, into: File) {
        into.deleteRecursively()
        into.mkdirs()
        ZipFile(zip).use { archive ->
            changed.forEach { file ->
                val entry = archive.getEntry(file.path) ?: error("${file.path} is missing from the update")
                val target = File(into, file.path)
                target.parentFile.mkdirs()
                archive.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
                if (sha256(target) != file.sha256) error("${file.path} did not arrive intact")
            }
        }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Swaps the staged files in once this process has exited, then starts the app again.
     *
     * Every file it replaces is backed up first and all of them are put back if any copy
     * fails, so a locked file cannot leave the install half old and half new. The result
     * goes to [resultFile] in the installer's "<exit code>|<what>|<log>" form, so the next
     * start reports it the same way it reports an installer run.
     */
    fun launchApply(
        staging: File,
        files: List<String>,
        stale: List<File>,
        root: File,
        relaunch: File?,
        resultFile: File,
        log: File,
        waitPid: Long = ProcessHandle.current().pid()
    ): Result<Unit> = runCatching {
        fun q(value: String) = "'" + value.replace("'", "''") + "'"
        val backup = File(staging.parentFile, staging.name + "-backup")
        // One `+= ,@(...)` per file: an array literal holding a single inner array is
        // flattened by PowerShell, which would break a delta of exactly one file.
        val pairs = files.joinToString("\n") { path ->
            "\$files += ,@(${q(File(staging, path).absolutePath)}, ${q(File(root, path).absolutePath)}, ${q(File(backup, path).absolutePath)})"
        }
        val removals = stale.joinToString("\n") { "\$stale += ${q(it.absolutePath)}" }
        val script = File(staging.parentFile, "apply-delta.ps1")
        script.writeText(
            """
            |${'$'}ErrorActionPreference = 'Stop'
            |Wait-Process -Id $waitPid -ErrorAction SilentlyContinue
            |${'$'}files = @()
            |$pairs
            |${'$'}stale = @()
            |$removals
            |${'$'}done = @()
            |${'$'}code = 0
            |function Retry([scriptblock]${'$'}action) {
            |  for (${'$'}i = 0; ${'$'}i -lt 20; ${'$'}i++) { try { & ${'$'}action; return } catch { Start-Sleep -Milliseconds 500 } }
            |  & ${'$'}action
            |}
            |try {
            |  foreach (${'$'}f in ${'$'}files) {
            |    if (Test-Path -LiteralPath ${'$'}f[1]) {
            |      New-Item -ItemType Directory -Force -Path (Split-Path ${'$'}f[2]) | Out-Null
            |      Copy-Item -LiteralPath ${'$'}f[1] -Destination ${'$'}f[2] -Force
            |    }
            |    New-Item -ItemType Directory -Force -Path (Split-Path ${'$'}f[1]) | Out-Null
            |    Retry { Copy-Item -LiteralPath ${'$'}f[0] -Destination ${'$'}f[1] -Force }
            |    ${'$'}done += ,${'$'}f
            |  }
            |  foreach (${'$'}s in ${'$'}stale) { Remove-Item -LiteralPath ${'$'}s -Force -ErrorAction SilentlyContinue }
            |} catch {
            |  ${'$'}code = 1
            |  ${'$'}_ | Out-File -LiteralPath ${q(log.absolutePath)} -Encoding utf8
            |  foreach (${'$'}f in ${'$'}done) {
            |    if (Test-Path -LiteralPath ${'$'}f[2]) { Copy-Item -LiteralPath ${'$'}f[2] -Destination ${'$'}f[1] -Force -ErrorAction SilentlyContinue }
            |  }
            |}
            |Set-Content -LiteralPath ${q(resultFile.absolutePath)} -Value ("" + ${'$'}code + '|delta|' + ${q(log.absolutePath)})
            |${relaunch?.let { "Start-Process -FilePath ${q(it.absolutePath)}" } ?: ""}
            |Remove-Item -LiteralPath ${'$'}MyInvocation.MyCommand.Path -ErrorAction SilentlyContinue
            """.trimMargin()
        )
        ProcessBuilder(
            "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
            "-WindowStyle", "Hidden", "-File", script.absolutePath
        ).redirectErrorStream(true).start()
        Unit
    }
}
