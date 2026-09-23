# Publishes the GitHub release for a locally built Glove-Statistics APK (v0.5.0).
#
# Usage:
#   1) build the APK:  gradlew.bat assembleRelease --offline
#   2) run this script: powershell -File .tooling/release/publish-v0.5.0.ps1
#
# Notes / lessons baked in (same constraints as the v0.3.0 / v0.4.0 scripts):
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
#   * The commit must already be pushed to `main`: the release is created with
#     target_commitish = main, so the tag is placed on whatever main points at.
#
# NOTE: on this machine this script CANNOT work. Both Invoke-RestMethod and
# curl.exe use Windows Schannel, which fails here with
# "schannel: AcquireCredentialsHandle failed: SEC_E_NO_CREDENTIALS"
# (git and Node use OpenSSL and are unaffected). Use the sibling script instead:
#   $env:GH_TOKEN = '<token from git-credential-manager>'
#   node .tooling/release/publish-v0.5.0.mjs
$ErrorActionPreference = 'Stop'

$repo      = 'Mr5U/Glove-statistics'
$tag       = 'v0.5.0'
$target    = 'main'
$root      = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$assetPath = Join-Path $root 'app\build\outputs\apk\release\app-release.apk'
$assetName = 'Glove-Statistics-0.5.0-release.apk'
$notesPath = Join-Path $PSScriptRoot 'RELEASE_NOTES_v0.5.0.md'
$gcm       = 'Z:\SoftWare\IDE\Git\mingw64\bin\git-credential-manager.exe'

# Release title, built from code points so the file stays ASCII:
#   U+7F1D U+624B U+5957 U+8BB0 U+5DE5              = app name ("sew glove work log")
#   U+0020 U+0076 U+0030 U+002E U+0035 U+002E U+0030 = " v0.5.0"
#   U+0020 U+2014 U+0020                            = " - "
#   U+7CBE U+7B80 U+FF1A                            = "lean:"
#   U+53BB U+5382 U+623F U+FF0C                     = "factory work removed,"
#   U+5BFC U+51FA U+56FE U+53EA U+7559 U+8BA1 U+4EF6 U+660E U+7EC6 = "export keeps only the piecework detail"
$titleCps = @(0x7F1D,0x624B,0x5957,0x8BB0,0x5DE5,0x20,0x76,0x30,0x2E,0x35,0x2E,0x30,0x20,0x2014,0x20,
              0x7CBE,0x7B80,0xFF1A,0x53BB,0x5382,0x623F,0xFF0C,
              0x5BFC,0x51FA,0x56FE,0x53EA,0x7559,0x8BA1,0x4EF6,0x660E,0x7EC6)
$title = -join ($titleCps | ForEach-Object { [char]$_ })

if (-not (Test-Path $assetPath)) { throw "APK not found: $assetPath (build it first)" }
if (-not (Test-Path $notesPath)) { throw "release notes not found: $notesPath" }
if (-not (Test-Path $gcm)) { throw "git-credential-manager not found: $gcm" }

$apk = Get-Item $assetPath
Write-Host ("asset to upload: {0} ({1} bytes)" -f $apk.FullName, $apk.Length)
Write-Host ("asset sha256   : {0}" -f (Get-FileHash $apk.FullName -Algorithm SHA256).Hash)
Write-Host ("release title  : {0}" -f $title)

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
