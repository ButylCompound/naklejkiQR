#!/usr/bin/env zsh

# Domyślne wartości
delay=0
limit=-1
csv_file="do_wydruku.csv"
exe_path="dist/GeneratorNaklejek_CLI.exe"

# Parsowanie parametrów
while getopts d:n: flag
do
    case "${flag}" in
        d) delay=${OPTARG};;
        n) limit=${OPTARG};;
    esac
done

if [[ ! -f $csv_file ]]; then
    echo "Błąd: Nie znaleziono pliku $csv_file"
    exit 1
fi

echo "Odczytywanie i sortowanie pliku CSV (od najnowszego do najstarszego)..."

# Używamy małego skryptu Pythona wbudowanego w zsh, aby uniknąć problemów
# z przecinkami wewnątrz nazw produktów (standardowy `sort` często tu zawodzi).
sorted_data=$(python3 -c "
import csv, sys
try:
    with open('$csv_file', encoding='utf-8') as f:
        reader = csv.reader(f)
        header = next(reader)
        data = list(reader)
        # Sortowanie po kolumnie Data (indeks 1) malejąco
        data.sort(key=lambda x: x[1], reverse=True)
        for row in data:
            print('\t'.join(row))
except Exception as e:
    print('Błąd Pythona:', e, file=sys.stderr)
")

count=0

# Odczyt tab-separated output from Python
echo "$sorted_data" | while IFS=$'\t' read -r nazwa data waga operator; do
    if [[ -z "$nazwa" ]]; then continue; fi
    
    if [[ $limit -ne -1 && $count -ge $limit ]]; then
        echo "Osiągnięto limit $limit wydruków. Zatrzymywanie..."
        break
    fi
    
    count=$((count + 1))
    echo "=========================================================="
    echo "Drukowanie [$count]: $nazwa | Waga: $waga kg | Data: $data"
    
    # Uruchamiamy aplikację CLI
    if [[ -f $exe_path ]]; then
        ./$exe_path "$waga" --name "$nazwa" --date "$data" --operator "$operator" --print
    else
        # Zapasowo: używamy środowiska wirtualnego jeśli nie ma .exe
        echo "Nie znaleziono pliku .exe, uruchamiam przez Pythona..."
        .venv/Scripts/python.exe main.py "$waga" --name "$nazwa" --date "$data" --operator "$operator" --print
    fi
    
    # Przerywamy po osiągnięciu limitu
    if [[ $limit -ne -1 && $count -ge $limit ]]; then
        echo "Wysłano zadanie nr $count (Limit)."
        break
    fi
    
    # Czekamy aby nie zalać bufora drukarki
    if [[ $delay -gt 0 ]]; then
        echo "Czekam $delay sekund na zbuforowanie drukarki..."
        sleep $delay
    fi
done

echo "=========================================================="
echo "Zakończono. Wysłano do druku łącznie $count etykiet."
