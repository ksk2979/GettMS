# Gett Server: 서버 mods 폴더를 런처 설정(GitHub 의 launcher.config.json)과 똑같이 맞춘다.
# 사용법: 서버 폴더에 이 파일을 두고, 서버를 끈 상태에서 더블클릭(또는 우클릭 → PowerShell 로 실행).
# 하는 일: 설정에 모드 고정 목록(lock)이 있으면 그 목록의 서버용 항목(both+server)을 그대로 받고, 없으면 예전처럼 mods 목록 +
#          필수 의존성을 Modrinth 에서 1.21.1 NeoForge 최신 빌드로 받는다(sha512 검증). 목록 밖 jar 는 _removed 폴더로 옮긴다.

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$ConfigUrl = 'https://raw.githubusercontent.com/ksk2979/GettMS/main/launcher/launcher.config.json'
$Headers = @{ 'User-Agent' = 'GettServerSync/1.0' }
$ModsDir = Join-Path $PSScriptRoot 'mods'
$RemovedDir = Join-Path $PSScriptRoot '_removed'
New-Item -ItemType Directory -Force $ModsDir | Out-Null

Write-Host "설정 읽는 중: $ConfigUrl"
$cfg = Invoke-RestMethod -Uri "${ConfigUrl}?t=$([int](Get-Date -UFormat %s))" -Headers $Headers
$mc = $cfg.minecraftVersion
$serverOnly = @($cfg.serverOnlyMods | Where-Object { $_ })
$serverList = @($cfg.mods) + $serverOnly
Write-Host "Minecraft $mc / NeoForge $($cfg.neoforgeVersion) / 서버 모드 $($serverList.Count)개 (공통 $($cfg.mods.Count) + 서버전용 $($serverOnly.Count))"

$want = @{}   # filename -> @{url; sha512; slug; version}
$useLock = $cfg.lock -and @($cfg.lock).Count -gt 0
if ($useLock) {
  Write-Host "모드 고정 목록(lock) 사용: $(@($cfg.lock).Count)개 중 서버용만"
  foreach ($e in $cfg.lock) {
    if ($e.side -eq 'both' -or $e.side -eq 'server') {
      $want[$e.file] = @{ url = $e.url; sha512 = $e.sha512; slug = $e.slug; version = 'lock' }
    }
  }
}
$queue = [System.Collections.Generic.Queue[string]]::new()
$seen = @{}
if (-not $useLock) { foreach ($s in $serverList) { if ($s) { $queue.Enqueue($s) } } }

while ($queue.Count -gt 0) {
  $slug = $queue.Dequeue()
  if ($seen.ContainsKey($slug)) { continue }
  $seen[$slug] = $true
  $all = Invoke-RestMethod -Uri "https://api.modrinth.com/v2/project/$slug/version?loaders=%5B%22neoforge%22%5D&game_versions=%5B%22$mc%22%5D" -Headers $Headers
  $vs = @($all | Where-Object { $_.loaders -contains 'neoforge' -and $_.game_versions -contains $mc })
  if ($vs.Count -eq 0) { throw "모드 '$slug' 의 $mc NeoForge 빌드가 없음" }
  $v = $vs | Where-Object { $_.version_type -eq 'release' } | Select-Object -First 1
  if (-not $v) { $v = $vs[0] }
  $file = $v.files | Where-Object { $_.primary } | Select-Object -First 1
  if (-not $file) { $file = $v.files[0] }
  $want[$file.filename] = @{ url = $file.url; sha512 = $file.hashes.sha512; slug = $slug; version = $v.version_number }
  foreach ($d in $v.dependencies) {
    if ($d.dependency_type -eq 'required' -and $d.project_id) {
      $p = Invoke-RestMethod -Uri "https://api.modrinth.com/v2/project/$($d.project_id)" -Headers $Headers
      if (-not $seen.ContainsKey($p.slug)) { Write-Host "  의존성: $slug -> $($p.slug)"; $queue.Enqueue($p.slug) }
    }
  }
}

$customServer = @()
if (-not $useLock) { $customServer = @($cfg.customMods | Where-Object { $_ -and ($_.side -eq 'both' -or $_.side -eq 'server') }) }
foreach ($c in $customServer) {
  $want[$c.file] = @{ url = $c.url; sha512 = $c.sha512; slug = $c.name; version = 'custom' }
}

$downloaded = 0; $removed = 0
foreach ($name in $want.Keys) {
  $dest = Join-Path $ModsDir $name
  if (Test-Path $dest) { continue }
  $w = $want[$name]
  Write-Host "받는 중: $($w.slug) $($w.version)"
  $tmp = "$dest.part"
  # 일시적인 실패(GitHub 504 등)는 3번까지 다시 시도
  for ($try = 1; ; $try++) {
    try { Invoke-WebRequest -Uri $w.url -OutFile $tmp -Headers $Headers; break }
    catch {
      if ($try -ge 3) { throw }
      Write-Host "  다운로드 실패, 다시 시도 ($try/3): $($_.Exception.Message)"
      Start-Sleep -Seconds (2 * $try)
    }
  }
  $hash = (Get-FileHash $tmp -Algorithm SHA512).Hash.ToLower()
  if ($hash -ne $w.sha512.ToLower()) { Remove-Item $tmp -Force; throw "해시 불일치: $name" }
  Move-Item $tmp $dest -Force
  $downloaded++
}
foreach ($f in Get-ChildItem $ModsDir -Filter *.jar) {
  if (-not $want.ContainsKey($f.Name)) {
    New-Item -ItemType Directory -Force $RemovedDir | Out-Null
    Move-Item $f.FullName (Join-Path $RemovedDir $f.Name) -Force
    Write-Host "제거(이동): $($f.Name) -> _removed\"
    $removed++
  }
}
Write-Host ""
Write-Host "완료: 총 $($want.Count)개, 새로 받음 $downloaded, 제거 $removed"
Write-Host "이제 서버를 시작하세요."
try { if ($Host.Name -eq 'ConsoleHost') { Read-Host '엔터를 누르면 닫힙니다' | Out-Null } } catch {}
