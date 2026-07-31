import pandas as pd
from collections import defaultdict
from datetime import datetime
import re

def reconcile_inventory(excel_path, csv_path, excel_sheet='palety'):
    """
    Porównuje raport z inwentaryzacji (CSV) z danymi z systemu kontroli jakości (Excel).
    
    Zwraca słownik z wynikami:
    {
        'summary': {'total_expected': int, 'total_scanned': int, 'matched': int, 'missing': int, 'surplus': int},
        'records': list of dicts, gdzie każdy dict to:
            {'Lp': int, 'Produkt': str, 'Waga (kg)': float, 'Data': str, 'Status': str, 'Kategoria': str}
    }
    """
    try:
        # 1. Wczytanie danych z Excela
        df_ex = pd.read_excel(excel_path, sheet_name=excel_sheet)
        df_ex['Excel_Row'] = df_ex.index + 2
    except Exception as e:
        raise RuntimeError(f"Błąd podczas wczytywania pliku Excel ({excel_path}): {e}")
        
    try:
        # 2. Wczytanie danych z CSV (separator ';')
        df_csv = pd.read_csv(csv_path, sep=';')
    except Exception as e:
        raise RuntimeError(f"Błąd podczas wczytywania pliku CSV ({csv_path}): {e}")

    # Czyszczenie Excela: odrzucamy puste wiersze oraz powtórzone wklejone nagłówki
    df_ex = df_ex.dropna(subset=['Nazwa handlowa', 'ilość na palecie'])
    df_ex = df_ex[df_ex['Nazwa handlowa'].astype(str).str.strip() != 'Nazwa handlowa']
    df_ex = df_ex[df_ex['ilość na palecie'].astype(str).str.strip() != 'ilość na palecie']
    
    # Normalizacja kolumny Stan i odfiltrowanie pozycji 'Wydana'
    df_ex['Stan_clean'] = df_ex['Stan'].astype(str).str.strip().str.lower()
    df_ex_expected = df_ex[df_ex['Stan_clean'] != 'wydana'].copy()

    # Szukanie kolumny z ilością/wagą w CSV (np. 'Ilość', 'Waga (kg)', 'Waga')
    ilosc_col = None
    for cand in ['Ilość', 'Ilość (szt./kg)', 'Waga (kg)', 'Waga', 'ilość', 'ilosc']:
        if cand in df_csv.columns:
            ilosc_col = cand
            break
    if not ilosc_col:
        for col_name in df_csv.columns:
            if any(k in str(col_name).lower() for k in ['ilość', 'ilosc', 'waga', 'szt', 'kg']):
                ilosc_col = col_name
                break
    if not ilosc_col:
        ilosc_col = df_csv.columns[2] if len(df_csv.columns) > 2 else df_csv.columns[-1]

    # Usunięcie stopki (wierszy typu 'Liczba palet;47') - zachowujemy tylko wiersze, gdzie Lp jest liczbą
    if 'Lp' in df_csv.columns:
        df_csv = df_csv[pd.to_numeric(df_csv['Lp'], errors='coerce').notna()]

    # Czyszczenie CSV: odrzucamy puste nazwy / ilości
    df_csv = df_csv.dropna(subset=['Produkt', ilosc_col])

    # Funkcje pomocnicze
    def extract_num(val):
        if pd.isna(val) or val is None:
            return 0.0
        if isinstance(val, (int, float)):
            return float(val)
        s = str(val).strip().replace(',', '.')
        match = re.search(r'[-+]?\d*\.?\d+', s)
        if match:
            try:
                return float(match.group())
            except ValueError:
                return 0.0
        return 0.0

    def format_qty(val):
        try:
            f = float(val)
            if f.is_integer():
                return int(f)
            return f
        except Exception:
            return val

    # Funkcje pomocnicze do budowania dokładnego znacznika czasu (YYYY-MM-DD HH:MM)
    def get_ex_dt(row):
        d = pd.to_datetime(row.get('Data'), errors='coerce').strftime('%Y-%m-%d')
        if pd.isna(pd.to_datetime(row.get('Data'), errors='coerce')):
            d = str(row.get('Data', ''))[:10]
        t_val = row.get('Godzina końca palety')
        if pd.isna(t_val) or str(t_val).strip() in ('', 'nan'):
            t = '00:00'
        elif hasattr(t_val, 'strftime'):
            t = t_val.strftime('%H:%M')
        else:
            t = str(t_val).strip()[:5]
            if len(t) == 4 and t[1] == ':':
                t = '0' + t
        return f"{d} {t}".strip()

    def get_csv_dt(row):
        val = row.get('Data naklejki', '')
        dt = pd.to_datetime(val, format='%d.%m.%Y %H:%M', errors='coerce')
        if pd.isna(dt):
            dt = pd.to_datetime(val, format='%Y-%m-%d %H:%M:%S', errors='coerce')
        if pd.isna(dt):
            dt = pd.to_datetime(val, format='%Y-%m-%d %H:%M', errors='coerce')
        if pd.isna(dt):
            try:
                dt = pd.to_datetime(val)
            except Exception:
                pass
        if pd.isna(dt):
            return str(val).strip()
        return dt.strftime('%Y-%m-%d %H:%M')

    # Normalizacja kluczy do parowania (uwzględniająca dokładną godzinę i minutę oraz samą wartość liczbową)
    df_ex_expected['prod_norm'] = df_ex_expected['Nazwa handlowa'].astype(str).str.strip()
    df_ex_expected['waga_norm'] = df_ex_expected['ilość na palecie'].apply(extract_num)
    df_ex_expected['date_norm'] = df_ex_expected.apply(get_ex_dt, axis=1)

    df_csv['prod_norm'] = df_csv['Produkt'].astype(str).str.strip()
    df_csv['waga_norm'] = df_csv[ilosc_col].apply(extract_num)
    df_csv['date_norm'] = df_csv.apply(get_csv_dt, axis=1)

    ex_list = df_ex_expected.to_dict('records')
    csv_list = df_csv.to_dict('records')

    # Budowanie słownika dla szybkiego parowania 1-do-1
    ex_pool = defaultdict(list)
    for idx, r in enumerate(ex_list):
        key = (r['prod_norm'], r['waga_norm'], r['date_norm'])
        ex_pool[key].append(idx)

    matched_ex_indices = set()
    records = []
    
    # 1. Przetwarzanie skanów z magazynu (zgodne oraz nadwyżki)
    for csv_r in csv_list:
        key = (csv_r['prod_norm'], csv_r['waga_norm'], csv_r['date_norm'])
        prod_val = csv_r['prod_norm']
        ilosc_val = format_qty(csv_r['waga_norm'])
        data_val = csv_r['date_norm'] if csv_r['date_norm'] else str(csv_r.get('Data naklejki', '')).strip()
        
        if key in ex_pool and len(ex_pool[key]) > 0:
            match_idx = ex_pool[key].pop(0)
            matched_ex_indices.add(match_idx)
            records.append({
                'Produkt': prod_val,
                'Ilość': ilosc_val,
                'Data': data_val,
                'Status': '🟢 Zgodna',
                'Kategoria': 'zgodne',
                'Excel_Row': ex_list[match_idx]['Excel_Row']
            })
        else:
            # Nadwyżka (zeskanowane na magazynie, ale brak w systemie lub status Wydana)
            records.append({
                'Produkt': prod_val,
                'Ilość': ilosc_val,
                'Data': data_val,
                'Status': '🟡 Nadwyżka',
                'Kategoria': 'nadwyzka'
            })

    # 2. Przetwarzanie pozycji z Excela, które nie zostały zeskanowane (brakujące)
    for idx, ex_r in enumerate(ex_list):
        if idx not in matched_ex_indices:
            prod_val = ex_r['prod_norm']
            ilosc_val = format_qty(ex_r['waga_norm'])
            data_val = ex_r['date_norm']
                
            records.append({
                'Produkt': prod_val,
                'Ilość': ilosc_val,
                'Data': data_val,
                'Status': '🔴 Brak w skanach',
                'Kategoria': 'brakujace'
            })

    # Dodanie numeracji Lp
    for i, rec in enumerate(records, 1):
        rec['Lp'] = i

    # Podsumowanie statystyk
    summary = {
        'total_expected': len(ex_list),
        'total_scanned': len(csv_list),
        'matched': len(matched_ex_indices),
        'missing': len(ex_list) - len(matched_ex_indices),
        'surplus': len(csv_list) - len(matched_ex_indices)
    }

    return {
        'summary': summary,
        'records': records
    }

