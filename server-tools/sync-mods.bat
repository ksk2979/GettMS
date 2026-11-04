@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo 최신 동기화 스크립트 받는 중...
curl.exe -sL -o sync-server-mods.ps1.new https://raw.githubusercontent.com/ksk2979/GettMS/main/server-tools/sync-server-mods.ps1 && move /y sync-server-mods.ps1.new sync-server-mods.ps1 >nul
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0sync-server-mods.ps1"
