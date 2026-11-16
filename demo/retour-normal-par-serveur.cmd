@echo off
REM Plan B : retour-normal lance sur le serveur par SSH
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0retour-normal.ps1" -ViaServeur %*
pause