def reconcile_raw_materials(excel_path, csv_path, excel_sheet='Stan magazynowy surowców'):
    import pandas as pd
    import re
    
    def norm(s):
        return re.sub(r'[^a-z0-9]', '', str(s).lower())
        
    def extract_num(val):
        if pd.isna(val) or val is None: return 0.0
        if isinstance(val, (int, float)): return float(val)
        s = str(val).strip().replace(',', '.')
        match = re.search(r'[-+]?\d*\.?\d+', s)
        return float(match.group()) if match else 0.0

    def format_qty(val):
        try:
            f = float(val)
            if f.is_integer(): return int(f)
            return f
        except Exception: return val

    import openpyxl
    wb = openpyxl.load_workbook(excel_path, data_only=True, read_only=True)
    ws = wb[excel_sheet]
    
    ex_items = {}
    for row in ws.iter_rows(min_row=3, min_col=1, max_col=2):
        cell_name = row[0]
        cell_qty = row[1]
        
        name = str(cell_name.value).strip()
        if name in ['Etykiety wierszy', '(puste)', '0', 'nan', 'Suma końcowa', 'None'] or not name:
            continue
            
        # Ignorowanie nagłówków grup (Tabela Przestawna ustawia indent=0 dla grup, indent>0 dla pozycji)
        indent = cell_name.alignment.indent if cell_name.alignment else 0
        if indent == 0:
            continue
            
        try:
            qty = float(cell_qty.value)
        except (ValueError, TypeError):
            continue
            
        norm_name = norm(name)
        if norm_name not in ex_items:
            ex_items[norm_name] = {'Name': name, 'ExpectedQty': qty, 'ScannedQty': 0.0}
        else:
            ex_items[norm_name]['ExpectedQty'] += qty

    df_csv = pd.read_csv(csv_path, sep=';')
    ilosc_col = None
    for cand in ['Ilość', 'Ilość (szt./kg)', 'Waga (kg)', 'Waga', 'ilość', 'ilosc']:
        if cand in df_csv.columns:
            ilosc_col = cand
            break
    if not ilosc_col:
        for col_name in df_csv.columns:
            if any(k in str(col_name).lower() for k in ['ilość', 'ilosc', 'waga', 'szt', 'kg']):
                ilosc_col = col_name
                break
    if not ilosc_col:
        ilosc_col = df_csv.columns[2] if len(df_csv.columns) > 2 else df_csv.columns[-1]

    # Usunięcie stopki (wierszy typu 'Liczba palet;47') - zachowujemy tylko wiersze, gdzie Lp jest liczbą
    if 'Lp' in df_csv.columns:
        df_csv = df_csv[pd.to_numeric(df_csv['Lp'], errors='coerce').notna()]

    # Czyszczenie CSV: odrzucamy puste nazwy
    df_csv = df_csv.dropna(subset=['Produkt'])

    import difflib
    
    unmatched_scans = {}
    ex_list_for_matching = [{'Name': v['Name'], 'NormName': k} for k, v in ex_items.items()]

    for _, row in df_csv.iterrows():
        name = str(row.get('Produkt', '')).strip()
        if not name or name == 'nan': continue
        qty = extract_num(row.get(ilosc_col))
        
        c_norm = norm(name)
        candidates = []
        for e in ex_list_for_matching:
            e_norm = e['NormName']
            if len(e_norm) < 3: continue
            
            sm = difflib.SequenceMatcher(None, c_norm, e_norm)
            match_len = sum(b.size for b in sm.get_matching_blocks())
            
            # Wskaźnik: jak duża część "krótszego" słowa zawiera się w "dłuższym"
            score = match_len / min(len(c_norm), len(e_norm))
            
            if score >= 0.80:
                candidates.append({
                    'NormName': e_norm,
                    'Score': score,
                    'Len': len(e_norm)
                })
                
        if candidates:
            candidates.sort(key=lambda x: (x['Score'], x['Len']), reverse=True)
            best_match_norm = candidates[0]['NormName']
            ex_items[best_match_norm]['ScannedQty'] += qty
        else:
            if c_norm not in unmatched_scans:
                unmatched_scans[c_norm] = {'Name': name, 'ExpectedQty': 0.0, 'ScannedQty': qty}
            else:
                unmatched_scans[c_norm]['ScannedQty'] += qty

    records = []
    # Zgodne / Braki / Nadwyżki z bazy
    for k, v in ex_items.items():
        if v['ExpectedQty'] == 0 and v['ScannedQty'] == 0:
            continue
        
        diff = v['ScannedQty'] - v['ExpectedQty']
        if abs(diff) < 0.01:
            status = '🟢 Zgodne'
            cat = 'zgodne'
        elif diff < 0:
            status = '🔴 Braki'
            cat = 'brakujace'
        else:
            status = '🟡 Nadwyżka'
            cat = 'nadwyzka'
            
        records.append({
            'Lp': 0,
            'Produkt': v['Name'],
            'Oczekiwana Ilość': format_qty(v['ExpectedQty']),
            'Zeskanowana Ilość': format_qty(v['ScannedQty']),
            'Różnica': format_qty(diff),
            'Status': status,
            'Kategoria': cat
        })
        
    # Nieodnalezione w bazie (Nadwyżki)
    for k, v in unmatched_scans.items():
        diff = v['ScannedQty']
        records.append({
            'Lp': 0,
            'Produkt': v['Name'] + ' (Brak w bazie)',
            'Oczekiwana Ilość': 0,
            'Zeskanowana Ilość': format_qty(v['ScannedQty']),
            'Różnica': format_qty(diff),
            'Status': '🟡 Nadwyżka (Nieznane)',
            'Kategoria': 'nadwyzka'
        })
        
    # Sortowanie i numeracja
    records.sort(key=lambda x: str(x['Produkt']).lower())
    for i, rec in enumerate(records, 1):
        rec['Lp'] = i
        
    summary = {
        'total_expected': sum(v['ExpectedQty'] for v in ex_items.values()),
        'total_scanned': sum(v['ScannedQty'] for v in ex_items.values()) + sum(v['ScannedQty'] for v in unmatched_scans.values()),
        'matched_groups': sum(1 for v in ex_items.values() if abs(v['ScannedQty'] - v['ExpectedQty']) < 0.01 and (v['ExpectedQty'] > 0 or v['ScannedQty'] > 0)),
        'missing_groups': sum(1 for v in ex_items.values() if v['ExpectedQty'] > 0 and v['ScannedQty'] < v['ExpectedQty']),
        'surplus_groups': sum(1 for v in ex_items.values() if v['ScannedQty'] > v['ExpectedQty']) + len(unmatched_scans)
    }

    return {
        'summary': summary,
        'records': records
    }

if __name__ == '__main__':
    # Szybki test modułu
    res = reconcile_inventory('arkusz kontroli jakosci.xlsx', 'inwentaryzacja_Inwentaryzacja_2026-07-22.csv')
    print("Test podsumowania:", res['summary'])
    print("Przykładowe rekordy:", res['records'][:3])
