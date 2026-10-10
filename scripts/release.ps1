<#
.SYNOPSIS
    Bumps the app version, builds, pushes the whole tree, and publishes a new
    GitHub release with the APK attached.

.DESCRIPTION
    Every run performs the same sequence so releases stay reproducible:
      1. read versionName / versionCode from app/build.gradle.kts
      2. bump them (patch by default, -Minor / -Major for larger jumps)
      3. run unit tests, lint and the build
      4. copy the APK to the repository root as the release asset
      5. commit the whole tree, tag it, and push
      6. create the GitHub release (or update it if the tag already exists)

    When keystore/keystore.properties exists the signed release APK is built and
    published (the default). With -DebugApk the debug-signed APK is published
    instead, which is only useful for quick internal testing.

    The app's in-app updater reads the newest published release from GitHub, so a
    release created here is exactly what existing installs offer as an update.

.PARAMETER Bump
    Which part of the version to increase: Patch (default), Minor, or Major.

.PARAMETER SkipTests
    Skip the test/lint/build step (use only when you already validated).

.PARAMETER DebugApk
    Publish the debug-signed APK instead of the signed release APK.

.PARAMETER Draft
    Create the release as a draft so it is not offered as an update yet.

.PARAMETER Notes
    Release notes body. Defaults to an auto-generated summary of the commits
    since the previous tag.

.EXAMPLE
    .\scripts\release.ps1
    .\scripts\release.ps1 -Bump Minor
    .\scripts\release.ps1 -DebugApk
#>
[CmdletBinding()]
# Returns whichever of two dotted versions is higher, comparing numerically per part
# rather than as text. "1.4.10" is above "1.4.9"; a string compare says otherwise, and
# the result would be a release numbered below one already published.
param(
    [ValidateSet('Patch', 'Minor', 'Major')]
    [string]$Bump = 'Patch',
    [switch]$SkipTests,
    [switch]$DebugApk,
    [switch]$Draft,
    # Publishes only the Windows assets and leaves the Android version alone.
    # A desktop-only change produces a byte-identical APK, and shipping it again
    # just makes people re-download 126 MB they already have.
    [switch]$DesktopOnly,
    # Publishes only the Android assets (the APKs) and leaves the Windows version, the
    # installer, the portable zip, the update delta and the browser extensions alone.
    # An Android-only change should not rebuild and re-upload 130 MB of Windows files.
    [switch]$AndroidOnly,
    # Release notes as a string, or as a file.
    #
    # Prefer -NotesFile. Passing prose through -Notes on a command line means the
    # shell gets to interpret it, and any double quote in the text - which release
    # notes are full of, because they quote error messages - ends the argument and
    # the script fails with a baffling "A positional parameter cannot be found".
    [string]$Notes,
    [string]$NotesFile
)

if ($NotesFile) {
    if (-not (Test-Path $NotesFile)) { throw "Notes file not found: $NotesFile" }
    # -Encoding UTF8, always. Windows PowerShell's default for Get-Content is the
    # ANSI code page, so a UTF-8 file with no BOM - which is what an editor or an
    # agent writes - comes back as mojibake: a bullet becomes "â€¢" and an arrow
    # becomes "â†’". That text is then published to the release verbatim, and the
    # in-app updater shows it to users exactly as corrupted.
    $Notes = Get-Content $NotesFile -Raw -Encoding UTF8
}

$ErrorActionPreference = 'Stop'
if ($DesktopOnly -and $AndroidOnly) { throw '-DesktopOnly and -AndroidOnly cannot be combined; run without either for a full release.' }

function Compare-VersionNumbers([string]$a, [string]$b) {
    $ap = @($a -split '\.' | ForEach-Object { [int]$_ })
    $bp = @($b -split '\.' | ForEach-Object { [int]$_ })
    for ($i = 0; $i -lt [Math]::Max($ap.Count, $bp.Count); $i++) {
        $av = if ($i -lt $ap.Count) { $ap[$i] } else { 0 }
        $bv = if ($i -lt $bp.Count) { $bp[$i] } else { 0 }
        if ($av -ne $bv) { return $(if ($av -gt $bv) { $a } else { $b }) }
    }
    return $a
}
Set-StrictMode -Version Latest

