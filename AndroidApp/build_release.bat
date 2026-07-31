@echo off
setlocal enabledelayedexpansion
REM Buduje podpisany APK produkcyjny (release) i dodaje podpis v1 (JAR),
REM bo starszy instalator MIUI (np. Mi 10) odrzuca APK bez v1. AGP przy
REM minSdk 26 pomija v1, dlatego dopisujemy go apksignerem (min-sdk 23).
cd /d "%~dp0"
if not defined JAVA_HOME set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"

call gradlew.bat :app:assembleRelease --console=plain || goto :eof

set "APK=app\build\outputs\apk\release\app-release.apk"

REM --- znajdź SDK i najnowsze build-tools (apksigner) ---
if defined ANDROID_HOME (set "SDK=%ANDROID_HOME%") else (set "SDK=%LOCALAPPDATA%\Android\Sdk")
for /f "delims=" %%d in ('dir /b /ad /o-n "%SDK%\build-tools" 2^>nul') do (
    set "APKSIGNER=%SDK%\build-tools\%%d\apksigner.bat"
    goto :gotbt
)
:gotbt

REM --- wczytaj dane podpisu z keystore.properties ---
for /f "usebackq tokens=1,* delims==" %%A in ("%~dp0keystore.properties") do (
    if "%%A"=="storeFile" set "KS=%%B"
    if "%%A"=="storePassword" set "KSPASS=%%B"
    if "%%A"=="keyAlias" set "KALIAS=%%B"
    if "%%A"=="keyPassword" set "KPASS=%%B"
)

echo.
echo === Dopisywanie podpisu v1 (kompatybilnosc z MIUI) ===
call "%APKSIGNER%" sign --ks "%~dp0%KS%" --ks-key-alias "%KALIAS%" ^
    --ks-pass pass:%KSPASS% --key-pass pass:%KPASS% ^
    --min-sdk-version 23 --v1-signing-enabled true --v2-signing-enabled true ^
    --v3-signing-enabled true --v4-signing-enabled false "%APK%"

call "%APKSIGNER%" verify --min-sdk-version 23 --verbose "%APK%" 2>nul | findstr /C:"scheme"
echo.
echo APK: %APK%
endlocal
