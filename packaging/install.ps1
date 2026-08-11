<#
.SYNOPSIS
    Pieria installer for Windows (x86_64).

.DESCRIPTION
    Downloads the native daemon + gateway binaries for the host platform, installs
    them under the install root, adds the bin directory to the user PATH, and
    registers the daemon to run at logon via a per-user Scheduled Task.

    A logon Scheduled Task is used rather than a Windows SCM service because a bare
    console executable is not service-aware, and a real SCM service would need a
    third-party wrapper (WinSW/NSSM). The task is what 'pieria daemon start/stop/restart'
    drives, so the two stay in step.

    Re-running is safe: download, PATH update, and task registration are idempotent.

.EXAMPLE
    irm https://raw.githubusercontent.com/VAlux/pieria/main/packaging/install.ps1 | iex

.EXAMPLE
    .\install.ps1 -Version v0.1.0 -DryRun
#>
param(
	[string]$Version = $(if ($env:PIERIA_VERSION) { $env:PIERIA_VERSION } else { "latest" }),
	[string]$Repo = $(if ($env:PIERIA_REPO) { $env:PIERIA_REPO } else { "VAlux/pieria" }),
	[string]$InstallDir = $(if ($env:PIERIA_HOME) { $env:PIERIA_HOME } else { "$env:LOCALAPPDATA\Pieria" }),
	[string]$BaseUrl = $env:PIERIA_BASE_URL,   # override download host (mirror / local testing)
	[string]$TaskName = "PieriaDaemon",
	[switch]$NoService,
	# "0"/"false" read as $true in a plain boolean cast, so match install.sh and
	# treat only affirmative values as opting in.
	[switch]$AllowUnsupported = $(if ($env:PIERIA_ALLOW_UNSUPPORTED -and
		$env:PIERIA_ALLOW_UNSUPPORTED -notin @("0", "n", "no", "false")) { $true } else { $false }),
	[switch]$DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Write-Step([string]$Message) { Write-Host "==> $Message" }
function Write-Warn([string]$Message) { Write-Warning $Message }

# Windows platforms the release workflow actually builds
# (.github/workflows/release.yml). Anything else has no asset to download, so the
# install is stopped up front rather than left to surface as a 404.
$SupportedPlatforms = @("windows-x86_64")

# --- platform detection ------------------------------------------------------
# Maps the process architecture to the os-arch slug used by release assets and
# packaging\native\. Windows ships x86_64; warn (but try) on anything else.
function Get-Platform {
	$arch = $env:PROCESSOR_ARCHITECTURE
	switch ($arch) {
		"AMD64" { return "windows-x86_64" }
		"ARM64" { return "windows-aarch64" }
		default {
			Write-Warn "Unrecognized architecture '$arch'; attempting 'windows-x86_64'."
			return "windows-x86_64"
		}
	}
}

# Preflight: stop before any download when the detected platform has no release
# build. -AllowUnsupported keeps the best-effort behaviour for anyone serving a
# self-built archive through -BaseUrl.
function Assert-SupportedPlatform([string]$Platform) {
	if ($SupportedPlatforms -contains $Platform) { return }
	if ($AllowUnsupported) {
		Write-Warn "no release build is published for '$Platform'; continuing because -AllowUnsupported was given."
		return
	}
	throw @"
no release build for '$Platform'.
Published Windows platform: $($SupportedPlatforms -join ', ').
Build from source with '.\gradlew.bat :daemon:nativeDist', or re-run with
-AllowUnsupported (use -BaseUrl to serve your own archive).
"@
}

# Resolve the release base URL. A pinned tag uses .../download/<tag>/; "latest"
# uses GitHub's .../latest/download/ redirect. -BaseUrl overrides both.
function Get-ReleaseBase {
	if ($BaseUrl) { return $BaseUrl.TrimEnd("/") }
	if ($Version -eq "latest") { return "https://github.com/$Repo/releases/latest/download" }
	return "https://github.com/$Repo/releases/download/$Version"
}

function Invoke-Download([string]$Url, [string]$Dest) {
	if ($DryRun) { Write-Host "download $Url -> $Dest"; return }
	Invoke-WebRequest -Uri $Url -OutFile $Dest -UseBasicParsing
}

$platform     = Get-Platform
Assert-SupportedPlatform $platform
$releaseBase  = Get-ReleaseBase
$binDir       = Join-Path $InstallDir "bin"
$cliExe       = Join-Path $binDir "pieria.exe"
$daemonExe    = Join-Path $binDir "pieria-daemon.exe"
$gatewayExe   = Join-Path $binDir "pieria-gateway.exe"
$zipName      = "pieria-$platform.zip"
$zipUrl       = "$releaseBase/$zipName"
$checksumsUrl = "$releaseBase/checksums.txt"

Write-Step "platform:   $platform"
Write-Step "version:    $Version"
Write-Step "install to: $InstallDir"

# --- download + verify + extract --------------------------------------------
$work = Join-Path ([System.IO.Path]::GetTempPath()) ("pieria-install-" + [System.IO.Path]::GetRandomFileName())
if (-not $DryRun) { New-Item -ItemType Directory -Force -Path $work | Out-Null }
try {
	$zipPath = Join-Path $work $zipName
	# A failure here is nearly always a missing asset rather than a broken network,
	# so name the asset and the release instead of surfacing a bare web exception.
	try { Invoke-Download $zipUrl $zipPath }
	catch {
		throw @"
could not download $zipName from $zipUrl.
Check that release '$Version' publishes that asset: https://github.com/$Repo/releases
($($_.Exception.Message))
"@
	}

	# Optional integrity check: verify only if a checksums file is published.
	$checksumsPath = Join-Path $work "checksums.txt"
	$haveChecksums = $false
	try { Invoke-Download $checksumsUrl $checksumsPath; $haveChecksums = $true } catch { }

	if ($DryRun) {
		Write-Host "verify $zipName against checksums.txt (if published)"
	}
	elseif ($haveChecksums -and (Test-Path $checksumsPath)) {
		$line = Select-String -Path $checksumsPath -Pattern ([regex]::Escape($zipName)) | Select-Object -First 1
		if ($line) {
			$expected = ($line.Line -split '\s+')[0].ToLower()
			$actual = (Get-FileHash -Path $zipPath -Algorithm SHA256).Hash.ToLower()
			if ($actual -ne $expected) { throw "checksum mismatch for $zipName (expected $expected, got $actual)." }
			Write-Step "checksum verified"
		} else {
			Write-Warn "no checksum entry for $zipName; skipping verification."
		}
	} else {
		Write-Warn "no checksums.txt published; skipping integrity verification."
	}

	if ($DryRun) {
		Write-Host "expand $zipName -> $InstallDir (expects bin\{pieria,pieria-daemon,pieria-gateway}.exe)"
	} else {
		New-Item -ItemType Directory -Force -Path $binDir | Out-Null
		# Archive root contains bin\*.exe; expand into the install root.
		Expand-Archive -Path $zipPath -DestinationPath $InstallDir -Force
		if (-not (Test-Path $daemonExe)) { throw "expected $daemonExe after extraction; check archive layout." }
	}
}
finally {
	if (-not $DryRun -and (Test-Path $work)) { Remove-Item -Recurse -Force $work }
}

# --- add bin to user PATH ----------------------------------------------------
if ($DryRun) {
	Write-Host "ensure '$binDir' on user PATH"
} else {
	$userPath = [Environment]::GetEnvironmentVariable("Path", "User")
	$entries = @()
	if ($userPath) { $entries = $userPath -split ';' | Where-Object { $_ -ne "" } }
	if ($entries -notcontains $binDir) {
		$newPath = (@($entries) + $binDir) -join ';'
		[Environment]::SetEnvironmentVariable("Path", $newPath, "User")
		$env:Path = "$env:Path;$binDir"
		Write-Step "added '$binDir' to user PATH (restart shells to pick it up)"
	}
}

# --- daemon launch arguments -------------------------------------------------
$dataDir    = Join-Path $InstallDir "data"
$configDir  = Join-Path $env:APPDATA "Pieria"
$logDir     = Join-Path $InstallDir "logs"
$runtimeDir = Join-Path $InstallDir "run"
$daemonArgs = @(
	"--pieria.daemon.host=127.0.0.1",
	"--pieria.daemon.port=8077",
	"--pieria.db.path=$(Join-Path $dataDir 'pieria.db')",
	"--pieria.app-data.root=$dataDir",
	"--pieria.app-data.config-dir=$configDir",
	"--pieria.app-data.logs-dir=$logDir",
	"--pieria.app-data.runtime-dir=$runtimeDir",
	"--logging.file.name=$(Join-Path $logDir 'pieria-daemon.log')"
)
$argString = ($daemonArgs | ForEach-Object { '"' + $_ + '"' }) -join ' '

# --- service registration (logon Scheduled Task) ----------------------------
if (-not $NoService) {
	if ($DryRun) {
		Write-Host "Register-ScheduledTask -TaskName $TaskName (AtLogOn) -Execute $daemonExe"
		Write-Host "  arguments: $argString"
		Write-Host "Start-ScheduledTask -TaskName $TaskName"
	} else {
		Write-Step "registering daemon as a logon Scheduled Task '$TaskName'"
		New-Item -ItemType Directory -Force -Path $dataDir, $configDir, $logDir, $runtimeDir | Out-Null
		$action  = New-ScheduledTaskAction -Execute $daemonExe -Argument $argString -WorkingDirectory $dataDir
		$trigger = New-ScheduledTaskTrigger -AtLogOn
		$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable
		Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings -Force | Out-Null
		try { Start-ScheduledTask -TaskName $TaskName }
		catch { Write-Warn "task registered but failed to start; start it from Task Scheduler or run: $daemonExe $argString" }
	}
}

# --- next steps --------------------------------------------------------------
$daemonUrl = "http://127.0.0.1:8077"
$serviceLine = if ($NoService) {
	"skipped (-NoService). Start the daemon yourself: pieria-daemon"
} else {
	"registered as logon task '$TaskName' (first-run init runs on daemon start; check logs for model-pull guidance)."
}

@"

=== Pieria installed ===
Binaries:   $binDir\{pieria,pieria-daemon,pieria-gateway}.exe
Daemon URL: $daemonUrl
Service:    $serviceLine

Wire a harness (registers the MCP gateway + lifecycle hooks). From your project:

  pieria harness install claude-code        # or: codex
  pieria harness install claude-code --user   # wire ~\.claude instead of this repo

Inspect or undo with 'pieria harness list' and 'pieria harness uninstall <name>'.
Run 'pieria harness install <name> --dry-run' first to preview the changes.

Manage the daemon with 'pieria daemon start|stop|restart|status' — these drive the
'$TaskName' task registered above. Update in place with 'pieria update'.
========================
"@ | Write-Host
