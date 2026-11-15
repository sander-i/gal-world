<#
.SYNOPSIS
  Gal World - Windows 侧卸载（保留游戏与存档）

.DESCRIPTION
  停门户与游戏会话、删计划任务与防火墙规则。
  games/、data/portal（H2）、data/backups 默认保留；加 -Purge 才会一起删除。

.EXAMPLE
  .\uninstall.ps1
  .\uninstall.ps1 -Purge
#>

# SPDX-License-Identifier: MIT
# Copyright (c) 2026 sander

[CmdletBinding()]
param(
  [string]$Root = (Join-Path $env:USERPROFILE 'galworld'),
  [int]$Port = 8088,
  [switch]$Purge
)

$ErrorActionPreference = 'Stop'

function Test-Admin {
  $id = [Security.Principal.WindowsIdentity]::GetCurrent()
  (New-Object Security.Principal.WindowsPrincipal($id)).IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)
}

Write-Host "`n== 停止计划任务"
Unregister-ScheduledTask -TaskName 'GalWorldPortal' -Confirm:$false -ErrorAction SilentlyContinue

Write-Host "`n== 停止游戏会话"
$sessions = Join-Path $Root 'data\sessions'
if (Test-Path -LiteralPath $sessions) {
  Get-ChildItem -LiteralPath $sessions -Filter *.pid | ForEach-Object {
    $pidValue = (Select-String -LiteralPath $_.FullName -Pattern '^pid=(\d+)' |
        Select-Object -First 1).Matches.Groups[1].Value
    if ($pidValue) {
      taskkill /PID $pidValue /T /F 2>$null | Out-Null
      Write-Host "已结束进程 $pidValue（$($_.BaseName)）"
    }
  }
}

Write-Host "`n== 停止门户进程"
$listeners = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
foreach ($l in $listeners) {
  Stop-Process -Id $l.OwningProcess -Force -ErrorAction SilentlyContinue
  Write-Host "已停止 PID $($l.OwningProcess)"
}

Write-Host "`n== 删除防火墙规则"
if (Test-Admin) {
  Get-NetFirewallRule -DisplayName "Gal World Portal $Port" -ErrorAction SilentlyContinue |
    Remove-NetFirewallRule
  Get-NetFirewallRule -DisplayName 'Sunshine' -ErrorAction SilentlyContinue | Remove-NetFirewallRule
  Get-NetFirewallRule -DisplayName 'Sunshine UDP' -ErrorAction SilentlyContinue | Remove-NetFirewallRule
  Write-Host '已删除 Gal World 与 Sunshine 的防火墙规则（Sunshine 本体未卸载）'
} else {
  Write-Warning '非管理员：跳过防火墙规则清理，请用管理员身份重跑'
}

if ($Purge) {
  Write-Host "`n== 删除数据目录 $Root"
  Remove-Item -LiteralPath $Root -Recurse -Force
  Write-Host '已删除（游戏本体与存档一并移除）'
} else {
  Write-Host "`n已保留 $Root 下的游戏、存档与数据库。"
}

Write-Host '完成。' -ForegroundColor Green

