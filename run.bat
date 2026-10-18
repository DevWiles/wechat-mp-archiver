@echo off
rem Double-click launcher. Keep this file ASCII-only: cmd mis-parses UTF-8 batch files.
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run.ps1" %*
