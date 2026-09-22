# Probe: can we type-check app sources against the REAL cached Compose libraries?
$ErrorActionPreference = 'Stop'
$m = Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1'
$out = Join-Path $PSScriptRoot 'aarjava'
Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $out -Force | Out-Null

Add-Type -AssemblyName System.IO.Compression.FileSystem

$want = @(
    'androidx.compose.ui:ui-android', 'androidx.compose.ui:ui-geometry-android',
    'androidx.compose.ui:ui-graphics-android', 'androidx.compose.ui:ui-text-android',
    'androidx.compose.ui:ui-unit-android', 'androidx.compose.ui:ui-util-android',
    'androidx.compose.foundation:foundation-android', 'androidx.compose.foundation:foundation-layout-android',
    'androidx.compose.runtime:runtime-android', 'androidx.compose.runtime:runtime-saveable-android',
    'androidx.compose.runtime:runtime-annotation-android',
    'androidx.compose.animation:animation-android', 'androidx.compose.animation:animation-core-android',
    'androidx.compose.material3:material3-android', 'androidx.compose.material:material-ripple-android',
    'androidx.activity:activity', 'androidx.activity:activity-compose', 'androidx.activity:activity-ktx',
    'androidx.lifecycle:lifecycle-runtime-android', 'androidx.lifecycle:lifecycle-viewmodel-android',
    'androidx.lifecycle:lifecycle-viewmodel-compose-android', 'androidx.lifecycle:lifecycle-runtime-compose-android',
    'androidx.lifecycle:lifecycle-viewmodel-savedstate-android', 'androidx.lifecycle:lifecycle-common-jvm',
    'androidx.savedstate:savedstate-android', 'androidx.savedstate:savedstate-compose-android',
    'androidx.core:core', 'androidx.core:core-ktx', 'androidx.core:core-viewtree',
    'androidx.annotation:annotation-experimental', 'androidx.annotation:annotation-jvm',
    'androidx.collection:collection-jvm', 'androidx.arch.core:core-common', 'androidx.arch.core:core-runtime',
    'androidx.profileinstaller:profileinstaller', 'androidx.emoji2:emoji2',
    'androidx.tracing:tracing', 'androidx.graphics:graphics-path'
)

$jars = @()
foreach ($w in $want) {
    $parts = $w -split ':'
    $group = $parts[0]; $art = $parts[1]
    # Gradle's cache keeps the group id dotted (unlike Maven's slash form).
    $adir = Join-Path (Join-Path $m $group) $art
    if (-not (Test-Path $adir)) { Write-Host "missing artifact: $w" -ForegroundColor DarkYellow; continue }
    # highest version
    $ver = Get-ChildItem $adir -Directory | Sort-Object Name -Descending | Select-Object -First 1
    $aar = Get-ChildItem $ver.FullName -Recurse -Filter '*.aar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch '-(sources|javadoc)' } | Select-Object -First 1
    $name = "$art-$($ver.Name)"
    $dest = Join-Path $out $name
    New-Item -ItemType Directory -Path $dest -Force | Out-Null
    if (-not $aar) {
        # Some androidx modules ship as a plain jar (lifecycle-common-jvm, collection-jvm, ...).
        $plain = Get-ChildItem $ver.FullName -Recurse -Filter '*.jar' -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notmatch '-(sources|javadoc)' } | Select-Object -First 1
        if ($plain) {
            # Copy it into the output dir so callers can just enumerate that folder.
            Copy-Item $plain.FullName (Join-Path $dest $plain.Name) -Force
            $jars += (Join-Path $dest $plain.Name)
            continue
        }
        Write-Host "no aar/jar: $w" -ForegroundColor DarkYellow
        Remove-Item $dest -Recurse -Force -ErrorAction SilentlyContinue
        continue
    }
    try {
        $zip = [System.IO.Compression.ZipFile]::OpenRead($aar.FullName)
        $entry = $zip.Entries | Where-Object { $_.FullName -eq 'classes.jar' } | Select-Object -First 1
        if ($entry) {
            $target = Join-Path $dest 'classes.jar'
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $true)
            $jars += $target
        } else {
            Write-Host "no classes.jar in $w" -ForegroundColor DarkYellow
        }
        $zip.Dispose()
    } catch { Write-Host "failed $w : $_" -ForegroundColor Red }
}

Write-Host "extracted $($jars.Count) classes.jar" -ForegroundColor Cyan
# NOTE: do not write this to a text file - the project path contains Chinese characters and
# PowerShell 5.1's Set-Content -Encoding ASCII would corrupt them. Callers rebuild it in memory.
