@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo Gett Core 개발용 클라이언트를 시작합니다 (첫 실행 1~2분)...
call gradlew.bat runClient
pause
