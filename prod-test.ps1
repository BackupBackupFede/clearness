<#
.SYNOPSIS
    Runs the BUILT jar on a real (production) Fabric server, then optionally joins it with a vanilla
    client. Dev and production disagree on Fabric 1.21.1, so only the built jar counts.

.DESCRIPTION
    Fabric 1.21.1 is the one line where dev and production disagree: dev runs Mojang names, the
    published jar runs intermediary. A mixin can work in runServer and crash a real server (Drained
    1.0.0), or the reverse (Fabric API on XP Tonic). Only the built jar on the official Fabric server
    launcher says which.

    1. builds nothing - run .\build-all.ps1 first, this tests what is in fabric\build\libs;
    2. downloads the official Fabric server launcher once (meta.fabricmc.net), into a shared cache;
    3. runs the self-test (<MODID>_SELFTEST=true) on a fresh world and prints its RESULT;
    4. with -VanillaJoin, starts the server again and walks a client with no mod through
       handshake -> login -> configuration -> play (tools\vanilla_join.py). That is the proof behind
       "vanilla clients welcome": a server that wants a modded client refuses in configuration.

.EXAMPLE
    .\prod-test.ps1                      # Fabric 1.21.1, self-test only
    .\prod-test.ps1 -VanillaJoin         # + a vanilla client joins
    .\prod-test.ps1 -Version 26.3        # any MATRIX line
