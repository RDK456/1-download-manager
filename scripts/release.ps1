<#
.SYNOPSIS
    Bumps the app version, builds, pushes the whole tree, and publishes a new
    GitHub release with the APK attached.

.DESCRIPTION
    Every run performs the same sequence so releases stay reproducible:
      1. read versionName / versionCode from app/build.gradle.kts
      2. bump them (patch by default, -Minor / -Major for larger jumps)
      3. run unit tests, lint and assembleDebug
      4. copy the APK to the repository root as the release asset
      5. commit the whole tree, tag it, and push
      6. create the GitHub release (or update it if the tag already exists)

    The app's in-app updater reads the newest published release from GitHub, so
    a release created here is what existing installs offer as an update.

.PARAMETER Bump
    Which part of the version to increase: Patch (default), Minor, or Major.

.PARAMETER SkipTests
    Skip the test/lint/assemble step (use only when you already validated).

.PARAMETER Draft
    Create the release as a draft so it is not offered as an update yet.

.PARAMETER Notes
    Release notes body. Defaults to an auto-generated summary of the commits
    since the previous tag.

.EXAMPLE
    .\scripts\release.ps1
    .\scripts\release.ps1 -Bump Minor
#>
[CmdletBinding()]
param(
    [ValidateSet('Patch', 'Minor', 'Major')]
    [string]$Bump = 'Patch',
    [switch]$SkipTests,
    [switch]$Draft,
    [string]$Notes
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = Split-Path -Parent $PSScriptRoot
$GradleFile = Join-Path $RepoRoot 'app\build.gradle.kts'
$ApkBuilt = Join-Path $RepoRoot 'app\build\outputs\apk\debug\app-debug.apk'
$ApkAsset = Join-Path $RepoRoot 'DownloadHub-debug.apk'

# --- locate toolchain --------------------------------------------------------
$ToolsRoot = Join-Path $env:LOCALAPPDATA 'Temp\opencode\tools'
$PortableGitCmd = Join-Path $ToolsRoot 'mingit\cmd'
$PortableGhBin = Join-Path $ToolsRoot 'gh\bin'
foreach ($dir in @($PortableGitCmd, $PortableGhBin)) {
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

    # --- 2. write it back ---------------------------------------------------
    $gradleText = $gradleText -replace 'versionCode\s*=\s*\d+', "versionCode = $newVersionCode"
    $gradleText = $gradleText -replace 'versionName\s*=\s*"[^"]+"', "versionName = `"$newVersion`""
    [System.IO.File]::WriteAllText($GradleFile, $gradleText)

    # --- 3. validate --------------------------------------------------------
    if (-not $SkipTests) {
        Write-Host 'Running tests, lint and assembleDebug...' -ForegroundColor Cyan
        $gradlew = if ($env:OS -eq 'Windows_NT') { '.\gradlew.bat' } else { './gradlew' }
        & $gradlew test lintDebug assembleDebug
        if ($LASTEXITCODE -ne 0) { throw 'Build failed; the version bump was left in place for inspection.' }
    }

    if (-not (Test-Path $ApkBuilt)) { throw "APK not found at $ApkBuilt" }
    Copy-Item $ApkBuilt $ApkAsset -Force
    $apkHash = (Get-FileHash $ApkAsset -Algorithm SHA256).Hash
    $apkSize = (Get-Item $ApkAsset).Length
    Write-Host ("APK {0} bytes, sha256 {1}" -f $apkSize, $apkHash) -ForegroundColor DarkGray

    # --- 4. commit + tag + push --------------------------------------------
    & $git add --all
    & $git commit -m "Release $tag"
    if ($LASTEXITCODE -ne 0) { throw 'Nothing to commit or the commit failed.' }
    & $git tag -a $tag -m "1 download manager $newVersion"
    & $git push origin main
    & $git push origin "refs/tags/$tag"

    # --- 5. release notes ---------------------------------------------------
    $previousTag = (& $git describe --tags --abbrev=0 "$tag^" 2>$null)
    $logArgs = @('log', '--pretty=format:- %s')
    if ($previousTag) { $logArgs += "$previousTag..$tag" }
    $commitList = (& $git @logArgs) -join "`n"
    if ([string]::IsNullOrWhiteSpace($Notes)) {
        $Notes = @"
1 download manager $newVersion

Highlights:
$commitList

APK: DownloadHub-debug.apk ($([math]::Round($apkSize / 1MB, 1)) MB, debug-signed)
SHA-256: $apkHash

Open this app's Settings -> Check for updates to install this release.
"@
    }

    # --- 6. publish ---------------------------------------------------------
    $releaseArgs = @('release', 'create', $tag, $ApkAsset, '--title', "1 download manager $newVersion", '--notes', $Notes)
    if ($Draft) { $releaseArgs += '--draft' }
    & $gh @releaseArgs
    if ($LASTEXITCODE -ne 0) {
        Write-Host 'Release already exists; updating it instead.' -ForegroundColor Yellow
        & $gh release upload $tag $ApkAsset --clobber
        & $gh release edit $tag --title "1 download manager $newVersion" --notes $Notes
    }

    $repoUrl = (& $gh repo view --json url --jq .url)
    Write-Host ''
    Write-Host "Published $tag" -ForegroundColor Green
    Write-Host "  release : $repoUrl/releases/tag/$tag"
    Write-Host "  commit  : $(& $git rev-parse --short HEAD)"
}
finally {
    Pop-Location
}
