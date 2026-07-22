#!/usr/bin/env zsh

delay=5
limit=-1
csv_file="do_wydruku.csv"
exe_path="dist/GeneratorNaklejek_CLI.exe"

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
echo "$sorted_data" | while IFS=$'\t' read -r nazwa data waga operator kopie; do
    if [[ -z "$nazwa" ]]; then continue; fi
    if [[ -z "$kopie" ]]; then kopie=1; fi
    
    if [[ $limit -ne -1 && $count -ge $limit ]]; then
        echo "Osiągnięto limit $limit wydruków. Zatrzymywanie..."
        break
    fi
    
    count=$((count + 1))
    echo "=========================================================="
    echo "Drukowanie [$count]: $nazwa | Waga: $waga kg | Data: $data | Kopie: $kopie"
    
    # Uruchamiamy aplikację CLI, przekierowując stdin, aby aplikacja nie "zjadła" reszty strumienia
    if [[ -f $exe_path ]]; then
        ./$exe_path --weight "$waga" --name "$nazwa" --date "$data" --operator "$operator" --copies "$kopie" --printer "ZDesigner ZD421-203dpi ZPL" --no-print < /dev/null
    else
        echo "Nie znaleziono pliku .exe, uruchamiam przez Pythona..."
        .venv/Scripts/python.exe main.py --weight "$waga" --name "$nazwa" --date "$data" --operator "$operator" --copies "$kopie" --printer "ZDesigner ZD421-203dpi ZPL" --no-print < /dev/null
    fi
    
    # Przerywamy po osiągnięciu limitu
    if [[ $limit -ne -1 && $count -ge $limit ]]; then
        echo "Wysłano zadanie nr $count (Limit)."
        break
    fi
    
    # Czekamy i nasłuchujemy klawisza 'p' (pauza)
    if [[ $delay -gt 0 ]]; then
        echo "Czekam $delay sekund na zbuforowanie... (Wciśnij 'p' aby wstrzymać)"
    else
        echo "(Wciśnij 'p' szybko aby wstrzymać)"
    fi
    
    end_time=$(( SECONDS + delay ))
    while [[ $SECONDS -lt $end_time || $delay -eq 0 ]]; do
        remaining=$(( end_time - SECONDS ))
        if [[ $remaining -le 0 ]]; then remaining=0.05; fi
        
        # Zczytywanie jednego znaku prosto z terminala (z pominięciem stdin pipe)
        if read -k 1 -t $remaining key < /dev/tty 2>/dev/null; then
            if [[ "$key" == "p" || "$key" == "P" ]]; then
                echo "\n[PAUZA] Drukowanie wstrzymane. Wciśnij 'p' aby wznowić."
                while true; do
                    if read -k 1 key < /dev/tty 2>/dev/null; then
                        if [[ "$key" == "p" || "$key" == "P" ]]; then
                            echo "\n[WZNOWIENIE] Kontynuacja drukowania..."
                            break 2
                        fi
                    fi
                done
            fi
        fi
        
        if [[ $delay -eq 0 ]]; then break; fi
    done
done

echo "=========================================================="
echo "Zakończono. Wysłano do druku łącznie $count etykiet."