#>
[CmdletBinding()]
param(
    [string]$Version = '1.21.1',
    [switch]$VanillaJoin,
    [int]   $TimeoutSeconds = 300
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

$modId = (Select-String -Path (Join-Path $root 'gradle.properties') -Pattern '^mod_id=(.+)$').Matches.Groups[1].Value
$useApi = (Select-String -Path (Join-Path $root 'gradle.properties') -Pattern '^use_fabric_api=(.+)$').Matches.Groups[1].Value -eq 'true'

# Read this line's row out of MATRIX: loader version, Java, Fabric API version.
$m = Select-String -Path (Join-Path $root 'build.gradle') -Pattern ('^\s*"' + [regex]::Escape($Version) + '"\s*:\s*\[') -Context 0, 3
if (-not $m) { throw "No MATRIX row for '$Version' in build.gradle." }
$row = (@($m.Line) + $m.Context.PostContext) -join ' '
$loader    = [regex]::Match($row, 'loader:\s*"([^"]+)"').Groups[1].Value
$javaMajor = [regex]::Match($row, 'java:\s*(\d+)').Groups[1].Value
$apiVer    = [regex]::Match($row, 'fabricApi:\s*"([^"]+)"').Groups[1].Value

# JDKs on this machine: 21 for 1.21.x, 25 for 26.x.
$java = if ($javaMajor -eq '21') { 'C:\Users\FLO\.jdks\ms-21.0.11\bin\java.exe' }
        else { 'C:\Users\FLO\.gradle\jdks\eclipse_adoptium-25-amd64-windows.2\bin\java.exe' }
if (-not (Test-Path $java)) { throw "JDK $javaMajor not found at $java" }

$jars = @(Get-ChildItem (Join-Path $root 'fabric\build\libs') -Filter "$modId-fabric-$Version-*.jar" -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' })
if ($jars.Count -ne 1) { throw "Expected exactly 1 built jar for fabric $Version, found $($jars.Count). Run .\build-all.ps1 first." }

# A free port, asked from the OS: a server left over from an interrupted run must not make this one
# fail with "Address already in use".
$listener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Loopback, 0)
$listener.Start(); $Port = $listener.LocalEndpoint.Port; $listener.Stop()

# One server per Minecraft line, shared by every mod: the launcher downloads Minecraft once.
$server = Join-Path $env:LOCALAPPDATA "mod-prod-servers\fabric-$Version"
New-Item -ItemType Directory -Force -Path (Join-Path $server 'mods') | Out-Null
$launcher = Join-Path $server 'fabric-server.jar'
if (-not (Test-Path $launcher)) {
    $url = "https://meta.fabricmc.net/v2/versions/loader/$Version/$loader/1.0.3/server/jar"
    Write-Host "Downloading the Fabric server launcher: $url"
    Invoke-WebRequest -Uri $url -OutFile $launcher -UseBasicParsing
}
Set-Content -Path (Join-Path $server 'eula.txt') -Value 'eula=true' -Encoding ascii
Set-Content -Path (Join-Path $server 'server.properties') -Encoding ascii -Value @(
    "level-name=world-$modId", "server-port=$Port", 'online-mode=false', 'white-list=false',
    'level-type=minecraft\:flat', 'generate-structures=false', 'spawn-protection=0')

# Only this mod (+ Fabric API when it declares it): another mod's jar would muddy the verdict.
Get-ChildItem (Join-Path $server 'mods') -Filter *.jar | Remove-Item -Force
Copy-Item $jars[0].FullName (Join-Path $server 'mods')
if ($useApi) {
    $api = Join-Path $server "fabric-api-$apiVer.jar"
    if (-not (Test-Path $api)) {
        Invoke-WebRequest -UseBasicParsing -OutFile $api `
            -Uri "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/$apiVer/fabric-api-$apiVer.jar"
    }
    Copy-Item $api (Join-Path $server 'mods')
}
$world = Join-Path $server "world-$modId"
if (Test-Path $world) { Remove-Item $world -Recurse -Force }

# The server's output goes to a file: simpler than reading a redirected stream from PowerShell, and
# it stays there to be read when something fails. The test world is thrown away on every run, so
# the server is simply killed at the end of the vanilla join.
function Start-Server([string]$log, [hashtable]$envVars) {
    foreach ($k in $envVars.Keys) { [Environment]::SetEnvironmentVariable($k, $envVars[$k]) }
    try {
        return Start-Process -FilePath $java -ArgumentList '-Xmx2G', '-jar', 'fabric-server.jar', 'nogui' `
            -WorkingDirectory $server -NoNewWindow -PassThru -RedirectStandardOutput $log -RedirectStandardError "$log.err"
    } finally {
        foreach ($k in $envVars.Keys) { [Environment]::SetEnvironmentVariable($k, $null) }
    }
}

# The server still holds the file open for writing: open it shared, or Windows refuses the read.
function Read-Log([string]$log) {
    if (-not (Test-Path $log)) { return '' }
    $fs = [System.IO.File]::Open($log, 'Open', 'Read', 'ReadWrite')
    try { return (New-Object System.IO.StreamReader($fs)).ReadToEnd() } finally { $fs.Dispose() }
}

$switch = ($modId.ToUpper() -replace '[^A-Z0-9]', '') + '_SELFTEST'
Write-Host "Production Fabric $Version (loader $loader, JDK $javaMajor): $($jars[0].Name)" -ForegroundColor Cyan

# --- 1. the self-test, which halts the server itself ---------------------------------------------
$log = Join-Path $server "selftest-$modId.log"
# The very first run on a line also downloads Minecraft and the Fabric libraries: give it longer.
$firstRun = -not (Test-Path (Join-Path $server '.fabric'))
if ($firstRun) { Write-Host "  first run on this line: the launcher downloads Minecraft first (a few minutes)" }
$p = Start-Server $log @{ $switch = 'true' }
if (-not $p.WaitForExit(($TimeoutSeconds + $(if ($firstRun) { 900 } else { 0 })) * 1000)) {
    $p.Kill(); Write-Host "  self-test : TIMEOUT - the server never halted (read $log)" -ForegroundColor Red; exit 1
}
$text = Read-Log $log
$failed = $false
if ($text -match 'Critical injection failure|InvalidInjectionException|Mixin apply.*failed') {
    Write-Host "  mixins    : FAILED - read $log" -ForegroundColor Red; $failed = $true
}
$result = ([regex]::Matches($text, '\[SELFTEST\] RESULT.*') | Select-Object -Last 1).Value
if (-not $result) { Write-Host "  self-test : NO RESULT - read $log" -ForegroundColor Red; $failed = $true }
elseif ($result -match 'PASS') { Write-Host "  self-test : PASS" -ForegroundColor Green }
else { Write-Host "  self-test : $result" -ForegroundColor Red; $failed = $true }

# --- 2. a vanilla client joins ---------------------------------------------------------------------
if ($VanillaJoin) {
    # Protocol numbers, read from each version: add a line here when MATRIX gains one.
    $protocols = @{ '1.21.1' = 767; '26.2' = 776 }
    if (-not $protocols.ContainsKey($Version)) {
        Write-Host "  vanilla   : SKIPPED - no protocol number recorded for $Version in prod-test.ps1" -ForegroundColor Yellow
    } else {
        $log = Join-Path $server "join-$modId.log"
        $p = Start-Server $log @{}
        $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
        while ((Get-Date) -lt $deadline -and -not $p.HasExited -and (Read-Log $log) -notmatch 'Done \(') { Start-Sleep -Milliseconds 500 }
        if ((Read-Log $log) -notmatch 'Done \(') {
            Write-Host "  vanilla   : the server never finished starting (read $log)" -ForegroundColor Red; $failed = $true
        } else {
            $probe = & python -I (Join-Path $root 'tools\vanilla_join.py') 127.0.0.1 $Port $protocols[$Version] 2>&1 | Select-Object -Last 1
            if ("$probe" -match '^JOINED') { Write-Host "  vanilla   : JOINED and stayed in play" -ForegroundColor Green }
            else { Write-Host "  vanilla   : $probe" -ForegroundColor Red; $failed = $true }
        }
        if (-not $p.HasExited) { $p.Kill() }
    }
}

if ($failed) { exit 1 }
