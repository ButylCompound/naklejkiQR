@echo off
echo ========================================================
echo Budowanie wersji instalacyjnej (EXE) aplikacji
echo ========================================================

cd /d "%~dp0"

:: Sprawdzenie czy wirtualne środowisko istnieje
if not exist .venv\Scripts\activate.bat (
    echo [Błąd] Nie znaleziono wirtualnego środowiska .venv. 
    echo Uruchom najpierw aplikacje przez run.bat, aby pobrac zaleznosci.
    pause
    exit /b
)

:: Aktywacja środowiska
call .venv\Scripts\activate.bat

:: Instalacja PyInstaller jeśli nie jest zainstalowany
echo Instalowanie PyInstaller...
pip install pyinstaller

:: Czyszczenie poprzednich buildów
if exist build rmdir /S /Q build
if exist dist\Inwentaryzacja_QR.exe del /F /Q dist\Inwentaryzacja_QR.exe

echo.
echo Generowanie pliku EXE (moze to potrwac kilka minut)...
:: --noconsole: ukrywa czarne okno terminala
:: --onefile: pakuje wszystko do jednego pliku .exe
:: --name: nazwa pliku wynikowego
:: --hidden-import: wymusza dołączenie bibliotek, których PyInstaller mógłby nie zauważyć
pyinstaller --noconsole --onefile --name "Inwentaryzacja_QR" --hidden-import pandas --hidden-import openpyxl --clean main.py

echo.
echo ========================================================
echo ZAKONCZONO POMYSLNIE!
echo Gotowy, samodzielny program zostal wygenerowany.
echo Sciezka: %~dp0dist\Inwentaryzacja_QR.exe
echo ========================================================
pause
