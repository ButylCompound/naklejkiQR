@echo off
echo Uruchamianie Generatora Naklejek...

cd /d "%~dp0"

:: Check if virtual environment exists, create if it doesn't
if not exist .venv\Scripts\activate.bat (
    echo Tworzenie wirtualnego srodowiska...
    
    where uv >nul 2>nul
    if %ERRORLEVEL% equ 0 (
        echo Uzywanie 'uv' do utworzenia srodowiska...
        uv venv .venv
        echo Instalowanie zaleznosci przy pomocy 'uv'...
        uv pip install -r requirements.txt
    ) else (
        echo Narzedzie 'uv' nie zostalo znalezione. Uzywanie 'python -m venv'...
        python -m venv .venv
        echo Instalowanie zaleznosci...
        .venv\Scripts\python.exe -m pip install -r requirements.txt
    )
)

:: Activate virtual environment
if exist .venv\Scripts\activate.bat (
    call .venv\Scripts\activate.bat
) else (
    echo [OSTRZEZENIE] Nie udalo sie utworzyc wirtualnego srodowiska. Proba uruchomienia za pomoca glownego pythona...
)

:: Run the GUI script
python gui.py
