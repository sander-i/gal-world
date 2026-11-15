<#
.SYNOPSIS
  Gal World Portal —— Windows 远程更新器

.DESCRIPTION
  请求模式（默认，无 -Jar/-JarUrl）：读取 <Root>\data\update-request.json，
  按发布接口的 release-manifest.json 下载 jar，依次写
  <Root>\data\update-status.json 的 queued → precheck → downloading → verifying →
  backing_up → stopping → replacing → starting → success；失败回滚为 rolled_back / failed。

  手工模式：-Jar 或 -JarUrl 指定 jar，跳过发布接口查询，不写状态文件。

  备份目录 <Root>\data\backups\pre-update-<当前版本>-<时间戳>，内含 jar 与 H2，保留 3 份。
  只更新 jar 与 H2；证书、防火墙、计划任务、games\ 与存档都不动。

.PARAMETER JarUrl
  手工模式：新 jar 的下载地址（https）。与 -Jar 二选一。

.PARAMETER Jar
  手工模式：本地新 jar 路径。与 -JarUrl 二选一。

.PARAMETER Sha256
  手工模式：可选，校验新 jar 的 SHA256。

.PARAMETER Root
  安装根目录，默认 %USERPROFILE%\galworld。

.PARAMETER Port
  门户端口，默认 8088；用于停服等待与冒烟。

.PARAMETER Scheme
  冒烟使用的协议，http 或 https；未指定时按 GALWORLD_TLS 推断。

.PARAMETER TaskName
  计划任务名，默认 GalWorldPortal；不存在时直接拉起 java。

.PARAMETER BackupKeep
  保留的备份份数，默认 3。

.EXAMPLE
  powershell -File update-portal.ps1 -Root C:\Users\me\galworld -Port 8088 -Scheme http

.EXAMPLE
  .\update-portal.ps1 -Jar ..\..\portal\target\gal-world-portal-<version>.jar
#>

# SPDX-License-Identifier: MIT
# Copyright (c) 2026 sander

[CmdletBinding(DefaultParameterSetName = 'Request')]
param(
  [Parameter(ParameterSetName = 'Url', Mandatory = $true)]
  [string]$JarUrl,
  [Parameter(ParameterSetName = 'File', Mandatory = $true)]
  [string]$Jar,
  [Parameter(ParameterSetName = 'Url')]
  [Parameter(ParameterSetName = 'File')]
  [string]$Sha256,
  [string]$Root = (Join-Path $env:USERPROFILE 'galworld'),
  [int]$Port = 8088,
  [ValidateSet('http', 'https')]
  [string]$Scheme,
  [string]$TaskName = 'GalWorldPortal',
  [int]$BackupKeep = 3
)

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
# 冒烟要访问自签证书，必须跳过校验，否则永远失败并误回滚
[System.Net.ServicePointManager]::ServerCertificateValidationCallback = {
  param($sender, $cert, $chain, $errors) return $true
}
Add-Type -AssemblyName System.IO.Compression.FileSystem

$IsRequest = $PSCmdlet.ParameterSetName -eq 'Request'
if (-not $Scheme) {
  $Scheme = if ($env:GALWORLD_TLS -eq 'true') { 'https' } else { 'http' }
}

$Root = [System.IO.Path]::GetFullPath($Root)
$Lib = Join-Path $Root 'lib'
$TargetJar = Join-Path $Lib 'gal-world-portal.jar'
$DataDir = Join-Path $Root 'data'
$DbPath = Join-Path $DataDir 'portal\gal-world.mv.db'
$DbBase = Join-Path $DataDir 'portal\gal-world'
$Req = Join-Path $DataDir 'update-request.json'
$Status = Join-Path $DataDir 'update-status.json'
$LogFile = Join-Path $DataDir 'update.log'
$Tmp = Join-Path $DataDir 'update\tmp'
$BackupRoot = Join-Path $DataDir 'backups'
$BaseUrl = "${Scheme}://127.0.0.1:$Port"
# 更新源优先级：环境变量 > Portal 写入的请求文件 > 内置默认值。
# 连不上默认站时把请求文件里的 releaseApi 换掉即可，不必改这个脚本。
$ApiFromEnv = $env:GALWORLD_UPDATE_API
$DefaultApi = 'https://gitee.com/api/v5/repos/sander-o/gal-world'
$Api = ''
# 令牌只从环境变量读取，不写日志、不进 argv
$Token = $env:GALWORLD_UPDATE_TOKEN

