@echo off
echo Przygotowywanie kompilacji PyInstaller...

cd /d "%~dp0"

:: Sprawdzanie czy istnieje srodowisko wirtualne
if not exist .venv\Scripts\activate.bat (
    echo [BLAD] Brak srodowiska wirtualnego. Uruchom najpierw run.bat aby je utworzyc i zainstalowac pakiety.
    pause
    exit /b 1
)

:: Aktywacja srodowiska
call .venv\Scripts\activate.bat

:: Instalacja PyInstaller
where uv >nul 2>nul
if %ERRORLEVEL% equ 0 (
    set HAS_UV=1
) else (
    set HAS_UV=0
)

if "%HAS_UV%"=="1" (
    echo Instalowanie pakietu PyInstaller przy uzyciu 'uv'...
    uv pip install pyinstaller
) else (
    echo Instalowanie pakietu PyInstaller przy uzyciu 'pip'...
    python -m pip install pyinstaller
)

:: Czyszczenie poprzednich kompilacji
if exist dist rmdir /S /Q dist
if exist build rmdir /S /Q build
if exist *.spec del /Q *.spec

:: Kompilacja aplikacji GUI
echo.
echo Kompilowanie wersji GUI (GeneratorNaklejek.exe)...
.venv\Scripts\pyinstaller.exe --noconfirm --onefile --windowed --name "GeneratorNaklejek" gui.py

:: Kompilacja aplikacji CLI
echo.
echo Kompilowanie wersji CLI (GeneratorNaklejek_CLI.exe)...
.venv\Scripts\pyinstaller.exe --noconfirm --onefile --console --name "GeneratorNaklejek_CLI" main.py

echo.
echo ========================================================
echo KOMPILACJA ZAKONCZONA SUKCESEM!
echo Gotowe pliki .exe znajduja sie w folderze 'dist'.
echo.
echo [INSTRUKCJA DLA PRODUKCJI]
echo Utworz na komputerze produkcyjnym nowy folder i wrzuc do niego:
echo 1. dist\GeneratorNaklejek.exe (Wygenerowany przed chwila)
echo 2. sticker_template.tex
echo 3. logo.jpg
echo 4. SumatraPDF-3.6.1-64.exe
echo.
echo PAMIETAJ: Na komputerze produkcyjnym wciaz musi byc zainstalowany MiKTeX!
echo ========================================================
pause
