@echo off
rem One-click startup entry point for Windows.
rem Double-click to run, or pass flags from a shell. The logic lives in scripts\start.ps1.
setlocal

rem Pause only in interactive mode: --quiet / --no-prompt keep the window open-free.
if not "%~1"=="" for %%A in (%*) do (
    if /I "%%~A"=="--quiet" set "INTERACTIVE=0"
    if /I "%%~A"=="--no-prompt" set "INTERACTIVE=0"
)
if not defined INTERACTIVE set "INTERACTIVE=1"

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start.ps1" %*
set "RC=%ERRORLEVEL%"

if "%INTERACTIVE%"=="1" (
    echo.
    echo 提示：如果是 AI Agent 或无人值守部署，请使用 start.bat --quiet
    pause
)
exit /b %RC%
