@echo off
echo Uruchamianie Skanera Inwentaryzacji...

cd /d "%~dp0"

:: Sprawdzanie czy aplikacja zostala przeniesiona
set "CURRENT_DIR=%CD%"
if exist .venv_path.txt (
    set /p SAVED_DIR=<.venv_path.txt
) else (
    set "SAVED_DIR="
)

if exist .venv (
    if /I not "%CURRENT_DIR%"=="%SAVED_DIR%" (
        echo Wykryto przeniesienie folderu aplikacji. Rekonfiguracja srodowiska...
        rmdir /S /Q .venv
    )
)

:: Sprawdzanie i tworzenie wirtualnego srodowiska
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
    
    :: Zapis aktualnej sciezki
    echo %CURRENT_DIR%>.venv_path.txt
)

:: Aktywacja srodowiska
if exist .venv\Scripts\activate.bat (
    call .venv\Scripts\activate.bat
) else (
    echo [OSTRZEZENIE] Nie udalo sie utworzyc wirtualnego srodowiska. Proba uruchomienia za pomoca glownego pythona...
)

:: Uruchamianie aplikacji GUI
python gui.py