$script:Phase = ''
$script:Terminal = $false
$script:PortalStopped = $false
$script:PortalStarted = $false
$script:JobId = ''
$script:JobStartedAt = ''
$script:Target = ''
$script:Current = ''
$script:Override = $false
$script:BackupDir = ''
$mutex = $null
$mutexHeld = $false

function Write-Log([string]$Message) {
  try {
    $dir = Split-Path -Parent $LogFile
    if (-not (Test-Path -LiteralPath $dir)) {
      New-Item -ItemType Directory -Force -Path $dir | Out-Null
    }
    Add-Content -LiteralPath $LogFile -Encoding UTF8 `
      -Value ("{0} {1}" -f (Get-Date).ToString('o'), $Message)
  } catch { }
}

function Read-JsonFile([string]$Path) {
  return Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json
}

function Get-AuthHeaders {
  if ($Token) { return @{ Authorization = "token $Token" } }
  return @{}
}

function Invoke-UpdateSource([string]$Url, [string]$OutFile) {
  $headers = Get-AuthHeaders
  if ($OutFile) {
    Invoke-WebRequest -Uri $Url -OutFile $OutFile -UseBasicParsing `
      -Headers $headers -TimeoutSec 1800
  } else {
    Invoke-RestMethod -Uri $Url -Headers $headers -TimeoutSec 30
  }
}

# 状态文件必须 UTF-8 无 BOM：Java 侧 Files.readString + Jackson 遇 BOM 会解析失败
function Write-Phase([string]$PhaseName, [string]$Message = '') {
  $script:Phase = $PhaseName
  if (-not $IsRequest) {
    Write-Log "phase=$PhaseName $Message"
    return
  }
  $now = (Get-Date).ToString('o')
  if (-not $script:JobStartedAt) { $script:JobStartedAt = $now }
  $body = [ordered]@{
    jobId          = $script:JobId
    phase          = $PhaseName
    version        = $script:Target
    toVersion      = $script:Target
    fromVersion    = $script:Current
    message        = $Message
    startedAt      = $script:JobStartedAt
    phaseStartedAt = $now
  }
  if (@('success', 'rolled_back', 'failed') -contains $PhaseName) {
    $body.finishedAt = $now
  }
  $json = $body | ConvertTo-Json -Compress
  $tmp = "$Status.tmp"
  [System.IO.File]::WriteAllText($tmp, $json, (New-Object System.Text.UTF8Encoding($false)))
  Move-Item -LiteralPath $tmp -Destination $Status -Force
  Write-Log "phase=$PhaseName $Message"
  if (@('success', 'rolled_back', 'failed') -contains $PhaseName) {
    $script:Terminal = $true
  }
}

function Get-NormalizedVersion([string]$Value) {
  if (-not $Value) { return '' }
  $v = $Value.Trim().ToLowerInvariant()
  if ($v.StartsWith('v')) { $v = $v.Substring(1) }
  $dash = $v.IndexOf('-')
  if ($dash -ge 0) { $v = $v.Substring(0, $dash) }
  return $v
}

function Test-VersionGt([string]$A, [string]$B) {
  $x = Get-NormalizedVersion $A
  $y = Get-NormalizedVersion $B
  if (-not $x) { return $false }
  if (-not $y) { return $true }
  try {
    return ([version]$x -gt [version]$y)
  } catch {
    return ([string]::CompareOrdinal($x, $y) -gt 0)
  }
}

function Get-JarVersion([string]$Path) {
  if (-not (Test-Path -LiteralPath $Path)) { return '' }
  $zip = [System.IO.Compression.ZipFile]::OpenRead($Path)
  try {
    $entry = $zip.GetEntry('BOOT-INF/classes/application.yml')
    if (-not $entry) { return '' }
    $reader = New-Object System.IO.StreamReader($entry.Open(), [System.Text.Encoding]::UTF8)
    try { $text = $reader.ReadToEnd() } finally { $reader.Dispose() }
    $m = [regex]::Match($text, '(?m)^\s+version:\s*(\S+)')
    if ($m.Success) { return $m.Groups[1].Value.Trim() }
    return ''
  } finally {
    $zip.Dispose()
  }
}

