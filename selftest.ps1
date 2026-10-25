<#
.SYNOPSIS
    Runs the in-game behaviour check on every loader x Minecraft line, and prints one line each.

.DESCRIPTION
    A green check here is the only cheap evidence a mixin still applies: Mixin resolves its targets
    at runtime, so compiling proves nothing. NeoForge's runServer ignores stdin, which is why the
    test halts the server itself.

    A world never loads in an older Minecraft, so each line gets its own level-name.

.EXAMPLE
    .\selftest.ps1                       # every loader, every line of MATRIX
    .\selftest.ps1 -Loaders fabric       # one loader
    .\selftest.ps1 -FreshWorld           # a new world on every run
    .\selftest.ps1 -FreshWorld -Restart  # then once more on the same world, after a restart
#>
[CmdletBinding()]
param(
    [string[]]$Loaders  = @('fabric', 'neoforge'),
    [string[]]$Versions,
    [string]  $ModId,
    [int]     $TimeoutSeconds = 420,
    # Delete each line's test world first: for a mod whose first run changes the world for good
    # (a structure placed once, a state that only moves forward), every run must start from zero.
    [switch]  $FreshWorld,
    # Then stop, start again on the same world with <MODID>_SELFTEST_PHASE=2, and run the
    # self-test's after-restart checks. A test inside one session never sees what is lost on
    # reload: Clearness 3.0.0 lost its whole state on every restart and all checks stayed green.
    [switch]  $Restart
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

if (-not $ModId) {
    $ModId = (Select-String -Path (Join-Path $root 'gradle.properties') -Pattern '^mod_id=(.+)$').Matches.Groups[1].Value
}
if (-not $Versions) {
    $Versions = Select-String -Path (Join-Path $root 'build.gradle') -Pattern '^\s*"([0-9][^"]*)"\s*:\s*\[' -AllMatches |
        ForEach-Object { $_.Matches } | ForEach-Object { $_.Groups[1].Value }
}

$envName = ($ModId.ToUpper() -replace '[^A-Z0-9]', '') + '_SELFTEST'
Write-Host "Self-test: $($Loaders -join ', ') x $($Versions -join ', ')  (env $envName)" -ForegroundColor Cyan

$failed = $false
foreach ($version in $Versions) {
    foreach ($loader in $Loaders) {
        $runDir = Join-Path $root "$loader\run"
        $props  = Join-Path $runDir 'server.properties'
        # A fresh clone has no run/ at all, and a server without eula=true stops before the check.
        if (-not (Test-Path $runDir)) { New-Item -ItemType Directory -Path $runDir | Out-Null }
        $eula = Join-Path $runDir 'eula.txt'
        if (-not (Test-Path $eula)) { Set-Content -Path $eula -Value 'eula=true' -Encoding ascii }
        if (Test-Path $props) {
            (Get-Content $props) -replace '^level-name=.*', "level-name=world-$version" | Set-Content $props -Encoding utf8
        } else {
            Set-Content -Path $props -Value "level-name=world-$version" -Encoding ascii
        }

        if ($FreshWorld) {
            $world = Join-Path $runDir "world-$version"
            if (Test-Path $world) { Remove-Item $world -Recurse -Force }
        }
        $phases = if ($Restart) { @(1, 2) } else { @(1) }
        foreach ($phase in $phases) {
            $label = if ($phase -eq 2) { "$loader $version (after restart)" } else { "$loader $version" }
            $log = Join-Path $env:TEMP ("selftest-$ModId-$loader-$version" + $(if ($phase -eq 2) { '-restart' } else { '' }) + '.log')
            [Environment]::SetEnvironmentVariable($envName, 'true')
            [Environment]::SetEnvironmentVariable("${envName}_PHASE", "$phase")
            # The server stops itself once the check has run. If a mixin never applied, nothing stops it
            # and the run would hang forever - hence the kill switch. NeoForge's runServer also ignores
            # stdin, so a piped "stop" is not an option.
            $proc = Start-Process -FilePath (Join-Path $root 'gradlew.bat') `
                -ArgumentList ":${loader}:runServer", "-Pminecraft_version=$version", "--console=plain", "-q" `
                -WorkingDirectory $root -NoNewWindow -PassThru `
                -RedirectStandardOutput $log -RedirectStandardError "$log.err"
            if (-not $proc.WaitForExit($TimeoutSeconds * 1000)) {
                Write-Host "  $label : TIMEOUT after ${TimeoutSeconds}s - the server never halted, so the check never ran" -ForegroundColor Red
                try { $proc.Kill($true) } catch { }
                [Environment]::SetEnvironmentVariable($envName, $null)
                [Environment]::SetEnvironmentVariable("${envName}_PHASE", $null)
                $failed = $true
                break
            }
            [Environment]::SetEnvironmentVariable($envName, $null)
            [Environment]::SetEnvironmentVariable("${envName}_PHASE", $null)

            $result = Select-String -Path $log -Pattern '\[SELFTEST\] (RESULT|.*FAILED)' | ForEach-Object { $_.Line }
            if (-not $result) {
                Write-Host "  $label : NO RESULT - read $log" -ForegroundColor Red
                $failed = $true
                break
            } elseif (@($result | Where-Object { $_ -cmatch '\[SELFTEST\] RESULT PASS' }).Count -gt 0) {
                # Only the RESULT line decides, case-sensitive: a failure message can contain "Pass"
                # (an InteractionResult prints as "Pass[]"), and a plain -match found it.
                Write-Host "  $label : PASS" -ForegroundColor Green
            } else {
                Write-Host "  $label : $result" -ForegroundColor Red
                $failed = $true
                break
            }
        }
    }
}

if ($failed) { exit 1 }
