param(
	[ValidateSet("Install", "Start", "Stop", "Status", "Uninstall")]
	[string]$Action = "Status",
	[string]$ServiceName = "PieriaDaemon",
	[string]$Daemon = "$env:LOCALAPPDATA\Pieria\bin\pieria-daemon.exe",
	[string]$Java = "java",
	[string]$Gateway = "$env:LOCALAPPDATA\Pieria\bin\pieria-gateway.exe",
	[string]$HostAddress = "127.0.0.1",
	[int]$Port = 8077,
	[string]$DataDir = "$env:LOCALAPPDATA\Pieria\data",
	[string]$ConfigDir = "$env:APPDATA\Pieria",
	[string]$LogDir = "$env:LOCALAPPDATA\Pieria\logs",
	[string]$RuntimeDir = "$env:LOCALAPPDATA\Pieria\run",
	[switch]$AllowStagedInstall,
	[switch]$DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Quote-Arg([string]$Value) {
	return '"' + ($Value -replace '"', '\"') + '"'
}

function Get-DaemonCommand {
	$dbPath = Join-Path $DataDir "pieria.db"
	$logPath = Join-Path $LogDir "pieria-daemon.log"
	$args = @(
		"--pieria.daemon.host=$HostAddress",
		"--pieria.daemon.port=$Port",
		"--pieria.db.path=$dbPath",
		"--pieria.app-data.root=$DataDir",
		"--pieria.app-data.config-dir=$ConfigDir",
		"--pieria.app-data.logs-dir=$LogDir",
		"--pieria.app-data.runtime-dir=$RuntimeDir",
		"--logging.file.name=$logPath"
	)

	if ($Daemon.EndsWith(".jar", [System.StringComparison]::OrdinalIgnoreCase)) {
		return "$(Quote-Arg $Java) -jar $(Quote-Arg $Daemon) " + (($args | ForEach-Object { Quote-Arg $_ }) -join " ")
	}

	return "$(Quote-Arg $Daemon) " + (($args | ForEach-Object { Quote-Arg $_ }) -join " ")
}

$daemonCommand = Get-DaemonCommand

switch ($Action) {
	"Install" {
		if ($DryRun) {
			Write-Output "Would create directories: $DataDir, $ConfigDir, $LogDir, $RuntimeDir"
			Write-Output "Would install service $ServiceName with command:"
			Write-Output $daemonCommand
			Write-Output "Real install requires a Windows service-aware wrapper or executable; pass -AllowStagedInstall after providing one."
			Write-Output "gateway executable for harness MCP configs: $Gateway"
			break
		}
		if (-not $AllowStagedInstall) {
			throw "Windows services require a service-aware wrapper such as WinSW/NSSM or an SCM-compatible executable. Re-run with -DryRun to inspect the command, or pass -AllowStagedInstall after providing one."
		}
		New-Item -ItemType Directory -Force -Path $DataDir, $ConfigDir, $LogDir, $RuntimeDir | Out-Null
		New-Service -Name $ServiceName -DisplayName "Pieria Daemon" -BinaryPathName $daemonCommand -StartupType Automatic
		Write-Output "Installed $ServiceName. Start it with: .\pieria-service.ps1 -Action Start"
	}
	"Start" {
		if ($DryRun) { Write-Output "Start-Service -Name $ServiceName"; break }
		Start-Service -Name $ServiceName
	}
	"Stop" {
		if ($DryRun) { Write-Output "Stop-Service -Name $ServiceName"; break }
		Stop-Service -Name $ServiceName
	}
	"Status" {
		if ($DryRun) { Write-Output "Get-Service -Name $ServiceName"; break }
		Get-Service -Name $ServiceName
	}
	"Uninstall" {
		if ($DryRun) {
			Write-Output "Stop-Service -Name $ServiceName"
			Write-Output "sc.exe delete $ServiceName"
			break
		}
		Stop-Service -Name $ServiceName -ErrorAction SilentlyContinue
		sc.exe delete $ServiceName | Out-Null
	}
}
