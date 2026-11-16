@echo off
REM Plan B : incendie lance sur le serveur par SSH
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0incendie.ps1" -ViaServeur %*
pause
