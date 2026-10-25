<#
.SYNOPSIS
    Builds every line of MATRIX and shows what each jar actually contains.

.DESCRIPTION
    Two traps this closes. First, a change is not finished until it builds on EVERY line: different
    mappings, a different JDK, a different Loom plugin. Second, and worse: runServer compiles from
    source, so a green self-test says nothing about the jars on disk - one can be stale and still
    sit there looking fine. Comparing class counts across lines catches that in a second.

.EXAMPLE
    .\build-all.ps1
#>
[CmdletBinding()]
param([switch]$Clean)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

$versions = Select-String -Path (Join-Path $root 'build.gradle') -Pattern '^\s*"([0-9][^"]*)"\s*:\s*\[' -AllMatches |
    ForEach-Object { $_.Matches } | ForEach-Object { $_.Groups[1].Value }

# Clean ONCE, before the loop: jars are named per line but share build/libs, so a clean inside the
# loop would delete every line built before it and the table below would show only the last one.
if ($Clean) {
    & (Join-Path $root 'gradlew.bat') clean --console=plain -q
    if ($LASTEXITCODE -ne 0) { throw "clean failed" }
}

foreach ($version in $versions) {
    Write-Host "=== build $version" -ForegroundColor Cyan
    & (Join-Path $root 'gradlew.bat') build "-Pminecraft_version=$version" --console=plain -q
    if ($LASTEXITCODE -ne 0) { throw "build failed for $version" }
}

Write-Host ""
Write-Host "=== what is in the jars (class counts must match across lines)" -ForegroundColor Cyan
Add-Type -AssemblyName System.IO.Compression.FileSystem
Get-ChildItem -Path (Join-Path $root 'fabric\build\libs'), (Join-Path $root 'neoforge\build\libs') -Filter *.jar |
    Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' } |
    ForEach-Object {
        $zip = [System.IO.Compression.ZipFile]::OpenRead($_.FullName)
        $classes = ($zip.Entries | Where-Object { $_.FullName -like '*.class' }).Count
        $zip.Dispose()
        "{0,-52} {1,3} classes" -f $_.Name, $classes
    }
