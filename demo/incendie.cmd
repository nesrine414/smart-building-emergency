@echo off
REM Demo : incendie (broker en ligne)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0incendie.ps1" %*
pause
