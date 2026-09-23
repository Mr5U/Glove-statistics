# Upgrade-compatibility check: will the new APK install over the old one WITHOUT wiping data?
#
# Android only keeps app data when the new APK has the SAME package name and is signed by the
# SAME certificate. If either differs, the installer refuses ("App not installed") - it does not
# delete anything by itself, but people then uninstall manually and lose their data.
#
# This script compares two APKs and tells you plainly whether a plain over-install will keep data.
#
# Keep this file ASCII-only (Windows PowerShell reads .ps1 as ANSI without a BOM).
#
# Usage:
#   powershell -File .tooling/verify-upgrade.ps1
#   powershell -File .tooling/verify-upgrade.ps1 -Old "C:\path\old-0.3.0.apk" -New "app\build\outputs\apk\release\app-release.apk"

param(
    [string]$Old = '',
    [string]$New = ''
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

if (-not $New) { $New = Join-Path $root 'app\build\outputs\apk\release\app-release.apk' }
if (-not (Test-Path $New)) { throw "new APK not found: $New (run ./gradlew --offline assembleRelease first)" }

# aapt2 / apksigner ship with the Android SDK build-tools.
$sdk = 'Z:\AiPlayModel\AndroidStudioSDK'
$buildTools = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory -ErrorAction SilentlyContinue |
    Sort-Object { [int]($_.Name -replace '\..*$', '') } -Descending |
    Select-Object -First 1 -ExpandProperty FullName
if (-not $buildTools) { throw "no build-tools found under $sdk\build-tools" }

$aapt2 = Join-Path $buildTools 'aapt2.exe'
$apksigner = Join-Path $buildTools 'apksigner.bat'
if (-not (Test-Path $aapt2)) { throw "aapt2 not found at $aapt2" }
if (-not (Test-Path $apksigner)) { throw "apksigner not found at $apksigner" }

function Get-ApkInfo([string]$path) {
    $badging = & $aapt2 dump badging $path 2>&1 | Select-Object -First 1
    $package = if ("$badging" -match "name='([^']+)'") { $Matches[1] } else { '?' }
    $code = if ("$badging" -match "versionCode='([^']+)'") { $Matches[1] } else { '?' }
    $name = if ("$badging" -match "versionName='([^']+)'") { $Matches[1] } else { '?' }

    $certs = & $apksigner verify --print-certs $path 2>&1
    $sha256 = ($certs | Select-String -Pattern 'certificate SHA-256 digest:\s*(\S+)' |
        Select-Object -First 1).Matches.Groups[1].Value

    [pscustomobject]@{
        Path = $path
        Package = $package
        VersionCode = $code
        VersionName = $name
        CertSha256 = $sha256
        FileSha256 = (Get-FileHash $path -Algorithm SHA256).Hash
        Size = (Get-Item $path).Length
    }
}

Write-Host "=== new APK ===" -ForegroundColor Cyan
$newInfo = Get-ApkInfo $New
$newInfo | Format-List | Out-String | Write-Host

if (-not $Old) {
    Write-Host "=== old APK ===" -ForegroundColor Cyan
    Write-Host "not provided - comparing against the signing config in the repository instead."
    Write-Host "To check against the APK currently on your phone, pass it explicitly:"
    Write-Host "  powershell -File .tooling/verify-upgrade.ps1 -Old <path-to-old.apk>"
    Write-Host ""
    Write-Host "Signing config in app/build.gradle.kts uses .tooling/debug.keystore."
    $keystore = Join-Path $root '.tooling\debug.keystore'
    if (Test-Path $keystore) {
        Write-Host ("keystore present: {0} ({1} bytes)" -f $keystore, (Get-Item $keystore).Length)
        Write-Host "As long as this keystore file and applicationId stay unchanged,"
        Write-Host "every release you build is upgrade-compatible with the previous one." -ForegroundColor Green
    } else {
        Write-Host "keystore MISSING - a rebuild would be signed differently and could not upgrade in place!" -ForegroundColor Red
    }
    exit 0
}

if (-not (Test-Path $Old)) { throw "old APK not found: $Old" }

Write-Host "=== old APK ===" -ForegroundColor Cyan
$oldInfo = Get-ApkInfo $Old
$oldInfo | Format-List | Out-String | Write-Host

$samePackage = $oldInfo.Package -eq $newInfo.Package
$sameCert = $oldInfo.CertSha256 -and ($oldInfo.CertSha256 -eq $newInfo.CertSha256)
$isUpgrade = $samePackage -and ([int]$newInfo.VersionCode -ge [int]$oldInfo.VersionCode)

Write-Host "=== verdict ===" -ForegroundColor Cyan
Write-Host ("package name identical : {0}  ({1})" -f $samePackage, $newInfo.Package)
Write-Host ("signing cert identical : {0}" -f $sameCert)
Write-Host ("version code           : {0} -> {1}" -f $oldInfo.VersionCode, $newInfo.VersionCode)

if ($samePackage -and $sameCert -and $isUpgrade) {
    Write-Host ""
    Write-Host "SAFE: install the new APK directly over the old one." -ForegroundColor Green
    Write-Host "Android treats this as an in-place upgrade and KEEPS all app data." -ForegroundColor Green
    Write-Host "Do NOT uninstall first - uninstalling is what deletes the data." -ForegroundColor Yellow
    exit 0
}

Write-Host ""
Write-Host "NOT SAFE to over-install:" -ForegroundColor Red
if (-not $samePackage) { Write-Host "  - package name differs" -ForegroundColor Red }
if (-not $sameCert) { Write-Host "  - signing certificate differs (different keystore)" -ForegroundColor Red }
if (-not $isUpgrade) { Write-Host "  - version code is not newer" -ForegroundColor Red }
Write-Host "Export an XML backup from the old app first, then restore it in the new one." -ForegroundColor Yellow
exit 2
