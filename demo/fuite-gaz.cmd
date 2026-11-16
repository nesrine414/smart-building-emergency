@echo off
REM Demo : fuite-gaz (broker en ligne)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0fuite-gaz.ps1" %*
pause
