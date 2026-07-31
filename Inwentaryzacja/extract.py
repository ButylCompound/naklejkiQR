import pandas as pd
import sys

file_path = "arkusz kontroli jakosci.xlsx"
try:
    df = pd.read_excel(file_path, sheet_name='palety')
except Exception as e:
    print(f"Error reading excel: {e}")
    sys.exit(1)

target_states = ["STOP - OFF SPEC NA SPRZEDAŻ", "STOP", "STOP - patrz opis"]
filtered = df[df['Stan'].isin(target_states)]

out_data = []
for idx, row in filtered.iterrows():
    nazwa = row['Nazwa handlowa']
    
    # Handle dates and times securely
    data_val = row['Data']
    godz_val = row['Godzina końca palety']
    
    if pd.isna(data_val):
        date_str = "1970-01-01"
    else:
        date_str = pd.to_datetime(data_val).strftime('%Y-%m-%d')
        
    if pd.isna(godz_val):
        time_str = "00:00:00"
    else:
        if hasattr(godz_val, 'strftime'):
            time_str = godz_val.strftime('%H:%M:%S')
        else:
            time_str = str(godz_val).strip()
            # Append seconds if missing
            if len(time_str) == 5:
                time_str += ":00"
                
    waga = row['ilość na palecie']
    
    out_data.append({
        'Nazwa': nazwa,
        'Data': f"{date_str} {time_str}",
        'Waga': waga,
        'Operator': 'QC',
        'Kopie': 1
    })

out_df = pd.DataFrame(out_data)
out_df.to_csv("do_wydruku.csv", index=False, encoding='utf-8')
print(f"Zapisano {len(out_df)} wierszy do do_wydruku.csv")
