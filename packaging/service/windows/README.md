# Pieria Windows Service

`pieria-service.ps1` is a staged Windows service helper for Phase 5. It supports
`Install`, `Start`, `Stop`, `Status`, and `Uninstall` flows, and it has `-DryRun` so the
generated daemon command can be validated before changing the machine.

Windows SCM services require a service-aware executable or wrapper such as WinSW/NSSM.
The script therefore refuses a real install unless `-AllowStagedInstall` is provided,
which is intended for environments that have already supplied such a wrapper command.

Only the daemon should be installed as a Windows service. Keep `pieria-gateway.exe` as a
normal executable referenced by MCP harness configuration.

Example dry run:

```powershell
.\pieria-service.ps1 -Action Install `
  -Daemon "$env:LOCALAPPDATA\Pieria\bin\pieria.jar" `
  -Java "java" `
  -Gateway "$env:LOCALAPPDATA\Pieria\bin\pieria-gateway.exe" `
  -DryRun
```
