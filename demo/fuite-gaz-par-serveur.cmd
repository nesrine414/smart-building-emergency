@echo off
REM Plan B : fuite-gaz lance sur le serveur par SSH
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0fuite-gaz.ps1" -ViaServeur %*
pause