function Test-JarStatic([string]$Path) {
  $zip = [System.IO.Compression.ZipFile]::OpenRead($Path)
  try {
    return ($null -ne $zip.GetEntry('BOOT-INF/classes/static/index.html'))
  } finally {
    $zip.Dispose()
  }
}

# 覆盖更新时目标版本必须自带更新接口，否则降级后无法再从页面升回来
function Test-JarUpdater([string]$Path) {
  $zip = [System.IO.Compression.ZipFile]::OpenRead($Path)
  try {
    return ($null -ne $zip.GetEntry('BOOT-INF/classes/io/github/sander_i/update/UpdateService.class'))
  } finally {
    $zip.Dispose()
  }
}

# PATH 里的 java 可能是 JDK 8；优先 JAVA_HOME，再逐个验证主版本 >= 17
function Get-Java17 {
  $cands = New-Object System.Collections.Generic.List[string]
  if ($env:JAVA_HOME) { $cands.Add((Join-Path $env:JAVA_HOME 'bin\java.exe')) }
  $onPath = Get-Command java.exe -ErrorAction SilentlyContinue
  if ($onPath) { $cands.Add($onPath.Source) }
  foreach ($base in @(
      (Join-Path $env:ProgramFiles 'Java'),
      (Join-Path ${env:ProgramFiles(x86)} 'Java'),
      (Join-Path $env:ProgramFiles 'Eclipse Adoptium'),
      (Join-Path $env:ProgramFiles 'Microsoft'))) {
    if ($base -and (Test-Path $base)) {
      Get-ChildItem $base -Directory -ErrorAction SilentlyContinue | ForEach-Object {
        $cands.Add((Join-Path $_.FullName 'bin\java.exe'))
      }
    }
  }
  # JDK 可能装在非系统盘（本机就在 G:），把每个固定盘的常见目录也扫一遍
  foreach ($drive in (Get-PSDrive -PSProvider FileSystem -ErrorAction SilentlyContinue)) {
    foreach ($sub in 'Program Files\Java\corretto-17*', 'Program Files\Java\jdk-*',
                     'Program Files\Eclipse Adoptium\jdk-*', 'Program Files\Microsoft\jdk-*') {
      Get-Item (Join-Path $drive.Root "$sub\bin\java.exe") -ErrorAction SilentlyContinue |
        ForEach-Object { $cands.Add($_.FullName) }
    }
  }
  foreach ($c in $cands) {
    if (-not $c -or -not (Test-Path -LiteralPath $c)) { continue }
    # java -version 往 stderr 写；脚本级 Stop 会把重定向输出当异常
    $oldPref = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
      $out = (& $c -version 2>&1 | Select-Object -First 1) -join ''
      if ($out -match '"(\d+)' -and [int]$Matches[1] -ge 17) { return $c }
    } catch { } finally { $ErrorActionPreference = $oldPref }
  }
  return $null
}

function Test-PortBusy {
  $c = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
  return ($c.Count -gt 0)
}

# 只把「端口真正释放」视为停服成功；停不下来就不允许备份/替换
function Stop-Portal {
  $task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
  if ($task) {
    Stop-ScheduledTask -TaskName $TaskName -ErrorAction Stop
  } else {
    Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction SilentlyContinue |
      Where-Object { $_.CommandLine -and $_.CommandLine.Contains($TargetJar) } |
      ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
  }
  for ($i = 0; $i -lt 30; $i++) {
    if (-not (Test-PortBusy)) { return $true }
    Start-Sleep -Seconds 1
  }
  return $false
}

