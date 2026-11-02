@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0fake-emulator.ps1" %*
exit /b %ERRORLEVEL%