$RepoRoot = Split-Path -Parent $PSScriptRoot
$Repository = 'RDK456/1-download-manager'
$GradleFile = Join-Path $RepoRoot 'app\build.gradle.kts'
$DebugApkBuilt = Join-Path $RepoRoot 'app\build\outputs\apk\debug\app-universal-debug.apk'
$ReleaseApkBuilt = Join-Path $RepoRoot 'app\build\outputs\apk\release\app-universal-release.apk'
$KeystoreProperties = Join-Path $RepoRoot 'keystore\keystore.properties'

# --- locate toolchain --------------------------------------------------------
$ToolsRoot = Join-Path $env:LOCALAPPDATA 'Temp\opencode\tools'
foreach ($dir in @((Join-Path $ToolsRoot 'mingit\cmd'), (Join-Path $ToolsRoot 'gh\bin'))) {
    if (Test-Path $dir) { $env:PATH = "$dir;$env:PATH" }
}
$env:GIT_TERMINAL_PROMPT = '0'

function Resolve-Tool([string]$name) {
    $command = Get-Command $name -ErrorAction SilentlyContinue
    if (-not $command) { throw "'$name' was not found on PATH." }
    return $command.Source
}

$git = Resolve-Tool 'git'
$gh = Resolve-Tool 'gh'

# A signed release is the default; -DebugApk opts out.
$useDebug = $DebugApk.IsPresent
$hasKeystore = Test-Path $KeystoreProperties
if (-not $useDebug -and -not $hasKeystore) {
    Write-Warning 'No keystore\keystore.properties found - falling back to the debug-signed APK.'
    $useDebug = $true
}

Push-Location $RepoRoot

