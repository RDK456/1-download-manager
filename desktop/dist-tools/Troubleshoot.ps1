# Reports why the app will not start, and starts it if it can.
#
# The packaged app has no java.exe: the jpackage launcher loads jvm.dll directly. So
# when something blocks the runtime, the launcher can only say "Failed to launch JVM",
# which tells the reader nothing about which file or why. This checks the things that
# actually cause it and prints the answer.
#
# Run it by double-clicking. Nothing is installed and nothing is changed.

$ErrorActionPreference = 'Continue'
$app = Split-Path -Parent $MyInvocation.MyCommand.Path
$failures = New-Object System.Collections.Generic.List[string]
$warnings = New-Object System.Collections.Generic.List[string]

function Say  ($text) { Write-Host $text }
function Good ($text) { Write-Host "  OK    $text" -ForegroundColor DarkGreen }
function Bad  ($text) { Write-Host "  BAD   $text" -ForegroundColor Red; $script:failures.Add($text) }
function Warn ($text) { Write-Host "  WARN  $text" -ForegroundColor Yellow; $script:warnings.Add($text) }

Say ''
Say '1 download manager - startup check'
Say ("   folder: $app")
Say ''

# --- where it is -------------------------------------------------------------
$root = [System.IO.Path]::GetPathRoot($app)
$drive = Get-PSDrive -Name $root.TrimEnd(':','\') -ErrorAction SilentlyContinue
Say 'Location'
if ($drive -and $drive.DisplayRoot) {
    Warn "this is a network drive ($($drive.DisplayRoot)); the JVM often cannot start from one"
} else {
    Good "local drive $root"
}
if ($app -match '[^\x20-\x7E]') {
    Warn 'the path contains non-ASCII characters, which some security products mishandle'
}
if ($app.Length -gt 180) {
    Warn "the path is very long (${($app.Length)} characters); try a shorter one like C:\1dm"
}

# --- is the package complete? ------------------------------------------------
Say ''
Say 'Package'
$exe = Join-Path $app '1DownloadManager.exe'
if (Test-Path $exe) { Good '1DownloadManager.exe' } else { Bad '1DownloadManager.exe is missing' }

$cfg = Join-Path $app 'app\1DownloadManager.cfg'
if (Test-Path $cfg) { Good 'app\1DownloadManager.cfg' } else { Bad 'app\1DownloadManager.cfg is missing - the zip did not extract fully' }

$release = Join-Path $app 'runtime\release'
if (Test-Path $release) { Good 'runtime\release' } else { Bad 'runtime\release is missing - the zip did not extract fully' }

$jvm = Join-Path $app 'runtime\bin\server\jvm.dll'
if (Test-Path $jvm) {
    $mb = [math]::Round((Get-Item $jvm).Length / 1MB, 1)
    if ($mb -lt 10) { Bad "runtime\bin\server\jvm.dll is only $mb MB; it should be about 12 MB" }
    else { Good "runtime\bin\server\jvm.dll ($mb MB)" }
} else {
    Bad 'runtime\bin\server\jvm.dll is missing - the zip did not extract fully'
}

# --- the files a security product likes to block -----------------------------
Say ''
Say 'Runtime files'
$bin = Join-Path $app 'runtime\bin'
if (Test-Path $bin) {
    $dlls = Get-ChildItem $bin -Filter *.dll -File -ErrorAction SilentlyContinue
    Good "$($dlls.Count) runtime DLLs"
    # A quarantined or blocked file is often left zero-length, or unreadable.
    $unreadable = @()
    foreach ($d in $dlls) {
        try {
            $s = [System.IO.File]::Open($d.FullName, 'Open', 'Read', 'ReadWrite')
            $s.Close()
        } catch { $unreadable += $d.Name }
    }
    if ($unreadable.Count -gt 0) {
        Bad "these DLLs cannot be read, which usually means security software is blocking them: $($unreadable -join ', ')"
    }
    $empty = $dlls | Where-Object { $_.Length -eq 0 }
    if ($empty) { Bad "these DLLs are zero bytes: $(($empty | ForEach-Object Name) -join ', ')" }
    $stubs = Get-ChildItem $bin -Filter 'api-ms-*.dll' -File -ErrorAction SilentlyContinue
    if ($stubs.Count -gt 0) {
        Warn "$($stubs.Count) api-ms-win-*.dll stubs present. These are what security software usually blocks, and the cause of most 'Failed to launch JVM'. Re-download the latest version, which leaves them out."
    }
} else {
    Bad 'runtime\bin is missing - the zip did not extract fully'
}

# --- can the JVM start at all? ----------------------------------------------
Say ''
Say 'Starting the app'
# Try the packaged launcher first, the way the user would. If it fails there is
# nothing more it can say, so fall back to java.exe: the same JVM, started by hand,
# which prints the real error - a missing DLL, a denied path, a blocked file.
$javaExe = Join-Path $bin 'java.exe'
$fallback = Join-Path $app 'Start 1DownloadManager.bat'

$launcherOk = $false
$proc = Start-Process -FilePath $exe -PassThru -ErrorAction SilentlyContinue
if (-not $proc) {
    Bad 'the launcher could not be started at all'
} else {
    Start-Sleep -Seconds 12
    if (Get-Process -Id $proc.Id -ErrorAction SilentlyContinue) {
        $launcherOk = $true
        Good 'the launcher started; if no window appeared, the problem is in the app, not the runtime'
    } else {
        Bad '1DownloadManager.exe exited immediately - the JVM never started'
    }
}

if (-not $launcherOk) {
    Say ''
    Say 'Asking the JVM directly what is wrong'
    if (-not (Test-Path $javaExe)) {
        Bad 'runtime\bin\java.exe is missing, so the real error cannot be read. Re-download this version.'
    } else {
        # Deliberately not Start 1DownloadManager.bat: it pauses on exit, which would
        # hang a double-clicked window with no way past it. The same command is run
        # here and its output captured instead.
        $appDir = Join-Path $app 'app'
        $arguments = @(
            '-cp', "$appDir\*",
            "-Dcompose.application.resources.dir=$appDir\resources",
            '-Dcompose.application.configure.swing.globals=true',
            "-Dskiko.library.path=$appDir",
            '-version'
        )
        # 2>&1 turns the JVM's stderr into PowerShell error records, which arrive
        # wrapped in CategoryInfo/position text. Unwrap them, because the JVM's own
        # message is the entire point of running it this way.
        $version = (& $javaExe @arguments 2>&1 | ForEach-Object {
            if ($_ -is [System.Management.Automation.ErrorRecord]) { $_.Exception.Message }
            else { $_.ToString() }
        }) -join "`n"
        if ($LASTEXITCODE -eq 0 -and $version -match 'version') {
            Good ('the JVM itself starts: ' + ($version -split "`n" | Select-Object -First 1).Trim())
            Say ''
            Say 'The runtime is fine, so the launcher stub is the problem. Start the app with:'
            Say '    Start 1DownloadManager.bat'
            Say 'in this folder. It bypasses the stub and prints the real error if there is one.'
        } else {
            Bad 'the JVM will not start even when run directly. This is the actual error:'
            ($version -split "`n" | Select-Object -First 12) | ForEach-Object {
                if ($_.Trim()) { Say "    $($_.Trim())" }
            }
            Say ''
            Say 'That message is what your security software is blocking. Exclude this'
            Say 'folder, or allow 1DownloadManager.exe and everything under runtime\bin.'
        }
    }
}

# --- what to do about it -----------------------------------------------------
Say ''
if ($failures.Count -eq 0) {
    Say 'Nothing is obviously wrong with the package.' -ForegroundColor Green
    Say 'If the app still will not start, your security software is the most likely'
    Say 'reason. Add your 1 download manager folder to its exclusion list, or ask'
    Say 'it to allow 1DownloadManager.exe and the files under runtime\bin.'
} else {
    Say "$($failures.Count) problem(s) found:" -ForegroundColor Red
    $failures | ForEach-Object { Say "  - $_" }
    Say ''
    Say 'Most likely fixes, in order:'
    Say '  1. Extract the zip again to a short local path, e.g. C:\1dm'
    Say '     Do not run it from inside the zip, and do not copy just the exe.'
    Say '  2. Delete the folder first. A half-extracted copy cannot be repaired.'
    Say '  3. Exclude the folder from your antivirus or EDR and extract again.'
    Say '     This is the usual cause: the agent quarantines a runtime DLL while it'
    Say '     is being written, and the launcher can only report "Failed to launch JVM".'
}
if ($warnings.Count -gt 0) {
    Say ''
    Say 'Also worth knowing:'
    $warnings | ForEach-Object { Say "  - $_" }
}
Say ''
Say 'This check changed nothing on your computer.'
Say ''
Read-Host 'Press Enter to close'
