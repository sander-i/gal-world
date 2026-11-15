<#
.SYNOPSIS
  Gal World - Windows 侧游戏注册 CLI（对应 Linux 的 deploy/bin/galworld）

.DESCRIPTION
  约定与 Linux 版一致：game.env 是运行时参数的唯一真相，Portal 只把它投影进数据库。
  Windows 下没有 Xvfb / Wine / ALSA，因此 DISPLAY_NUM、LOOPBACK_SLOT 不再使用，
  端口统一指向 Moonlight Web（所有游戏共用）。

.EXAMPLE
  .\galworld.ps1 add sample-game --name "示例游戏" --exe "D:\Games\sample-game\game.exe"
  .\galworld.ps1 list
  .\galworld.ps1 start sample-game
#>

# SPDX-License-Identifier: MIT
# Copyright (c) 2026 sander

[CmdletBinding()]
param(
  [Parameter(Position = 0, Mandatory = $true)]
  [ValidateSet('list', 'add', 'remove', 'start', 'stop', 'status')]
  [string]$Command,

  [Parameter(Position = 1)]
  [string]$Id,

  [string]$Name,
  [string]$Exe,
  [int]$Width = 1280,
  [int]$Height = 720,

  # Moonlight Web 的地址；所有 Windows 会话共用同一个串流入口
  [string]$PortalUrl = "http://localhost:8088",
  [int]$StreamPort = 47990
)

$ErrorActionPreference = 'Stop'

$Root = if ($env:GALWORLD_ROOT) { $env:GALWORLD_ROOT } else { Join-Path $env:USERPROFILE 'galworld' }
$Games = Join-Path $Root 'games'

function Read-GameEnv([string]$path) {
  $map = @{}
  foreach ($line in Get-Content -LiteralPath $path -Encoding UTF8) {
    $trimmed = $line.Trim()
    if ($trimmed -eq '' -or $trimmed.StartsWith('#')) { continue }
    $eq = $trimmed.IndexOf('=')
    if ($eq -le 0) { continue }
    $map[$trimmed.Substring(0, $eq).Trim()] = $trimmed.Substring($eq + 1).Trim()
  }
  return $map
}

function Get-GameState([string]$gameId) {
  $pidFile = Join-Path $Root "data\sessions\$gameId.pid"
  if (-not (Test-Path -LiteralPath $pidFile)) { return 'stopped' }
  $pidValue = (Select-String -LiteralPath $pidFile -Pattern '^pid=(\d+)' |
      Select-Object -First 1).Matches.Groups[1].Value
  if (-not $pidValue) { return 'stopped' }
  $proc = Get-Process -Id ([int]$pidValue) -ErrorAction SilentlyContinue
  if ($proc) { 'running' } else { 'failed' }
}

function Invoke-Portal([string]$method, [string]$path) {
  $uri = "$PortalUrl$path"
  try {
    Invoke-RestMethod -Method $method -Uri $uri -TimeoutSec 60 | Out-Null
  } catch {
    Write-Warning "调用门户失败（$uri）：$($_.Exception.Message)"
    throw
  }
}

switch ($Command) {
  'list' {
    if (-not (Test-Path -LiteralPath $Games)) { Write-Host '还没有注册任何游戏'; break }
    '{0,-14} {1,-24} {2,-8} {3}' -f 'ID', 'NAME', 'PORT', 'STATE'
    Get-ChildItem -LiteralPath $Games -Directory |
      Where-Object { Test-Path (Join-Path $_.FullName 'game.env') } |
      ForEach-Object {
        $envMap = Read-GameEnv (Join-Path $_.FullName 'game.env')
        '{0,-14} {1,-24} {2,-8} {3}' -f $_.Name, $envMap['GAME_NAME'], $envMap['PORT'], (Get-GameState $_.Name)
      }
  }

  'add' {
    if (-not $Id -or -not $Name -or -not $Exe) { throw '用法: galworld.ps1 add <id> --name <显示名> --exe <绝对路径>' }
    if (-not (Test-Path -LiteralPath $Exe)) { throw "找不到 exe：$Exe" }
    $dir = Join-Path $Games $Id
    if (Test-Path -LiteralPath $dir) { throw "游戏 $Id 已存在" }
    New-Item -ItemType Directory -Path $dir -Force | Out-Null

    $workDir = Split-Path -Parent (Resolve-Path -LiteralPath $Exe)
    $lines = @(
      "GAME_ID=$Id",
      "GAME_NAME=$Name",
      "EXE=$Exe",
      "WORKDIR=$workDir",
      "WIDTH=$Width",
      "HEIGHT=$Height",
      "PORT=$StreamPort"
    )
    Set-Content -LiteralPath (Join-Path $dir 'game.env') -Value $lines -Encoding UTF8
    Write-Host "已注册 $Id（串流端口 $StreamPort）"
    Write-Host "提示：门户需要通过 /api/games/scan 或重启来同步这条记录"
  }

  'remove' {
    if (-not $Id) { throw '用法: galworld.ps1 remove <id>' }
    $dir = Join-Path $Games $Id
    if (-not (Test-Path -LiteralPath $dir)) { throw "没有这个游戏：$Id" }
    try { Invoke-Portal 'POST' "/api/games/$Id/stop" } catch { }
    Remove-Item -LiteralPath $dir -Recurse -Force
    Remove-Item -LiteralPath (Join-Path $Root "data\sessions\$Id.pid") -ErrorAction SilentlyContinue
    Write-Host "已移除 $Id"
  }

  'start' {
    if (-not $Id) { throw '用法: galworld.ps1 start <id>' }
    Invoke-Portal 'POST' "/api/games/$Id/start"
    Write-Host "已请求启动 $Id"
  }

  'stop' {
    if (-not $Id) { throw '用法: galworld.ps1 stop <id>' }
    Invoke-Portal 'POST' "/api/games/$Id/stop"
    Write-Host "已请求停止 $Id"
  }

  'status' {
    if (-not $Id) { Get-ChildItem -LiteralPath $Games -Directory -ErrorAction SilentlyContinue | ForEach-Object { $_.Name }; break }
    $envMap = Read-GameEnv (Join-Path $Games "$Id\game.env")
    "game   : $Id"
    "name   : $($envMap['GAME_NAME'])"
    "exe    : $($envMap['EXE'])"
    "workdir: $($envMap['WORKDIR'])"
    "port   : $($envMap['PORT'])"
    "state  : $(Get-GameState $Id)"
  }
}

