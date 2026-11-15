<#
.SYNOPSIS
  Gal World - Windows 侧一键部署脚本

.DESCRIPTION
  完成门户在本机的落地：目录、证书、防火墙、开机自启，并检查 Sunshine 是否就绪。
  Sunshine 本身不自动下载安装（安装包需要人工确认来源），只做检测与提示；
  传 -SkipSunshine 可以把这段提示也跳过。

  需要以管理员身份运行（创建证书、防火墙规则、计划任务）。

.EXAMPLE
  .\install.ps1 -Jar ..\..\portal\target\gal-world-portal-<version>.jar

.EXAMPLE
  .\install.ps1 -Root D:\galworld -Port 8088 -SkipSunshine
#>

# SPDX-License-Identifier: MIT
# Copyright (c) 2026 sander

[CmdletBinding()]
param(
  [string]$Root = (Join-Path $env:USERPROFILE 'galworld'),
  [string]$Jar,
  [int]$Port = 8088,
  # Moonlight Web 的端口；写进 game.env 的 PORT 字段
  [int]$StreamPort = 47990,
  [int]$CertYears = 5,
  [string]$JavaExe,
  [switch]$SkipSunshine,
  [switch]$SkipFirewall
)

$ErrorActionPreference = 'Stop'

function Test-Admin {
  $id = [Security.Principal.WindowsIdentity]::GetCurrent()
  (New-Object Security.Principal.WindowsPrincipal($id)).IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Write-Step([string]$text) { Write-Host "`n== $text" -ForegroundColor Cyan }

if (-not (Test-Admin)) {
  throw '请以管理员身份运行：右键 PowerShell → 以管理员身份运行，再执行本脚本'
}

Write-Step "创建目录"
$dirs = @(
  $Root,
  (Join-Path $Root 'games'),
  (Join-Path $Root 'data'),
  (Join-Path $Root 'data\logs'),
  (Join-Path $Root 'data\sessions'),
  (Join-Path $Root 'data\certs'),
  (Join-Path $Root 'lib')
)
foreach ($d in $dirs) {
  if (-not (Test-Path -LiteralPath $d)) { New-Item -ItemType Directory -Path $d -Force | Out-Null }
}
Write-Host "根目录: $Root"

Write-Step "安装门户 jar"
$targetJar = Join-Path $Root 'lib\gal-world-portal.jar'
if ($Jar) {
  if (-not (Test-Path -LiteralPath $Jar)) { throw "找不到 jar：$Jar" }
  Copy-Item -LiteralPath $Jar -Destination $targetJar -Force
  Write-Host "已复制: $targetJar"
} elseif (Test-Path -LiteralPath $targetJar) {
  Write-Host "沿用已有 jar: $targetJar"
} else {
  Write-Warning "未提供 -Jar，且 $targetJar 不存在；自启会失败。先在 portal 目录执行 mvn -DskipTests package，再用 -Jar 指定产物重跑。"
}

Write-Step "生成自签名证书（Portal HTTPS）"
$p12 = Join-Path $Root 'data\certs\galworld.p12'
if (Test-Path -LiteralPath $p12) {
  Write-Host "证书已存在，跳过: $p12"
} else {
  # 用 .NET 直接生成自签名证书并导出 PKCS12。
  # 不用 New-SelfSignedCertificate：部分机器的 PowerShell 没有 Cert: 驱动器。
  $rsa = [System.Security.Cryptography.RSA]::Create(2048)
  $req = [System.Security.Cryptography.X509Certificates.CertificateRequest]::new(
    "CN=$env:COMPUTERNAME", $rsa,
    [System.Security.Cryptography.HashAlgorithmName]::SHA256,
    [System.Security.Cryptography.RSASignaturePadding]::Pkcs1)
  $san = [System.Security.Cryptography.X509Certificates.SubjectAlternativeNameBuilder]::new()
  $san.AddDnsName('localhost')
  $san.AddDnsName($env:COMPUTERNAME)
  $req.CertificateExtensions.Add($san.Build())
  $cert = $req.CreateSelfSigned(
    [DateTimeOffset]::Now.AddDays(-1),
    [DateTimeOffset]::Now.AddYears($CertYears))
  $bytes = $cert.Export([System.Security.Cryptography.X509Certificates.X509ContentType]::Pfx, 'changeit')
  [System.IO.File]::WriteAllBytes($p12, $bytes)
  $rsa.Dispose()
  Write-Host "已生成: $p12（口令 changeit，可用 GALWORLD_KEYSTORE_PASSWORD 覆盖）"
}

Write-Step "配置防火墙"
if ($SkipFirewall) {
  Write-Host '已跳过（-SkipFirewall）'
} else {
  $portalRule = "Gal World Portal $Port"
  if (-not (Get-NetFirewallRule -DisplayName $portalRule -ErrorAction SilentlyContinue)) {
    New-NetFirewallRule -DisplayName $portalRule -Direction Inbound -Action Allow `
      -Protocol TCP -LocalPort $Port | Out-Null
    Write-Host "已放行 TCP $Port"
  } else {
    Write-Host "规则已存在: $portalRule"
  }

  if (-not $SkipSunshine -and -not (Get-NetFirewallRule -DisplayName 'Sunshine' -ErrorAction SilentlyContinue)) {
    New-NetFirewallRule -DisplayName 'Sunshine' -Direction Inbound -Action Allow `
      -Protocol TCP -LocalPort 47984,47989,48010 | Out-Null
    New-NetFirewallRule -DisplayName 'Sunshine UDP' -Direction Inbound -Action Allow `
      -Protocol UDP -LocalPort 47998-48000 | Out-Null
    Write-Host '已放行 Sunshine TCP 47984/47989/48010 与 UDP 47998-48000'
  }
}

Write-Step "注册开机自启（计划任务）"

# Spring Boot 3 需要 JDK 17+；PATH 上的 java 可能是 Java 8 的 javapath 存根，
# 所以要按候选逐个验版本，不能直接取 Get-Command java。
function Test-Java17([string]$exe) {
  if (-not $exe -or -not (Test-Path -LiteralPath $exe)) { return $false }
  # java -version 写的是 stderr；脚本开头把 ErrorActionPreference 设成 Stop，
  # 原生命令的 stderr 会变成终止错误，这里必须临时降回 Continue 才能读到输出。
  $prev = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try {
    $out = & $exe -version 2>&1 | Out-String
  } catch {
    $out = ''
  } finally {
    $ErrorActionPreference = $prev
  }
  if ($out -match '"(\d+)\.(\d+)') {
    $major = [int]$Matches[1]
    if ($major -eq 1 -and $Matches[2]) { $major = [int]$Matches[2] }
    return $major -ge 17
  }
  return $false
}

$javaCandidates = @()
if ($JavaExe) { $javaCandidates += $JavaExe }
if ($env:JAVA_HOME) { $javaCandidates += (Join-Path $env:JAVA_HOME 'bin\java.exe') }
$javaCandidates += @(
  'C:\Program Files\Java\corretto-17*\bin\java.exe',
  'C:\Program Files\Java\jdk-17*\bin\java.exe',
  'C:\Program Files\Java\jdk-21*\bin\java.exe',
  'C:\Program Files\Eclipse Adoptium\jdk-*\bin\java.exe',
  'C:\Program Files\Microsoft\jdk-*\bin\java.exe'
)
# JDK 可能装在非系统盘（本机就在 G:），把每个固定盘的常见目录也扫一遍
foreach ($drive in (Get-PSDrive -PSProvider FileSystem -ErrorAction SilentlyContinue)) {
  foreach ($sub in 'Program Files\Java\corretto-17*', 'Program Files\Java\jdk-*',
                   'Program Files\Eclipse Adoptium\jdk-*', 'Program Files\Microsoft\jdk-*') {
    $javaCandidates += (Join-Path $drive.Root "$sub\bin\java.exe")
  }
}
$javaExe = $null
foreach ($c in $javaCandidates) {
  $found = Get-Item $c -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($found -and (Test-Java17 $found.FullName)) { $javaExe = $found.FullName; break }
}
if (-not $javaExe) {
  $onPath = (Get-Command java -ErrorAction SilentlyContinue).Source
  if (Test-Java17 $onPath) { $javaExe = $onPath }
}

if (-not $javaExe) {
  Write-Warning '找不到 JDK 17+，跳过自启注册。安装 JDK 17 后用 -JavaExe 指定路径重跑本脚本。'
} else {
  $action = New-ScheduledTaskAction -Execute $javaExe `
    -Argument "-jar `"$targetJar`"" `
    -WorkingDirectory $Root
  $trigger = New-ScheduledTaskTrigger -AtLogOn
  $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)
  Register-ScheduledTask -TaskName 'GalWorldPortal' -Action $action -Trigger $trigger `
    -Settings $settings -Description 'Gal World 门户' -Force | Out-Null
  Write-Host "已注册计划任务 GalWorldPortal（登录后启动 $javaExe -jar $targetJar）"
}

Write-Step "检查 Sunshine"
if ($SkipSunshine) {
  Write-Host '已跳过（-SkipSunshine）'
} else {
  $sunshine = 'C:\Program Files\Sunshine\sunshine.exe'
  if (Test-Path -LiteralPath $sunshine) {
    $svc = Get-Service -Name SunshineService -ErrorAction SilentlyContinue
    if ($svc) { Write-Host "Sunshine 服务状态: $($svc.Status)" }
    else { Write-Warning 'Sunshine 已安装但未注册服务，请从安装目录重新安装或手动注册' }
    Write-Host '首次使用请打开 https://localhost:47990 设置用户名密码，并配对 Moonlight Web'
  } else {
    Write-Host '未安装 Sunshine。安装包请从 LizardByte/Sunshine Releases 获取，安装后重跑本脚本。'
  }
}

Write-Step "健康检查"
try {
  $resp = Invoke-WebRequest -Uri "http://localhost:$Port/api/health" -TimeoutSec 10 -UseBasicParsing
  Write-Host "门户已在运行: $($resp.StatusCode)"
} catch {
  Write-Host "门户尚未启动（正常，首次部署需手动启动或重新登录）: $($_.Exception.Message)"
}

Write-Host "`n完成。根目录 $Root，端口 $Port。" -ForegroundColor Green

