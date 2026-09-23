# Publishes the GitHub release for a locally built Glove-Statistics APK.
#
# Usage:
#   1) build the APK:  gradlew.bat assembleRelease --offline
#   2) run this script: powershell -File .tooling/release/publish-v0.4.0.ps1
#
# Notes / lessons baked in (same constraints as the v0.3.0 script):
#   * The GitHub token is read straight from Git Credential Manager, so it is
#     never typed, printed or committed.
#   * `git credential` and any credential.helper that spawns a shell fail in this
#     sandbox ("sh.exe: couldn't create signal pipe"). Calling the credential
#     manager binary directly and reading `password=` from its output works.
#   * PowerShell 5.1's Invoke-RestMethod defaults to a TLS version GitHub rejects,
#     hence the explicit SecurityProtocol below. `git` uses OpenSSL and does not
#     have this problem, which is why git works while the API needs the line.
#   * Send JSON as explicit UTF-8 bytes from ConvertTo-Json. Passing a hashtable
#     to -Body silently sends the hashtable's own ToString() form.
#   * Only GET is retried. POST/PATCH are sent exactly once: a retry after a
#     successful create yields HTTP 422 (duplicate tag), and a retry after a
#     successful PATCH can silently undo it.
#   * Any non-ASCII literal in a .ps1 read as ANSI comes back corrupted. The
#     non-English release title is therefore rebuilt from Unicode code points.
$ErrorActionPreference = 'Stop'

$repo      = 'Mr5U/Glove-statistics'
$tag       = 'v0.4.0'
$target    = 'main'
$root      = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$assetPath = Join-Path $root 'app\build\outputs\apk\release\app-release.apk'
$assetName = 'Glove-Statistics-0.4.0-release.apk'
$notesPath = Join-Path $PSScriptRoot 'RELEASE_NOTES_v0.4.0.md'
$gcm       = 'Z:\SoftWare\IDE\Git\mingw64\bin\git-credential-manager.exe'

# Release title, built from code points so the file stays ASCII:
#   U+7F1D U+624B U+5957 U+8BB0 U+5DE5            = "缝手套记工"
#   U+0020 U+0076 U+0030 U+002E U+0034 U+002E U+0030 = " v0.4.0"
#   U+0020 U+2014 U+0020                          = " - "
#   U+5382 U+623F U+8BA1 U+4EF6 U+0020 U+002B U+0020 U+540C U+65E5 U+5408 U+5E76
#       = "厂房计件 + 同日合并"
#   U+FF08 U+624B U+5957 U+0020 U+002B U+0020 U+5382 U+623F U+53CC U+8D26 U+672C U+FF09
#       = "(手套 + 厂房双账本)"
$titleCps = @(0x7F1D,0x624B,0x5957,0x8BB0,0x5DE5,0x20,0x76,0x30,0x2E,0x34,0x2E,0x30,0x20,0x2014,0x20,
              0x5382,0x623F,0x8BA1,0x4EF6,0x20,0x2B,0x20,0x540C,0x65E5,0x5408,0x5E76,0x20,
              0xFF08,0x624B,0x5957,0x20,0x2B,0x20,0x5382,0x623F,0x53CC,0x8D26,0x672C,0xFF09)
$title = -join ($titleCps | ForEach-Object { [char]$_ })

if (-not (Test-Path $assetPath)) { throw "APK not found: $assetPath (build it first)" }
if (-not (Test-Path $notesPath)) { throw "release notes not found: $notesPath" }

[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$utf8 = New-Object Text.UTF8Encoding($false)

# --- credential ---------------------------------------------------------------
$raw = "protocol=https`nhost=github.com`n`n" | & $gcm get 2>&1
$token = $null
foreach ($line in $raw) { if ("$line".Trim() -match '^password=(.+)$') { $token = $Matches[1] } }
if (-not $token) { throw 'No GitHub credential in Git Credential Manager.' }

$headers = @{
    Authorization          = "token $token"
    'User-Agent'           = 'Glove-Statistics-release'
    Accept                 = 'application/vnd.github+json'
    'X-GitHub-Api-Version' = '2022-11-28'
}

function Send-Api {
    param([string]$Label, [string]$Uri, [string]$Method = 'GET', $Json, [string]$InFile, [string]$ContentType, [switch]$Retry)
    $attempts = if ($Retry) { 6 } else { 1 }
    for ($i = 1; $i -le $attempts; $i++) {
        try {
            $p = @{ Method = $Method; Uri = $Uri; Headers = $headers; TimeoutSec = 900; ErrorAction = 'Stop' }
            if ($null -ne $Json) {
                $p.Body = $utf8.GetBytes(($Json | ConvertTo-Json -Depth 3))
                $p.ContentType = 'application/json; charset=utf-8'
            }
            if ($InFile) { $p.InFile = $InFile; $p.ContentType = $ContentType }
            return Invoke-RestMethod @p
        } catch {
            $resp = $_.Exception.Response
            if ($resp) {
                $body = ''
                try { $sr = New-Object IO.StreamReader($resp.GetResponseStream()); $body = $sr.ReadToEnd() } catch { }
                throw "[$Label] HTTP $([int]$resp.StatusCode) $body"
            }
            if ($i -eq $attempts) { throw "[$Label] transport error after $attempts attempts: $($_.Exception.Message)" }
            Write-Host "[$Label] attempt $i failed, retrying..."
            Start-Sleep -Milliseconds 900
        }
    }
}

# --- release ------------------------------------------------------------------
$release = Send-Api 'get-release' "https://api.github.com/repos/$repo/releases/tags/$tag" -Retry
if (-not $release) {
    $release = Send-Api 'create-release' "https://api.github.com/repos/$repo/releases" 'POST' @{
        tag_name = $tag; target_commitish = $target; draft = $true; prerelease = $false
    }
    Write-Host "created release id=$($release.id)"
} else {
    Write-Host "found release id=$($release.id)"
}

$notes = (Get-Content -Raw -Encoding UTF8 $notesPath).Replace("`r`n", "`n")
$published = Send-Api 'publish-release' "https://api.github.com/repos/$repo/releases/$($release.id)" 'PATCH' @{
    name       = $title
    body       = $notes
    draft      = $false
    prerelease = $false
}
Write-Host "published: $($published.html_url)"

# --- asset --------------------------------------------------------------------
$apk = Get-Item $assetPath
foreach ($a in @(Send-Api 'list-assets' "https://api.github.com/repos/$repo/releases/$($published.id)/assets" -Retry)) {
    if ($a.name -eq $assetName) {
        Write-Host "replacing existing asset id=$($a.id)"
        Send-Api 'delete-asset' "https://api.github.com/repos/$repo/releases/assets/$($a.id)" 'DELETE' | Out-Null
    }
}
$uploaded = Send-Api 'upload-asset' `
    "https://uploads.github.com/repos/$repo/releases/$($published.id)/assets?name=$assetName" `
    'POST' -InFile $apk.FullName -ContentType 'application/vnd.android.package-archive'

Write-Host "asset: $($uploaded.name) ($($uploaded.size) bytes)"
Write-Host "download: $($uploaded.browser_download_url)"
Write-Host "RELEASE_URL=$($published.html_url)"
