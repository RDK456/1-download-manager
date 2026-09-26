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
param(
    [ValidateSet('Patch', 'Minor', 'Major')]
    [string]$Bump = 'Patch',
    [switch]$SkipTests,
    [switch]$DebugApk,
    [switch]$Draft,
    [string]$Notes
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = Split-Path -Parent $PSScriptRoot
$Repository = 'RDK456/1-download-manager'
$GradleFile = Join-Path $RepoRoot 'app\build.gradle.kts'
$DebugApkBuilt = Join-Path $RepoRoot 'app\build\outputs\apk\debug\app-debug.apk'
$ReleaseApkBuilt = Join-Path $RepoRoot 'app\build\outputs\apk\release\app-release.apk'
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

    $parts = $oldVersion -split '\.'
    while ($parts.Count -lt 3) { $parts += '0' }
    $major = [int]$parts[0]; $minor = [int]$parts[1]; $patch = [int]$parts[2]
    switch ($Bump) {
        'Major' { $major++; $minor = 0; $patch = 0 }
        'Minor' { $minor++; $patch = 0 }
        default { $patch++ }
    }
    $newVersion = "$major.$minor.$patch"
    $newVersionCode = $oldVersionCode + 1
    $tag = "v$newVersion"

    Write-Host "Version $oldVersion ($oldVersionCode)  ->  $newVersion ($newVersionCode)" -ForegroundColor Cyan
    Write-Host ("Artifact: " + $(if ($useDebug) { 'debug-signed APK' } else { 'signed release APK' })) -ForegroundColor Cyan

    # --- 2. write it back ---------------------------------------------------
    $gradleText = $gradleText -replace 'versionCode\s*=\s*\d+', "versionCode = $newVersionCode"
    $gradleText = $gradleText -replace 'versionName\s*=\s*"[^"]+"', "versionName = `"$newVersion`""
    [System.IO.File]::WriteAllText($GradleFile, $gradleText)

    # --- 3. validate --------------------------------------------------------
    $variant = if ($useDebug) { 'Debug' } else { 'Release' }
    if (-not $SkipTests) {
        Write-Host "Running tests, lint and assemble$variant..." -ForegroundColor Cyan
        $gradlew = if ($env:OS -eq 'Windows_NT') { '.\gradlew.bat' } else { './gradlew' }
        & $gradlew test lintDebug "assemble$variant"
        if ($LASTEXITCODE -ne 0) { throw 'Build failed; the version bump was left in place for inspection.' }
    }

    $apkBuilt = if ($useDebug) { $DebugApkBuilt } else { $ReleaseApkBuilt }
    if (-not (Test-Path $apkBuilt)) { throw "APK not found at $apkBuilt" }
    $apkAsset = Join-Path $RepoRoot ("1-download-manager-$newVersion.apk")
    Copy-Item $apkBuilt $apkAsset -Force
    $apkHash = (Get-FileHash $apkAsset -Algorithm SHA256).Hash
    $apkSize = (Get-Item $apkAsset).Length
    Write-Host ("APK {0} bytes, sha256 {1}" -f $apkSize, $apkHash) -ForegroundColor DarkGray

    # --- 4. verify the published signature ----------------------------------
    $sdkRoot = $env:ANDROID_HOME
    if (-not $sdkRoot) { $sdkRoot = $env:ANDROID_SDK_ROOT }
    $apksigner = $null
    if ($sdkRoot) {
        $apksigner = Get-ChildItem (Join-Path $sdkRoot 'build-tools') -Recurse -Filter 'apksigner.bat' `
            -ErrorAction SilentlyContinue |
            Sort-Object FullName -Descending | Select-Object -First 1
    }
    if ($apksigner) {
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
    & $git add --all
    & $git commit -m "Release $tag"
    if ($LASTEXITCODE -ne 0) { throw 'Nothing to commit or the commit failed.' }
    & $git tag -a $tag -m "1 download manager $newVersion"

    & $git push origin main
    if ($LASTEXITCODE -ne 0) { throw "Pushing main failed. Nothing was tagged remotely; re-run after fixing connectivity." }
    & $git push origin "refs/tags/$tag"
    if ($LASTEXITCODE -ne 0) { throw "Pushing tag $tag failed; the release was not published." }

    # --- 6. release notes ---------------------------------------------------
    $previousTag = (& $git describe --tags --abbrev=0 "$tag^" 2>$null)
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
    $releaseArgs = @('release', 'create', $tag, $apkAsset, '--title', "1 download manager $newVersion", '--notes', $Notes)
    if ($Draft) { $releaseArgs += '--draft' }
    & $gh @releaseArgs
    if ($LASTEXITCODE -ne 0) {
        Write-Host 'Release already exists; updating it instead.' -ForegroundColor Yellow
        & $gh release upload $tag $apkAsset --clobber
        if ($LASTEXITCODE -ne 0) { throw "Could not upload the APK to release $tag." }
        & $gh release edit $tag --title "1 download manager $newVersion" --notes $Notes
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
