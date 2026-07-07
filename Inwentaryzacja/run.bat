@echo off
echo Starting Inventory Scanner...

cd /d "%~dp0"

:: Check if virtual environment exists, create if it doesn't
if not exist .venv\Scripts\activate.bat (
    echo Creating virtual environment...
    
    where uv >nul 2>nul
    if %ERRORLEVEL% equ 0 (
        echo Using 'uv' to create environment...
        uv venv .venv
        echo Installing dependencies using 'uv'...
        uv pip install -r requirements.txt
    ) else (
        echo 'uv' tool not found. Using 'python -m venv'...
        python -m venv .venv
        echo Installing dependencies...
        .venv\Scripts\python.exe -m pip install -r requirements.txt
    )
)

:: Activate virtual environment
if exist .venv\Scripts\activate.bat (
    call .venv\Scripts\activate.bat
) else (
    echo [WARNING] Failed to create virtual environment. Attempting to run with system python...
)

:: Run the GUI script
python gui.py
