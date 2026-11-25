# Command line for 1 download manager. The app must be running, with
# Settings > "Local API and command line" ticked.
#
#   .\dm.ps1 add <link> [<link>...]
#   .\dm.ps1 list
#   .\dm.ps1 status|pause|resume|remove <id>      (remove keeps the files)
#   .\dm.ps1 pause-all | resume-all
param(
    [Parameter(Position = 0)][string]$Command = 'list',
    [Parameter(Position = 1, ValueFromRemainingArguments = $true)][string[]]$Rest
)
$ErrorActionPreference = 'Stop'

# The secret is read from the app's own settings, so it is never written into this script.
$settingsFile = Join-Path $env:APPDATA '1DownloadManager\settings.json'
$secret = (Get-Content -LiteralPath $settingsFile -Raw | ConvertFrom-Json).rpcSecret
if (-not $secret) { throw 'Turn on "Local API and command line" in Settings first.' }

function Invoke-Dm([string]$Method, [object[]]$Arguments = @()) {
    $call = @{ jsonrpc = '2.0'; id = 1; method = "aria2.$Method"; params = @("token:$secret") + $Arguments }
    $body = [Text.Encoding]::UTF8.GetBytes((ConvertTo-Json $call -Depth 5 -Compress))
    try {
        $reply = Invoke-RestMethod -Uri 'http://127.0.0.1:6800/jsonrpc' -Method Post -Body $body -ContentType 'application/json'
    } catch {
        throw 'No answer. Is the app running with the local API ticked?'
    }
    if ($reply.error) { throw $reply.error.message }
    return $reply.result
}

function Show-Downloads($Items) {
    $Items | ForEach-Object {
        $percent = if ([long]$_.totalLength -gt 0) { [int](100 * [long]$_.completedLength / [long]$_.totalLength) } else { 0 }
        [pscustomobject]@{ Id = $_.gid; Status = $_.status; Done = "$percent%"; Name = $_.name }
    } | Format-Table -AutoSize
}

function Get-Id { if (-not $Rest) { throw "Give a download id. See: dm.ps1 list" }; $Rest[0] }

switch ($Command) {
    'add' {
        if (-not $Rest) { throw 'Give a link.' }
        foreach ($link in $Rest) { Invoke-Dm 'addUri' @(, @($link)) }
    }
    'list' { Show-Downloads (@(Invoke-Dm 'tellActive') + @(Invoke-Dm 'tellWaiting') + @(Invoke-Dm 'tellStopped')) }
    'status' { Invoke-Dm 'tellStatus' @(Get-Id) | Format-List }
    'pause' { Invoke-Dm 'pause' @(Get-Id) }
    'resume' { Invoke-Dm 'unpause' @(Get-Id) }
    'remove' { Invoke-Dm 'remove' @(Get-Id) }
    'pause-all' { Invoke-Dm 'pauseAll' }
    'resume-all' { Invoke-Dm 'unpauseAll' }
    default { Write-Output 'Commands: add, list, status, pause, resume, remove, pause-all, resume-all' }
}