try {
    # --- 1. read the current version ----------------------------------------
    $gradleText = Get-Content $GradleFile -Raw
    if ($gradleText -notmatch 'versionCode\s*=\s*(\d+)') { throw 'versionCode not found in app/build.gradle.kts' }
    $oldVersionCode = [int]$Matches[1]
    if ($gradleText -notmatch 'versionName\s*=\s*"([^"]+)"') { throw 'versionName not found in app/build.gradle.kts' }
    $oldVersion = $Matches[1]

    $propsPath = Join-Path $RepoRoot 'gradle.properties'
    $windowsVersion = $null
    if ((Test-Path $propsPath) -and ([System.IO.File]::ReadAllText($propsPath) -match '(?m)^appVersion=(.+?)\s*$')) {
        $windowsVersion = $Matches[1].Trim()
    }

    # The two platforms carry their own version numbers, and they drift: a
    # desktop-only release moves the Windows one and leaves Android alone, so after a
    # few of those the Windows version is ahead.
    #
    # A desktop-only release must start from the Windows version, or it recomputes a
    # number it has already published. A full release must start from whichever is
    # higher, or it produces a number that is behind the Windows one and collides
    # with a tag that already exists - which is what happened when Android sat at
    # 1.4.3 while Windows was at 1.4.8.
    $baseVersion = $oldVersion
    if ($DesktopOnly) {
        if (-not $windowsVersion) {
            throw 'appVersion not found in gradle.properties; a desktop-only release needs it to compute the next version'
        }
        # The higher of the two: an Android-only release may already have used the next number.
        $baseVersion = Compare-VersionNumbers $windowsVersion $oldVersion
    } elseif ($windowsVersion) {
        $baseVersion = Compare-VersionNumbers $windowsVersion $oldVersion
    }

    $bp = $baseVersion -split '\.'
    while ($bp.Count -lt 3) { $bp += '0' }
    $major = [int]$bp[0]; $minor = [int]$bp[1]; $patch = [int]$bp[2]

    switch ($Bump) {
        'Major' { $major++; $minor = 0; $patch = 0 }
        'Minor' { $minor++; $patch = 0 }
        default { $patch++ }
    }
    $newVersion = "$major.$minor.$patch"
    $newVersionCode = $oldVersionCode + 1
    $tag = "v$newVersion"

    if ($DesktopOnly) {
        Write-Host "Windows $baseVersion  ->  $newVersion   (Android stays at $oldVersion)" -ForegroundColor Cyan
    } else {
        Write-Host "Version $oldVersion ($oldVersionCode)  ->  $newVersion ($newVersionCode)" -ForegroundColor Cyan
        Write-Host ("Artifact: " + $(if ($useDebug) { 'debug-signed APK' } else { 'signed release APK' })) -ForegroundColor Cyan
    }

    # --- 2. write it back ---------------------------------------------------
    # A desktop-only release must not move the Android version, or every existing
    # install would be offered an APK that has not changed.
    if (-not $DesktopOnly) {
        $gradleText = $gradleText -replace 'versionCode\s*=\s*\d+', "versionCode = $newVersionCode"
        $gradleText = $gradleText -replace 'versionName\s*=\s*"[^"]+"', "versionName = `"$newVersion`""
        [System.IO.File]::WriteAllText($GradleFile, $gradleText)
    } else {
        Write-Host "Desktop-only release: leaving the Android version at $oldVersion." -ForegroundColor Cyan
    }

    # The Windows installer reads its version from gradle.properties. Updating it
    # here is what stops the two from drifting, which previously shipped a release
    # whose MSI was named after a version nobody had released.
    $propsFile = Join-Path $RepoRoot 'gradle.properties'
    if ((Test-Path $propsFile) -and -not $AndroidOnly) {
        $propsText = [System.IO.File]::ReadAllText($propsFile)
        $propsText = [regex]::Replace($propsText, '(?m)^appVersion=.*$', "appVersion=$newVersion")
        [System.IO.File]::WriteAllText($propsFile, $propsText)
        Write-Host "gradle.properties appVersion -> $newVersion" -ForegroundColor DarkGray
    }

    # --- 3. validate --------------------------------------------------------
    $variant = if ($useDebug) { 'Debug' } else { 'Release' }
    $gradlew = if ($env:OS -eq 'Windows_NT') { '.\gradlew.bat' } else { './gradlew' }

    # The Windows installer is built from the same run. Leaving it to a separate
    # manual step is how a release once shipped with no MSI at all: the APK was
    # built, the release went out, and the installer was quietly missing.
    $hasDesktop = (Test-Path (Join-Path $RepoRoot 'desktop\build.gradle.kts')) -and -not $AndroidOnly
    if (-not $SkipTests) {
        $targets = @(':core:test')
        if (-not $AndroidOnly) { $targets += ':desktop:test' }
        if (-not $DesktopOnly) {
            # Only worth building an APK that is about to be published. Assembling it
            # for a desktop-only release costs several minutes and produces an
            # identical, uninstalled file.
            $targets += @(':app:testDebugUnitTest', ':app:lintDebug', ":app:assemble$variant")
        }
        if ($hasDesktop) {
            # prepareDistributable has to run between createDistributable and both
            # packaging tasks: it strips the api-ms-win-*.dll stubs that cause
            # "Failed to launch JVM" on a machine with a security agent, and adds the
            # startup check. The MSI is built from the same unpacked app as the zip, so
            # it needs the same preparation - and unlike the zip it cannot declare the
            # dependency itself, because the Compose distribution tasks cannot be
            # referenced by name from the build script.
            $targets += @(':desktop:createDistributable', ':desktop:prepareDistributable', ':desktop:packageMsi')
            # The extension zips published beside the installers. Here rather than
            # with the rest, because they carry the app version stamped into their
            # manifests and the version was bumped a moment ago.
            $targets += ':desktop:packageExtension'
        }
        Write-Host ("Running " + ($targets -join ' ') + "...") -ForegroundColor Cyan
        & $gradlew @targets
        if ($LASTEXITCODE -ne 0) { throw 'Build failed; the version bump was left in place for inspection.' }
    }

    # The portable zip is a second Gradle run rather than another task on the same
    # command line: the Compose distribution tasks cannot be referenced by name
    # from the build script, so ordering them here is the only reliable way.
    if ($hasDesktop) {
        # packageZip depends on prepareDistributable by name, but the explicit call
        # keeps the order visible where it matters.
        & $gradlew ':desktop:prepareDistributable' ':desktop:packageZip'
        if ($LASTEXITCODE -ne 0) { throw 'The portable zip failed to build.' }
    }

    # The delta update: this release's file list, and a zip of the files changed since
    # the previous release's list. The installed app downloads only that zip when it is
    # one version behind, instead of the whole installer. The previous list is fetched
    # from the latest published release; without one, only the list is published.
    $updateAssets = @()
    if ($hasDesktop) {
        $previousDir = Join-Path $RepoRoot 'desktop\build\previous-release'
        if (Test-Path $previousDir) { Remove-Item $previousDir -Recurse -Force }
        New-Item -ItemType Directory -Force -Path $previousDir | Out-Null
        $strictPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        & $gh release download --repo $Repository --pattern '*-files.json' --dir $previousDir 2>$null
        $ErrorActionPreference = $strictPreference
        $previousList = Get-ChildItem $previousDir -Filter '*-files.json' -ErrorAction SilentlyContinue | Select-Object -First 1
        $listArg = if ($previousList) { "-PpreviousUpdateManifest=$($previousList.FullName)" } else { '-PpreviousUpdateManifest=' }
        & $gradlew ':desktop:packageUpdateDelta' $listArg
        if ($LASTEXITCODE -ne 0) { throw 'The delta update files failed to build.' }
        $updateAssets = @(Get-ChildItem (Join-Path $RepoRoot 'desktop\build\update') -File | ForEach-Object {
            $dest = Join-Path $RepoRoot $_.Name
            Copy-Item $_.FullName $dest -Force
            Write-Host ("UPDATE {0} {1} bytes" -f $_.Name, $_.Length) -ForegroundColor DarkGray
            $dest
        })
    }
    $apkAsset = $null
    $abiApkAssets = @()
    $apkHash = ''
    $apkSize = 0L
    if (-not $DesktopOnly) {
        $apkBuilt = if ($useDebug) { $DebugApkBuilt } else { $ReleaseApkBuilt }
        if (-not (Test-Path $apkBuilt)) { throw "APK not found at $apkBuilt" }
        $apkAsset = Join-Path $RepoRoot ("1-download-manager-$newVersion.apk")
        Copy-Item $apkBuilt $apkAsset -Force
        $apkHash = (Get-FileHash $apkAsset -Algorithm SHA256).Hash
        $apkSize = (Get-Item $apkBuilt).Length
        Write-Host ("APK {0} bytes, sha256 {1}" -f $apkSize, $apkHash) -ForegroundColor DarkGray
        # One APK per processor as well. The updater takes the one for its phone, about
        # half the size of the universal one; the universal APK keeps its old name and is
        # uploaded first, so installs from before this pick it as they always did.
        $abiApkAssets = @(Get-ChildItem (Split-Path $apkBuilt) -Filter '*.apk' | ForEach-Object {
            if ($_.Name -match '-(arm64-v8a|x86_64)-') {
                $dest = Join-Path $RepoRoot ("1-download-manager-$newVersion-" + $Matches[1] + ".apk")
                Copy-Item $_.FullName $dest -Force
                Write-Host ("APK {0} {1} bytes" -f $Matches[1], $_.Length) -ForegroundColor DarkGray
                $dest
            }
        })
    } else {
        Write-Host 'Desktop-only release: no Android APK will be published.' -ForegroundColor Cyan
    }

    # The Windows installer, when one has been built. Absent builds are not an
    # error: the Android release must still be publishable on its own.
    $msiBuilt = Get-ChildItem (Join-Path $RepoRoot 'desktop\build\compose\binaries\main\msi') `
        -Filter '*.msi' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like "*$newVersion*" } |
        Select-Object -First 1
    $msiAsset = $null
    if ($msiBuilt) {
        $msiAsset = Join-Path $RepoRoot ("1-download-manager-$newVersion.msi")
        Copy-Item $msiBuilt.FullName $msiAsset -Force
        Write-Host ("MSI {0} bytes" -f (Get-Item $msiBuilt.FullName).Length) -ForegroundColor DarkGray
    } else {
        if ($hasDesktop) {
            # The desktop module exists, so an absent MSI means the build silently
            # skipped it. Publishing an APK-only release would quietly remove the
            # Windows download, so this stops the release instead.
            throw "No Windows installer for $newVersion was produced by :desktop:packageMsi."
        }
        Write-Host 'No desktop module; publishing the APK only.' -ForegroundColor Yellow
    }

    # A portable zip, so the app is usable on a machine where an installer cannot
    # run at all. Built for every release rather than on request, so it is never
    # missing at the moment someone needs it.
    $zipBuilt = $null
    $zipAsset = $null
    if ($hasDesktop) {
        $zipBuilt = Get-ChildItem (Join-Path $RepoRoot 'desktop\build\distributions') `
            -Filter '*.zip' -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -like "*$newVersion*" } |
            Select-Object -First 1
        if (-not $zipBuilt) { throw "No portable zip for $newVersion was produced by :desktop:packageZip." }
        $zipAsset = Join-Path $RepoRoot ("1-download-manager-$newVersion-portable.zip")
        Copy-Item $zipBuilt.FullName $zipAsset -Force
        Write-Host ("ZIP {0} bytes" -f (Get-Item $zipBuilt.FullName).Length) -ForegroundColor DarkGray
    }

    # --- 4. verify the published signature ----------------------------------
    $sdkRoot = $env:ANDROID_HOME
    if (-not $sdkRoot) { $sdkRoot = $env:ANDROID_SDK_ROOT }
    $apksigner = $null
    if ($sdkRoot) {
        $apksigner = Get-ChildItem (Join-Path $sdkRoot 'build-tools') -Recurse -Filter 'apksigner.bat' `
            -ErrorAction SilentlyContinue |
            Sort-Object FullName -Descending | Select-Object -First 1
    }
    if ($apksigner -and $apkAsset) {
        $verify = & $apksigner.FullName verify -v $apkAsset 2>&1
        $verifies = [bool]($verify | Select-String -Pattern '^Verifies')
        $v2 = [bool]($verify | Select-String -Pattern 'v2 scheme.*true')
        $v3 = [bool]($verify | Select-String -Pattern 'v3 scheme.*true')
        $signer = ($verify | Select-String -Pattern 'certificate DN' | Select-Object -First 1)
        Write-Host ("Signature: " + $(if ($verifies) { "verified (v2=$v2 v3=$v3)" } else { 'NOT VERIFIED' })) -ForegroundColor DarkGray
        if ($signer) { Write-Host ("Signer   : " + $signer.ToString().Trim()) -ForegroundColor DarkGray }
        if (-not $verifies) { throw 'The APK signature did not verify; refusing to publish.' }
    }

    # --- 5. commit + tag + push --------------------------------------------
    # git and gh write ordinary progress to stderr ("Everything up-to-date",
    # "* [new tag] ..."). Under $ErrorActionPreference = 'Stop' Windows
    # PowerShell turns that into a terminating NativeCommandError, so a
    # successful publish reported failure. Relax the preference for the noisy
    # calls and keep $LASTEXITCODE as the only success signal.
    $strictPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'

    & $git add --all
    & $git commit -m "Release $tag"
    if ($LASTEXITCODE -ne 0) { $ErrorActionPreference = $strictPreference; throw 'Nothing to commit or the commit failed.' }
    & $git tag -a $tag -m "1 download manager $newVersion"
    if ($LASTEXITCODE -ne 0) { $ErrorActionPreference = $strictPreference; throw "Creating tag $tag failed." }

    & $git push origin main
    if ($LASTEXITCODE -ne 0) { $ErrorActionPreference = $strictPreference; throw "Pushing main failed. Nothing was tagged remotely; re-run after fixing connectivity." }
    & $git push origin "refs/tags/$tag"
    if ($LASTEXITCODE -ne 0) { $ErrorActionPreference = $strictPreference; throw "Pushing tag $tag failed; the release was not published." }

    $ErrorActionPreference = $strictPreference

    # --- 6. release notes ---------------------------------------------------
    # With no earlier tag (the first release) git describe fails, and under 'Stop'
    # Windows PowerShell turns that into a terminating error before the release is
    # published. No previous tag just means the log covers everything.
    $ErrorActionPreference = 'Continue'
    $previousTag = (& $git describe --tags --abbrev=0 "$tag^" 2>$null)
    if ($LASTEXITCODE -ne 0) { $previousTag = $null }
    $ErrorActionPreference = $strictPreference
    $logArgs = @('log', '--pretty=format:- %s')
    if ($previousTag) { $logArgs += "$previousTag..$tag" }
    $commitList = (& $git @logArgs) -join "`n"
    $signingNote = if ($useDebug) {
        'This APK is debug-signed and is for internal testing only.'
    } else {
        'This APK is release-signed and can replace an earlier release-signed install.'
    }
    if ([string]::IsNullOrWhiteSpace($Notes)) {
        $Notes = @"
1 download manager $newVersion

Highlights:
$commitList

APK: $([System.IO.Path]::GetFileName($apkAsset)) ($([math]::Round($apkSize / 1MB, 1)) MB)
SHA-256: $apkHash
$signingNote

Open this app's Settings -> Check for updates to install this release.
"@
    }

    # --- 7. publish ---------------------------------------------------------
    # The notes go through a file, never as a command-line argument. Windows
    # PowerShell re-parses native-command arguments, so a multi-line notes body
    # containing quotes, backticks or a line starting with "-" gets mangled and
    # the upload fails (or publishes truncated text).
    $notesFile = Join-Path ([System.IO.Path]::GetTempPath()) "dlm-notes-$tag.txt"
    [System.IO.File]::WriteAllText($notesFile, $Notes)
    $notesArgs = @('--notes-file', $notesFile)

    $assets = @()
    if ($apkAsset) { $assets += $apkAsset }
    $assets += $abiApkAssets
    if ($msiAsset) { $assets += $msiAsset }
    if ($zipAsset) { $assets += $zipAsset }
    $assets += $updateAssets

    # The browser extensions, zipped per browser.
    #
    # They are also unpacked into the app itself, which is how a user with the app
    # installed loads one. But that folder is a path only they have, it moves on
    # reinstall, and neither store can be pointed at it - so without a download
    # asset there is nothing to hand a person who wants the extension without
    # installing the app, and nothing to upload from.
    $extensionAssets = @()
    if ($hasDesktop) {
        $extensionDir = Join-Path $RepoRoot 'desktop\build\extensions'
        $extensionAssets = Get-ChildItem $extensionDir -Include '*.zip', '*.xpi' -Recurse -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -like "*$newVersion*" } |
            ForEach-Object {
                $dest = Join-Path $RepoRoot $_.Name
                Copy-Item $_.FullName $dest -Force
                Write-Host ("EXT {0} bytes" -f (Get-Item $dest).Length) -ForegroundColor DarkGray
                $dest
            }
        if (@($extensionAssets).Count -eq 0) {
            # Silently publishing a release with no extension would look like the
            # extension had been withdrawn.
            throw "No extension zip for $newVersion was produced by :desktop:packageExtension."
        }
        $assets += @($extensionAssets)
    }
    if ($assets.Count -eq 0) { throw 'Nothing to publish: no assets were produced.' }

    $releaseArgs = @('release', 'create', $tag) + $assets +
        @('--title', "1 download manager $newVersion") + $notesArgs
    if ($Draft) { $releaseArgs += '--draft' }
    & $gh @releaseArgs
    if ($LASTEXITCODE -ne 0) {
        Write-Host 'Release already exists; updating it instead.' -ForegroundColor Yellow
        & $gh release upload $tag @assets --clobber
        if ($LASTEXITCODE -ne 0) { throw "Could not upload the assets to release $tag." }
        & $gh release edit $tag --title "1 download manager $newVersion" @notesArgs
        if ($LASTEXITCODE -ne 0) { throw "Could not update release $tag." }
    }

    # Confirm GitHub really has it before claiming success. This parses the JSON with
    # PowerShell instead of gh --jq: Windows PowerShell strips double quotes out of
    # native-command arguments, which corrupts jq expressions.
    $raw = & $gh release view $tag --repo $Repository --json 'tagName,assets' 2>$null | Out-String
    $info = $null
    if ($raw) { $info = $raw | ConvertFrom-Json }
    if (-not $info -or $info.tagName -ne $tag -or @($info.assets).Count -lt 1) {
        throw "Release $tag could not be read back from GitHub; treating the publish as failed."
    }
    Write-Host "Verified on GitHub: $($info.tagName) with $(@($info.assets).Count) asset(s)" -ForegroundColor DarkGray

    $repoUrl = (& $gh repo view --json url --jq .url)
    Write-Host ''
    Write-Host "Published $tag" -ForegroundColor Green
    Write-Host "  release : $repoUrl/releases/tag/$tag"
    Write-Host "  commit  : $(& $git rev-parse --short HEAD)"
}
finally {
    Pop-Location
}

# Reached only when the publish was verified above; a failure throws and skips
# this. Without it the script inherits a stale non-zero $LASTEXITCODE.
exit 0