function Start-Portal {
  $task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
  if ($task) {
    Start-ScheduledTask -TaskName $TaskName -ErrorAction Stop
    return
  }
  $java = Get-Java17
  if (-not $java) {
    throw '找不到 JDK 17+；请设置 JAVA_HOME 或安装到 Program Files\Java'
  }
  # 直接启动必须把根目录与端口传给子进程，否则会用到默认 %USERPROFILE%\galworld
  $env:GALWORLD_ROOT = $Root
  $env:GALWORLD_PORTAL_PORT = "$Port"
  Start-Process -FilePath $java -WorkingDirectory $Root -WindowStyle Hidden -ArgumentList @(
    '-Xms128m', '-Xmx512m', '-Dfile.encoding=UTF-8', '-jar', "`"$TargetJar`""
  ) | Out-Null
}

function Test-Smoke([string]$Want) {
  for ($i = 0; $i -lt 60; $i++) {
    try {
      $health = Invoke-RestMethod -Uri "$BaseUrl/api/health" -TimeoutSec 3
      if ([string]$health.version -eq $Want) {
        $g = (Invoke-WebRequest -Uri "$BaseUrl/api/games" -UseBasicParsing -TimeoutSec 3).StatusCode
        $s = (Invoke-WebRequest -Uri "$BaseUrl/api/settings" -UseBasicParsing -TimeoutSec 3).StatusCode
        $r = (Invoke-WebRequest -Uri "$BaseUrl/" -UseBasicParsing -TimeoutSec 3).StatusCode
        if ($g -eq 200 -and $s -eq 200 -and $r -eq 200) { return $true }
      }
    } catch { }
    Start-Sleep -Seconds 2
  }
  return $false
}

function Assert-DiskFree {
  try {
    $driveRoot = [System.IO.Path]::GetPathRoot($Root)
    $info = New-Object System.IO.DriveInfo($driveRoot)
    if ($info.AvailableFreeSpace -lt 2147483648) {
      throw ("磁盘余量不足 2 GiB（可用 {0} GiB）" -f [math]::Round($info.AvailableFreeSpace / 1GB, 1))
    }
  } catch [System.ArgumentException] {
    Write-Log '跳过磁盘余量检查（无法识别根目录所在磁盘）'
  }
}

function Invoke-Rollback {
  Write-Log "开始回滚到 $($script:Current)"
  if (-not (Stop-Portal)) {
    $script:PortalStopped = $true
    $script:PortalStarted = $false
    Write-Phase 'failed' "回滚失败：无法停止门户，需人工恢复: $($script:BackupDir)"
    exit 1
  }
  $script:PortalStopped = $true
  $script:PortalStarted = $false

  $jarBackup = Join-Path $script:BackupDir 'gal-world-portal.jar'
  if (-not (Test-Path -LiteralPath $jarBackup)) {
    Write-Phase 'failed' "回滚失败：备份缺少 jar，服务已停止，需人工恢复: $($script:BackupDir)"
    exit 1
  }
  Copy-Item -LiteralPath $jarBackup -Destination "$TargetJar.tmp" -Force
  Move-Item -LiteralPath "$TargetJar.tmp" -Destination $TargetJar -Force

  $dbBackup = Join-Path $script:BackupDir 'gal-world.mv.db'
  if (Test-Path -LiteralPath $dbBackup) {
    Copy-Item -LiteralPath $dbBackup -Destination "$DbPath.tmp" -Force
    Move-Item -LiteralPath "$DbPath.tmp" -Destination $DbPath -Force
    # H2 残留文件是 gal-world.trace.db / gal-world.lock.db，去掉 .mv.db 后缀再拼
    Remove-Item -LiteralPath "$DbBase.trace.db", "$DbBase.lock.db" `
      -Force -ErrorAction SilentlyContinue
  }

  try {
    Start-Portal
    $script:PortalStarted = $true
  } catch {
    Write-Log "回滚启动失败: $($_.Exception.Message)"
  }

  if ($script:PortalStarted -and (Test-Smoke $script:Current)) {
    Write-Log "回滚成功，当前 $($script:Current)"
    Write-Phase 'rolled_back' "已回滚到 $($script:Current)"
    exit 1
  }

  if (Stop-Portal) { $script:PortalStopped = $true }
  Write-Phase 'failed' "回滚失败，服务已停止，需人工恢复: $($script:BackupDir)"
  exit 1
}

