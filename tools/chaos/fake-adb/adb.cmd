@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0fake-adb.ps1" %*
exit /b %ERRORLEVEL%
