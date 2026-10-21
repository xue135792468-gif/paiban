@echo off
rem Doctor entry point for Windows. Logic lives in scripts\doctor.ps1.
rem Usage: doctor.bat [--json^|--quiet] [--help]
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\doctor.ps1" %*
exit /b %errorlevel%
