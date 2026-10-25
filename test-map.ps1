<#
.SYNOPSIS
    Prepares the in-game pass: the built jar in .minecraft\mods, debug features switched on, and a
    flat test world with cheats in .minecraft\saves. -Restore puts everything back.

.DESCRIPTION
    Written for this machine's launcher, where every installation shares .minecraft\mods and the
    JVM arguments cannot be edited:
      - the mods already in .minecraft\mods are MOVED aside to mods-backup-<modid>, not deleted;
      - debug features are switched on with an empty config\<modid>.debug file, not a JVM argument;
      - the world is generated once by a vanilla Fabric 1.21.1 server (no mod), flat, peaceful,
        cheats on, then copied to saves\<Mod Name> - Test. A newer client upgrades it on opening.

    Then: launcher -> the installation matching -Loader / -Version -> Singleplayer -> the test map.

.EXAMPLE
    .\test-map.ps1                               # fabric 1.21.1
    .\test-map.ps1 -Loader neoforge -Version 26.3
    .\test-map.ps1 -Restore                      # jar and debug file out, your mods back
#>
[CmdletBinding()]
param(
    [ValidateSet('fabric', 'neoforge')][string]$Loader = 'fabric',
    [string]$Version = '1.21.1',
    [switch]$Restore,
    [string]$MinecraftDir = (Join-Path $env:APPDATA '.minecraft')
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$props = Join-Path $root 'gradle.properties'
$modId = (Select-String -Path $props -Pattern '^mod_id=(.+)$').Matches.Groups[1].Value
$modName = (Select-String -Path $props -Pattern '^mod_name=(.+)$').Matches.Groups[1].Value
$mods = Join-Path $MinecraftDir 'mods'
$backup = Join-Path $MinecraftDir "mods-backup-$modId"
$debugFile = Join-Path $MinecraftDir "config\$modId.debug"
$mapName = "$modName - Test"

if (-not (Test-Path $MinecraftDir)) { throw "No Minecraft folder at $MinecraftDir" }

# --- restore -------------------------------------------------------------------------------------
if ($Restore) {
    Get-ChildItem $mods -Filter "$modId-*.jar" -ErrorAction SilentlyContinue | Remove-Item -Force
    if (Test-Path $debugFile) { Remove-Item $debugFile -Force }
    if (Test-Path $backup) {
        Get-ChildItem $backup | Move-Item -Destination $mods -Force
        Remove-Item $backup -Force
    }
    Write-Host "Restored: $modId jar and debug file removed, your mods are back in $mods." -ForegroundColor Green
    Write-Host "The map '$mapName' is still in saves\ - delete it by hand if you are done with it."
    return
}

# --- the jar -------------------------------------------------------------------------------------
$jars = @(Get-ChildItem (Join-Path $root "$Loader\build\libs") -Filter "$modId-$Loader-$Version-*.jar" -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' })
if ($jars.Count -ne 1) { throw "Expected exactly 1 built jar for $Loader $Version, found $($jars.Count). Run .\build-all.ps1 first." }

New-Item -ItemType Directory -Force -Path $mods | Out-Null
# Every installation reads this one folder: whatever is there now goes aside, once, and comes back
# with -Restore. A second run only swaps this mod's jar.
if (-not (Test-Path $backup)) {
    New-Item -ItemType Directory -Path $backup | Out-Null
    Get-ChildItem $mods | Move-Item -Destination $backup
    Write-Host "Your mods were moved to $backup (back with -Restore)."
}
Get-ChildItem $mods -Filter "$modId-*.jar" | Remove-Item -Force
Copy-Item $jars[0].FullName $mods
New-Item -ItemType Directory -Force -Path (Split-Path $debugFile) | Out-Null
Set-Content -Path $debugFile -Value '' -Encoding ascii

# --- the map -------------------------------------------------------------------------------------
$saves = Join-Path $MinecraftDir 'saves'
$target = Join-Path $saves $mapName
if (Test-Path $target) {
    Write-Host "Map '$mapName' already in saves\ - kept as it is (delete it for a fresh one)."
} else {
    $server = Join-Path $env:LOCALAPPDATA 'mod-prod-servers\worldgen-1.21.1'
    New-Item -ItemType Directory -Force -Path (Join-Path $server 'mods') | Out-Null
    $launcher = Join-Path $server 'fabric-server.jar'
    if (-not (Test-Path $launcher)) {
        Invoke-WebRequest -UseBasicParsing -OutFile $launcher -Uri 'https://meta.fabricmc.net/v2/versions/loader/1.21.1/0.16.10/1.0.3/server/jar'
    }
    Get-ChildItem (Join-Path $server 'mods') | Remove-Item -Recurse -Force    # a vanilla world: no mod
    $world = Join-Path $server 'world'
    if (Test-Path $world) { Remove-Item $world -Recurse -Force }
    $listener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Loopback, 0)
    $listener.Start(); $port = $listener.LocalEndpoint.Port; $listener.Stop()      # any free port
    Set-Content -Path (Join-Path $server 'eula.txt') -Value 'eula=true' -Encoding ascii
    Set-Content -Path (Join-Path $server 'server.properties') -Encoding ascii -Value @(
        'level-name=world', 'level-type=minecraft\:flat', 'generate-structures=false',
        'difficulty=peaceful', 'spawn-monsters=false', 'online-mode=false', "server-port=$port")
    # The console reads "stop" from this file and runs it once the server is up: a clean shutdown,
    # so level.dat is complete.
    $stop = Join-Path $server 'stop.txt'
    Set-Content -Path $stop -Value 'stop' -Encoding ascii
    Write-Host "Generating the test map (vanilla Fabric 1.21.1 server, about a minute the first time)..."
    $p = Start-Process -FilePath 'C:\Users\FLO\.jdks\ms-21.0.11\bin\java.exe' -ArgumentList '-Xmx2G', '-jar', 'fabric-server.jar', 'nogui' `
        -WorkingDirectory $server -NoNewWindow -PassThru -RedirectStandardInput $stop `
        -RedirectStandardOutput (Join-Path $server 'worldgen.log') -RedirectStandardError (Join-Path $server 'worldgen.err')
    if (-not $p.WaitForExit(300000)) { $p.Kill(); throw "World generation timed out (read $server\worldgen.log)" }
    if (-not (Test-Path (Join-Path $world 'level.dat'))) { throw "No world was generated (read $server\worldgen.log)" }

    # A server world has cheats off for singleplayer: flip allowCommands (a byte tag) to 1.
    $levelDat = Join-Path $world 'level.dat'
    $in = New-Object System.IO.Compression.GZipStream([System.IO.File]::OpenRead($levelDat), [System.IO.Compression.CompressionMode]::Decompress)
    $ms = New-Object System.IO.MemoryStream; $in.CopyTo($ms); $in.Dispose()
    $bytes = $ms.ToArray()
    $tag = [byte[]](0x01, 0x00, 0x0D) + [System.Text.Encoding]::ASCII.GetBytes('allowCommands')
    $at = -1
    for ($i = 0; $i -le $bytes.Length - $tag.Length; $i++) {
        $hit = $true
        for ($j = 0; $j -lt $tag.Length; $j++) { if ($bytes[$i + $j] -ne $tag[$j]) { $hit = $false; break } }
        if ($hit) { $at = $i; break }
    }
    if ($at -lt 0) { throw "allowCommands not found in level.dat" }
    $bytes[$at + $tag.Length] = 1
    $out = New-Object System.IO.Compression.GZipStream([System.IO.File]::Create($levelDat), [System.IO.Compression.CompressionMode]::Compress)
    $out.Write($bytes, 0, $bytes.Length); $out.Dispose()

    foreach ($f in 'session.lock', 'level.dat_old') { $x = Join-Path $world $f; if (Test-Path $x) { Remove-Item $x -Force } }
    New-Item -ItemType Directory -Force -Path $saves | Out-Null
    Copy-Item $world $target -Recurse
    Write-Host "Map '$mapName' created: flat, peaceful, survival, cheats on."
}

Write-Host ""
Write-Host "Ready. Launcher -> the $Loader $Version installation -> Singleplayer -> '$mapName'." -ForegroundColor Green
Write-Host "Debug features are on (config\$modId.debug). When done: .\test-map.ps1 -Restore"
