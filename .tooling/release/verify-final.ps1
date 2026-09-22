$ErrorActionPreference = 'Continue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$repo = 'Mr5U/Glove-statistics'
$root = 'Z:\AAA_Mr5U\MyProject\Glove-Statistics2.0'
$utf8 = New-Object Text.UTF8Encoding($false)

$raw = "protocol=https`nhost=github.com`n`n" | & 'Z:\SoftWare\IDE\Git\mingw64\bin\git-credential-manager.exe' get 2>&1
$token = $null
foreach ($line in $raw) { if ("$line".Trim() -match '^password=(.+)$') { $token = $Matches[1] } }
$h = @{ Authorization = "token $token"; 'User-Agent' = 'final'; Accept = 'application/vnd.github+json'; 'X-GitHub-Api-Version' = '2022-11-28' }

$repoInfo = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo" -Headers $h -TimeoutSec 90
$tree = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/git/trees/main?recursive=1" -Headers $h -TimeoutSec 90
$rel = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases/tags/v0.3.0" -Headers $h -TimeoutSec 90
$assets = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases/$($rel.id)/assets" -Headers $h -TimeoutSec 90
$latest = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases/latest" -Headers $h -TimeoutSec 90

$lines = New-Object System.Collections.Generic.List[string]
$lines.Add("repo=" + $repoInfo.full_name + " private=" + $repoInfo.private + " default_branch=" + $repoInfo.default_branch + " lang=" + $repoInfo.language)
$lines.Add("main_sha=" + $tree.sha)
$lines.Add("tracked_files_on_main=" + (($tree.tree | Where-Object { $_.type -eq 'blob' }).Count))
$lines.Add("is_latest=" + ($latest.tag_name -eq 'v0.3.0') + " tag=" + $rel.tag_name)
$lines.Add("release_name=" + $rel.name)
$lines.Add("release_name_len=" + $rel.name.Length)
$lines.Add("draft=" + $rel.draft + " prerelease=" + $rel.prerelease + " published=" + $rel.published_at)
$lines.Add("body_len=" + $rel.body.Length + " body_utf8_bytes=" + $utf8.GetByteCount($rel.body))
$lines.Add("--- files on main ---")
$tree.tree | Where-Object { $_.type -eq 'blob' } | Sort-Object path | ForEach-Object { $lines.Add("  " + $_.path) }
$lines.Add("--- assets ---")
foreach ($a in @($assets)) { $lines.Add("  " + $a.name + "  " + $a.size + " bytes  state=" + $a.state + "  " + $a.browser_download_url) }

[IO.File]::WriteAllText("$root\.tooling\release\final-state.txt", ($lines -join "`n"), $utf8)
Write-Host "done"