try {
  $mutex = New-Object System.Threading.Mutex($false, 'Local\GalWorldUpdater')
  try {
    $acquired = $mutex.WaitOne(0)
  } catch [System.Threading.AbandonedMutexException] {
    # 上一个更新器被强杀，锁已释放，按已获取处理
    $acquired = $true
  }
  if (-not $acquired) {
    Write-Log '另一个更新器正在运行（锁冲突），退出'
    # 只有状态里的 queued 属于本次请求时才补写 failed，避免覆盖其它任务
    if ($IsRequest) {
      try {
        if ((Test-Path -LiteralPath $Req) -and (Test-Path -LiteralPath $Status)) {
          $rq = Read-JsonFile $Req
          $st = Read-JsonFile $Status
          $script:JobId = [string]$rq.jobId
          $script:Target = [string]$rq.version
          $script:JobStartedAt = [string]$rq.requestedAt
          if ($st.phase -eq 'queued' -and $st.jobId -and $st.jobId -eq $rq.jobId) {
            Write-Phase 'failed' '另一个更新器正在运行（锁冲突），请稍后重试'
          }
        }
      } catch { }
    }
    exit 1
  }
  $mutexHeld = $true

  New-Item -ItemType Directory -Force -Path $Tmp | Out-Null
  New-Item -ItemType Directory -Force -Path (Join-Path $DataDir 'portal') | Out-Null
  $newJar = Join-Path $Tmp 'new.jar'

  if ($IsRequest) {
    if (-not (Test-Path -LiteralPath $Req)) { throw "缺少 $Req" }
    $rq = Read-JsonFile $Req
    $script:JobId = [string]$rq.jobId
    $script:Target = Get-NormalizedVersion ([string]$rq.version)
    $script:Override = [bool]$rq.override
    $Tag = [string]$rq.tag
    $script:JobStartedAt = [string]$rq.requestedAt
    if (-not $script:JobStartedAt) { $script:JobStartedAt = (Get-Date).ToString('o') }
    if (-not $script:Target -or -not $Tag) { throw 'update-request 缺少 version/tag' }
    $Api = if ($ApiFromEnv) { $ApiFromEnv.TrimEnd('/') }
      elseif ($rq.releaseApi) { ([string]$rq.releaseApi).TrimEnd('/') }
      else { $DefaultApi }
  } elseif ($PSCmdlet.ParameterSetName -eq 'Url') {
    Invoke-WebRequest -Uri $JarUrl -OutFile $newJar -UseBasicParsing -TimeoutSec 1800
  } else {
    if (-not (Test-Path -LiteralPath $Jar)) { throw "jar 不存在: $Jar" }
    Copy-Item -LiteralPath $Jar -Destination $newJar -Force
  }

  if (-not (Test-Path -LiteralPath $TargetJar)) { throw "缺少当前 jar: $TargetJar" }
  $script:Current = Get-JarVersion $TargetJar
  if ($IsRequest) {
    Write-Phase 'precheck' "当前 $($script:Current)，目标 $($script:Target)"
  }

  if (-not $IsRequest) {
    $script:Target = Get-JarVersion $newJar
    if (-not $script:Target) { throw '无法读取新 jar 内的版本' }
  }
  if ($script:Current -and -not $script:Override -and
      -not (Test-VersionGt $script:Target $script:Current)) {
    throw '目标版本不高于当前版本'
  }
  if ($script:Override) {
    Write-Log "覆盖更新：忽略版本方向（当前 $($script:Current)，目标 $($script:Target)）"
  }
  Assert-DiskFree

  if ($IsRequest) {
    Write-Phase 'downloading' "获取 Release $Tag"
    $relUrl = "$Api/releases/tags/$([uri]::EscapeDataString($Tag))"
    $rel = Invoke-UpdateSource $relUrl
    $manifestAsset = $rel.assets |
      Where-Object { $_.name -eq 'release-manifest.json' } | Select-Object -First 1
    if (-not $manifestAsset) { throw 'Release 缺少 release-manifest.json' }
    $manifestPath = Join-Path $Tmp 'manifest.json'
    Invoke-UpdateSource $manifestAsset.browser_download_url $manifestPath
    $man = Read-JsonFile $manifestPath
    if ((Get-NormalizedVersion ([string]$man.version)) -ne (Get-NormalizedVersion $script:Target)) {
      throw 'manifest version 不匹配'
    }
    if ([string]$man.tag -ne $Tag) { throw 'manifest tag 不匹配' }
    $minFrom = [string]$man.minFromVersion
    if (-not $script:Override -and $minFrom -and (Test-VersionGt $minFrom $script:Current) -and
        ((Get-NormalizedVersion $minFrom) -ne (Get-NormalizedVersion $script:Current))) {
      throw "当前版本 $($script:Current) 低于 minFromVersion $minFrom"
    }
    $wantName = [string]$man.artifact.name
    if (-not $wantName) { $wantName = "gal-world-portal-$($script:Target).jar" }
    $jarAsset = $rel.assets | Where-Object { $_.name -eq $wantName } | Select-Object -First 1
    if (-not $jarAsset) { throw "Release 缺少 $wantName" }
    Write-Phase 'downloading' "下载 $($jarAsset.browser_download_url)"
    Invoke-UpdateSource $jarAsset.browser_download_url $newJar
  }

  if ($IsRequest) {
    Write-Phase 'verifying' '校验大小、sha256 与 jar 内容'
    $size = [string]$man.artifact.size
    if ($size -notmatch '^\d+$') { throw 'manifest artifact.size 不是数字' }
    $actualSize = (Get-Item -LiteralPath $newJar).Length
    if ($actualSize -ne [int64]$size) {
      throw "jar 大小不匹配（期望 $size，实际 $actualSize）"
    }
    $sha = ([string]$man.artifact.sha256).ToLowerInvariant()
    $actualSha = (Get-FileHash -Algorithm SHA256 -LiteralPath $newJar).Hash.ToLowerInvariant()
    if ($sha -and $actualSha -ne $sha) { throw 'sha256 不匹配' }
  } elseif ($Sha256) {
    $actualSha = (Get-FileHash -Algorithm SHA256 -LiteralPath $newJar).Hash.ToLowerInvariant()
    if ($actualSha -ne $Sha256.ToLowerInvariant()) { throw 'SHA256 不匹配' }
  }
  if ((Get-JarVersion $newJar) -ne $script:Target) { throw 'jar 内版本不匹配' }
  if (-not (Test-JarStatic $newJar)) { throw 'jar 缺少前端入口' }
  if ($script:Override -and -not (Test-JarUpdater $newJar)) {
    throw '目标版本不含更新器，拒绝覆盖'
  }

  Write-Phase 'backing_up' '准备备份 jar 与数据库'
  Write-Phase 'stopping' '停止服务'
  if (-not (Stop-Portal)) { throw '停服失败' }
  $script:PortalStopped = $true
  $script:PortalStarted = $false

  $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
  $script:BackupDir = Join-Path $BackupRoot "pre-update-$($script:Current)-$stamp"
  New-Item -ItemType Directory -Force -Path $script:BackupDir | Out-Null
  Copy-Item -LiteralPath $TargetJar `
    -Destination (Join-Path $script:BackupDir 'gal-world-portal.jar') -Force
  if (Test-Path -LiteralPath $DbPath) {
    Copy-Item -LiteralPath $DbPath `
      -Destination (Join-Path $script:BackupDir 'gal-world.mv.db') -Force
  }

  Write-Phase 'replacing' '替换 jar'
  Copy-Item -LiteralPath $newJar -Destination "$TargetJar.tmp" -Force
  Move-Item -LiteralPath "$TargetJar.tmp" -Destination $TargetJar -Force

  Write-Phase 'starting' "启动 $($script:Target)"
  try {
    Start-Portal
    $script:PortalStarted = $true
  } catch {
    Write-Log "启动失败: $($_.Exception.Message)"
    Invoke-Rollback
  }

  Write-Phase 'verifying' '启动后校验'
  if (-not (Test-Smoke $script:Target)) {
    Write-Log '冒烟失败，开始回滚'
    Invoke-Rollback
  }

  Write-Phase 'success' "更新完成 $($script:Current) -> $($script:Target)"
  if ($IsRequest) {
    Remove-Item -LiteralPath $Req -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $Tmp -Recurse -Force -ErrorAction SilentlyContinue
    Get-ChildItem -LiteralPath $BackupRoot -Directory -Filter 'pre-update-*' `
        -ErrorAction SilentlyContinue |
      Sort-Object LastWriteTime -Descending | Select-Object -Skip $BackupKeep |
      ForEach-Object { Remove-Item -LiteralPath $_.FullName -Recurse -Force -ErrorAction SilentlyContinue }
  }
  Write-Log "更新成功 $($script:Current) -> $($script:Target)"
} catch {
  $message = $_.Exception.Message
  Write-Log "ERROR: $message"
  if (-not $script:Terminal) {
    try { Write-Phase 'failed' $message } catch { }
  }
  if ($script:PortalStopped -and -not $script:PortalStarted) {
    try {
      Start-Portal
      $script:PortalStarted = $true
      Write-Log '异常退出后已恢复门户'
    } catch {
      Write-Log "门户恢复启动失败，需人工处理: $($_.Exception.Message)"
    }
  }
  exit 1
} finally {
  if ($mutexHeld -and $mutex) {
    try { $mutex.ReleaseMutex() } catch { }
    $mutex.Dispose()
  }
}

