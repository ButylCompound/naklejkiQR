@echo off
chcp 65001 > nul
echo =========================================================================
echo GENERATING STRESS-TEST PDFs FOR TEMPLATE 5 (WIDE UPPERCASE CHARACTERS)
echo =========================================================================
echo.

echo [1/3] Short name...
.venv\Scripts\python.exe main.py --name "ALBU C2E" --weight "123.0" --date "2026-07-28 15:00:00" --operator "QC" --no-print --template sticker_template.tex
if exist output.pdf move output.pdf sample_5_short_stress.pdf

echo.
echo [2/3] Medium name...
.venv\Scripts\python.exe main.py --name "ALBU R2E UV" --weight "1234.5" --date "2026-07-28 15:00:00" --operator "QC" --no-print --template sticker_template.tex
if exist output.pdf move output.pdf sample_5_medium_stress.pdf

echo.
echo Name: 'MIESZANKA XYZ-123 CZARNA WYSOKOODPORNA'
.venv\Scripts\python.exe main.py --name "MIESZANKA XYZ-123 CZARNA WYSOKOODPORNA" --weight "1234.5" --date "2026-07-28 15:00:00" --operator "QC" --no-print --template sticker_template.tex
if exist output.pdf move output.pdf sample_5_long_stress.pdf

echo.
echo =========================================================================
pause
