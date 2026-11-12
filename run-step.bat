@echo off
setlocal
set PYTHONUTF8=1
cd /d "%~dp0"
where py >nul 2>&1
if not errorlevel 1 (
    py -3 -m printsim --demo
    goto finish
)
where python >nul 2>&1
if not errorlevel 1 (
    python -m printsim --demo
    goto finish
)
set "BUNDLED_PYTHON=%USERPROFILE%\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe"
if exist "%BUNDLED_PYTHON%" (
    "%BUNDLED_PYTHON%" -m printsim --demo
) else (
    echo Python 3.10+ is required. Install Python or run the command from README.
)
:finish
pause
