@echo off
REM Demo : retour-normal (broker en ligne)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0retour-normal.ps1" %*
pause
