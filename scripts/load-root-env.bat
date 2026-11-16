@echo off
rem Load non-empty keys from repo root .env into current cmd session (must use: call scripts\load-root-env.bat)
set "PANEL_REPO_ROOT=%~dp0.."
if not exist "%PANEL_REPO_ROOT%\.env" (
    echo [ERROR] Missing .env at %PANEL_REPO_ROOT%\.env - copy from .env.example
    exit /b 1
)

rem Skip blank values so placeholders do not wipe existing env vars
for /f "usebackq eol=# tokens=1,* delims==" %%A in ("%PANEL_REPO_ROOT%\.env") do (
    if not "%%~B"=="" set "%%A=%%~B"
)
exit /b 0
