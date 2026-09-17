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
    sys.exit(1)
")

if [[ $? -ne 0 ]]; then
    echo "Błąd: Nie udało się odczytać lub posortować pliku CSV."
    exit 1
fi

count=0
had_error=0

# Odczyt tab-separated output from Python
while IFS=$'\t' read -r nazwa data waga operator kopie; do
    if [[ -z "$nazwa" ]]; then continue; fi
    if [[ -z "$kopie" ]]; then kopie=1; fi
    
    if [[ $limit -ne -1 && $count -ge $limit ]]; then
        echo "Osiągnięto limit $limit wydruków. Zatrzymywanie..."
        break
    fi
    
    next_count=$((count + 1))
    echo "=========================================================="
    echo "Drukowanie [$next_count]: $nazwa | Waga: $waga kg | Data: $data | Kopie: $kopie"
    
    if [[ -f $exe_path ]]; then
        if ! ./$exe_path --weight "$waga" --name "$nazwa" --date "$data" --operator "$operator" --copies "$kopie" --printer "ZDesigner ZD421-300dpi ZPL" < /dev/null; then
            echo "Błąd: Nie udało się wydrukować pozycji [$next_count]: $nazwa"
            had_error=1
            break
        fi
    else
        echo "Nie znaleziono pliku .exe, uruchamiam przez Pythona..."
        if ! .venv/Scripts/python.exe main.py --weight "$waga" --name "$nazwa" --date "$data" --operator "$operator" --copies "$kopie" --printer "ZDesigner ZD421-300dpi ZPL" < /dev/null; then
            echo "Błąd: Nie udało się wydrukować pozycji [$next_count]: $nazwa"
            had_error=1
            break
        fi
    fi

    count=$next_count
    
    if [[ $limit -ne -1 && $count -ge $limit ]]; then
        echo "Wysłano zadanie nr $count (Limit)."
        break
    fi
    
    if [[ $delay -gt 0 ]]; then
        echo "Czekam $delay sekund na zbuforowanie... (Wciśnij 'p' aby wstrzymać)"
    else
        echo "(Wciśnij 'p' szybko aby wstrzymać)"
    fi
    
    end_time=$(( SECONDS + delay ))
    while [[ $SECONDS -lt $end_time || $delay -eq 0 ]]; do
        remaining=$(( end_time - SECONDS ))
        if [[ $remaining -le 0 ]]; then remaining=0.05; fi
        
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
done <<< "$sorted_data"

echo "=========================================================="
if [[ $had_error -ne 0 ]]; then
    echo "Przerwano po błędzie. Pomyślnie wydrukowano etykiety dla $count palet."
    exit 1
fi

echo "Zakończono. Wydrukowano etykiety dla $count palet."
