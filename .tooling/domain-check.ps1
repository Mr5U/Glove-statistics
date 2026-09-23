# Offline domain check: compile + run the pure-Kotlin domain/report logic.
#
# Why this exists: this machine cannot reach Maven, so `gradlew test` cannot resolve
# JUnit. But Domain.kt and ReportLayout.kt deliberately have NO Android dependency,
# so they can be compiled with the cached Kotlin compiler alone and executed on the
# plain JVM. That gives us real, executable regression tests for the merge rules and
# the report column geometry (the "exported image is cut off on the right" bug).
#
# Keep this file ASCII-only: Windows PowerShell 5.1 reads .ps1 as ANSI unless a UTF-8
# BOM is present, which corrupts non-ASCII characters.
#
# Usage:  powershell -File .tooling/domain-check.ps1
# Exit code 0 means every assertion in .tooling/check/DomainCheck.kt passed.

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$m = Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1'

function Find-Jar([string]$group, [string]$artifact, [string]$version) {
    $dir = Join-Path (Join-Path $m $group) "$artifact\$version"
    if (-not (Test-Path $dir)) { return $null }
    $jar = Get-ChildItem $dir -Recurse -Filter '*.jar' |
        Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' } |
        Select-Object -First 1
    if ($null -eq $jar) { return $null }
    return $jar.FullName
}

$kotlinVersion = '2.2.21'
$compiler = Find-Jar 'org.jetbrains.kotlin' 'kotlin-compiler-embeddable' $kotlinVersion
$stdlib = Find-Jar 'org.jetbrains.kotlin' 'kotlin-stdlib' $kotlinVersion
if (-not $stdlib) { $stdlib = Find-Jar 'org.jetbrains.kotlin' 'kotlin-stdlib' '1.9.23' }
$reflect = Find-Jar 'org.jetbrains.kotlin' 'kotlin-reflect' $kotlinVersion
if (-not $reflect) { $reflect = Find-Jar 'org.jetbrains.kotlin' 'kotlin-reflect' '1.9.23' }
$trove = Find-Jar 'org.jetbrains.intellij.deps' 'trove4j' '1.0.20200330'
$daemon = Find-Jar 'org.jetbrains.kotlin' 'kotlin-daemon-embeddable' $kotlinVersion
$annotations = Find-Jar 'org.jetbrains' 'annotations' '13.0'
# The Kotlin compiler itself needs coroutines on its own classpath.
$coroutines = Find-Jar 'org.jetbrains.kotlinx' 'kotlinx-coroutines-core-jvm' '1.6.4'
if (-not $coroutines) { $coroutines = Find-Jar 'org.jetbrains.kotlinx' 'kotlinx-coroutines-core-jvm' '1.10.2' }

if (-not $compiler) { throw "kotlin-compiler-embeddable $kotlinVersion not found in the Gradle cache" }
if (-not $stdlib) { throw 'kotlin-stdlib not found in the Gradle cache' }

$compilerJars = @($compiler, $stdlib, $reflect, $trove, $daemon, $annotations, $coroutines) |
    Where-Object { $_ }

$src = Join-Path $root 'app\src\main\java\com\mr5u\glovestatistics'
$sources = @(
    (Join-Path $src 'Domain.kt'),
    (Join-Path $src 'ReportLayout.kt'),
    (Join-Path $PSScriptRoot 'check\DomainCheck.kt')
)
foreach ($file in $sources) {
    if (-not (Test-Path $file)) { throw "missing source: $file" }
}

$out = Join-Path $PSScriptRoot 'out-domain'
Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $out -Force | Out-Null

Write-Host '=== step 1: compile Domain.kt + ReportLayout.kt + DomainCheck.kt ===' -ForegroundColor Cyan
$compileOutput = & java '-Dfile.encoding=UTF-8' -cp ($compilerJars -join ';') org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -nowarn -jvm-target 21 -classpath $stdlib -d $out @sources 2>&1
$compileExit = $LASTEXITCODE
if ($compileExit -ne 0) {
    $compileOutput | Where-Object { $_ -match 'error:|exception:' } | ForEach-Object { $_ }
    Write-Host 'FAIL: the domain sources do not compile' -ForegroundColor Red
    exit $compileExit
}
$classes = (Get-ChildItem $out -Recurse -Filter '*.class').Count
Write-Host "compiled $classes classes" -ForegroundColor DarkGray

Write-Host '=== step 2: run the assertions ===' -ForegroundColor Cyan
# -Dfile.encoding / stdout.encoding: keep the Chinese assertion messages readable in the console.
$runCp = "$out;$stdlib"
& java '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -cp $runCp check.DomainCheck
$runExit = $LASTEXITCODE

Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue

if ($runExit -eq 0) {
    Write-Host 'OK: domain + report layout checks passed' -ForegroundColor Green
} else {
    Write-Host 'FAIL: an assertion did not hold (see above)' -ForegroundColor Red
}
exit $runExit
