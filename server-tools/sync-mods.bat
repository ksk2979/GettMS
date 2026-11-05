@echo off
cd /d "%~dp0"
echo Updating sync script...
curl.exe -sL -o sync-server-mods.ps1.new https://raw.githubusercontent.com/ksk2979/GettMS/main/server-tools/sync-server-mods.ps1 && move /y sync-server-mods.ps1.new sync-server-mods.ps1 >nul
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0sync-server-mods.ps1"
