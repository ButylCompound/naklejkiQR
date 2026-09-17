@echo off
echo Przygotowywanie kompilacji PyInstaller...

cd /d "%~dp0"

if not exist .venv\Scripts\activate.bat (
    echo [BLAD] Brak srodowiska wirtualnego. Uruchom najpierw run.bat aby je utworzyc i zainstalowac pakiety.
    pause
    exit /b 1
)

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

echo.
echo Kompilowanie wersji GUI (GeneratorNaklejek.exe)...
.venv\Scripts\pyinstaller.exe --noconfirm --onefile --windowed --name "GeneratorNaklejek" gui.py

echo.
echo Kompilowanie wersji dla opakowan (GeneratorNaklejek_Paczki.exe)...
.venv\Scripts\pyinstaller.exe --noconfirm --onefile --windowed --name "GeneratorNaklejek_Paczki" gui_packs.py

echo.
echo Kompilowanie wersji CLI (GeneratorNaklejek_CLI.exe)...
.venv\Scripts\pyinstaller.exe --noconfirm --onefile --console --name "GeneratorNaklejek_CLI" main.py

echo.
echo Kopiowanie wymaganych plikow pobocznych do folderu 'dist'...
copy /Y sticker_template.tex dist\
copy /Y logo.jpg dist\
copy /Y SumatraPDF-3.6.1-64.exe dist\

echo.
echo Sprzatanie tymczasowych plikow kompilacji...
if exist build rmdir /S /Q build
if exist *.spec del /Q *.spec

echo.
echo Gotowe pliki oraz wszystkie potrzebne zasoby znajduja sie w folderze 'dist'.
pause
