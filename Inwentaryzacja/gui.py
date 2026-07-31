import tkinter as tk
from tkinter import ttk, filedialog, messagebox
import json
import os
import pandas as pd
from reconciliation import reconcile_inventory, reconcile_raw_materials

STATE_FILE = "state.json"

def load_state():
    if os.path.exists(STATE_FILE):
        try:
            with open(STATE_FILE, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            pass
    return {
        "last_excel_path_palety": "", "last_csv_path_palety": "",
        "last_excel_path_surowce": "", "last_csv_path_surowce": ""
    }

def save_state(state):
    try:
        with open(STATE_FILE, "w", encoding="utf-8") as f:
            json.dump(state, f, ensure_ascii=False, indent=2)
    except Exception as e:
        print(f"Błąd zapisu stanu: {e}")

class BaseInventoryTab(ttk.Frame):
    """Bazowa klasa dla zakładek inwentaryzacyjnych."""
    def __init__(self, parent):
        super().__init__(parent)
        
    def populate_data(self, data):
        """Metoda do nadpisania w klasach dziedziczących."""
        pass

class PalletInventoryTab(BaseInventoryTab):
    """Zakładka dedykowana inwentaryzacji palet i porównaniu z Excelem."""
    def __init__(self, parent, update_excel_callback=None):
        super().__init__(parent)
        self.update_excel_callback = update_excel_callback
        self.all_records = []
        self.current_sort_col = "Lp"
        self.current_sort_reverse = False
        
        self.create_widgets()
        
    def create_widgets(self):
        # 1. Panel KPI (Podsumowanie)
        self.kpi_frame = ttk.LabelFrame(self, text=" Podsumowanie Inwentaryzacji ", padding=10)
        self.kpi_frame.pack(fill="x", padx=10, pady=5)
        
        self.lbl_total_sys = ttk.Label(self.kpi_frame, text="Ogółem w systemie: 0", font=("Segoe UI", 10, "bold"))
        self.lbl_total_sys.pack(side="left", padx=15)
        
        self.lbl_total_scan = ttk.Label(self.kpi_frame, text="Zeskanowano: 0", font=("Segoe UI", 10, "bold"))
        self.lbl_total_scan.pack(side="left", padx=15)
        
        self.lbl_matched = ttk.Label(self.kpi_frame, text="🟢 Zgodne: 0", font=("Segoe UI", 10, "bold"), foreground="#0a7e07")
        self.lbl_matched.pack(side="left", padx=15)
        
        self.lbl_missing = ttk.Label(self.kpi_frame, text="🔴 Brak w skanach: 0", font=("Segoe UI", 10, "bold"), foreground="#c61a09")
        self.lbl_missing.pack(side="left", padx=15)
        
        self.lbl_surplus = ttk.Label(self.kpi_frame, text="🟡 Nadwyżka: 0", font=("Segoe UI", 10, "bold"), foreground="#b57d00")
        self.lbl_surplus.pack(side="left", padx=15)
        
        # 2. Panel Filtrowania i Wyszukiwania
        self.filter_frame = ttk.Frame(self, padding=5)
        self.filter_frame.pack(fill="x", padx=10, pady=5)
        
        ttk.Label(self.filter_frame, text="Widok:").pack(side="left", padx=5)
        
        self.filter_var = tk.StringVar(value="all")
        ttk.Radiobutton(self.filter_frame, text="Wszystkie", variable=self.filter_var, value="all", command=self.apply_filters).pack(side="left", padx=5)
        ttk.Radiobutton(self.filter_frame, text="🟢 Zgodne", variable=self.filter_var, value="zgodne", command=self.apply_filters).pack(side="left", padx=5)
        ttk.Radiobutton(self.filter_frame, text="🔴 Brak w skanach", variable=self.filter_var, value="brakujace", command=self.apply_filters).pack(side="left", padx=5)
        ttk.Radiobutton(self.filter_frame, text="🟡 Nadwyżka", variable=self.filter_var, value="nadwyzka", command=self.apply_filters).pack(side="left", padx=5)
        
        # Szukajka
        ttk.Label(self.filter_frame, text=" |   Szukaj:").pack(side="left", padx=(15, 5))
        self.entry_search = ttk.Entry(self.filter_frame, width=25)
        self.entry_search.pack(side="left", padx=5)
        self.entry_search.bind("<KeyRelease>", lambda e: self.apply_filters())
        
        ttk.Button(self.filter_frame, text="Czyść", command=self.clear_search).pack(side="left", padx=2)
        
        # 3. Tabela (Treeview)
        self.table_frame = ttk.Frame(self)
        self.table_frame.pack(fill="both", expand=True, padx=10, pady=5)
        
        # Kolumny zgodne z wytycznymi L125: tylko te pola, które istnieją w CSV i arkuszu + Lp
        columns = ("Lp", "Alejka", "Produkt", "Ilość", "Data", "Status")
        self.tree = ttk.Treeview(self.table_frame, columns=columns, show="headings", selectmode="extended")
        
        # Paski przewijania
        vsb = ttk.Scrollbar(self.table_frame, orient="vertical", command=self.tree.yview)
        hsb = ttk.Scrollbar(self.table_frame, orient="horizontal", command=self.tree.xview)
        self.tree.configure(yscrollcommand=vsb.set, xscrollcommand=hsb.set)
        
        self.tree.grid(row=0, column=0, sticky="nsew")
        vsb.grid(row=0, column=1, sticky="ns")
        hsb.grid(row=1, column=0, sticky="ew")
        
        self.table_frame.grid_rowconfigure(0, weight=1)
        self.table_frame.grid_columnconfigure(0, weight=1)
        
        # Nagłówki i szerokości
        self.tree.heading("Lp", text="Lp", command=lambda: self.sort_column("Lp"))
        self.tree.heading("Alejka", text="Alejka", command=lambda: self.sort_column("Alejka"))
        self.tree.heading("Produkt", text="Produkt / Nazwa handlowa", command=lambda: self.sort_column("Produkt"))
        self.tree.heading("Ilość", text="Ilość", command=lambda: self.sort_column("Ilość"))
        self.tree.heading("Data", text="Data", command=lambda: self.sort_column("Data"))
        self.tree.heading("Status", text="Status", command=lambda: self.sort_column("Status"))
        
        self.tree.column("Lp", width=50, anchor="center")
        self.tree.column("Produkt", width=220, anchor="w")
        self.tree.column("Ilość", width=100, anchor="center")
        self.tree.column("Data", width=140, anchor="center")
        self.tree.column("Status", width=150, anchor="center")
        
        # Konfiguracja tagów (kolorowanie wierszy)
        self.tree.tag_configure("zgodne", background="#e8f8e8", foreground="#000000")
        self.tree.tag_configure("brakujace", background="#fde8e8", foreground="#000000")
        self.tree.tag_configure("nadwyzka", background="#fef6e4", foreground="#000000")
        
        # 4. Panel dolny z eksportem
        self.bottom_frame = ttk.Frame(self, padding=10)
        self.bottom_frame.pack(fill="x", padx=10, pady=5)
        
        self.btn_export = ttk.Button(self.bottom_frame, text="Eksportuj widoczną tabelę do Excela / CSV...", command=self.export_visible)
        self.btn_export.pack(side="right", padx=5)
        
        if self.update_excel_callback:
            self.btn_update = ttk.Button(self.bottom_frame, text="Aktualizuj datę w Excelu dla zgodnych...", command=self.update_excel_callback)
            self.btn_update.pack(side="right", padx=5)
        
        self.lbl_count = ttk.Label(self.bottom_frame, text="Wyświetlono wierszy: 0")
        self.lbl_count.pack(side="left", padx=5)

    def clear_search(self):
        self.entry_search.delete(0, tk.END)
        self.apply_filters()
        
    def populate_data(self, data):
        """Przyjmuje strukturę wygenerowaną przez reconcile_inventory."""
        summary = data.get("summary", {})
        self.all_records = data.get("records", [])
        
        # Aktualizacja KPI
        self.lbl_total_sys.config(text=f"Ogółem w systemie: {summary.get('total_expected', 0)}")
        self.lbl_total_scan.config(text=f"Zeskanowano: {summary.get('total_scanned', 0)}")
        self.lbl_matched.config(text=f"🟢 Zgodne: {summary.get('matched', 0)}")
        self.lbl_missing.config(text=f"🔴 Brak w skanach: {summary.get('missing', 0)}")
        self.lbl_surplus.config(text=f"🟡 Nadwyżka: {summary.get('surplus', 0)}")
        
        # Odświeżenie tabeli
        self.apply_filters()
        
    def apply_filters(self):
        # Czyszczenie Treeview
        for item in self.tree.get_children():
            self.tree.delete(item)
            
        filter_type = self.filter_var.get()
        search_query = self.entry_search.get().strip().lower()
        
        displayed_count = 0
        for rec in self.all_records:
            # Filtr kategorii
            if filter_type != "all" and rec.get("Kategoria") != filter_type:
                continue
                
            # Szukajka po Nazwie lub Ilości lub Stanie
            if search_query:
                prod_str = str(rec.get("Produkt", "")).lower()
                ilosc_str = str(rec.get("Ilość", "")).lower()
                stat_str = str(rec.get("Status", "")).lower()
                if search_query not in prod_str and search_query not in ilosc_str and search_query not in stat_str:
                    continue
                    
            tag = rec.get("Kategoria", "")
            self.tree.insert("", "end", values=(
                rec.get("Lp", ""),
                rec.get("Alejka", ""),
                rec.get("Produkt", ""),
                rec.get("Ilość", ""),
                rec.get("Data", ""),
                rec.get("Status", "")
            ), tags=(tag,))
            displayed_count += 1
            
        self.lbl_count.config(text=f"Wyświetlono wierszy: {displayed_count}")
        
    def sort_column(self, col):
        """Sortowanie tabeli po kliknięciu nagłówka."""
        if self.current_sort_col == col:
            self.current_sort_reverse = not self.current_sort_reverse
        else:
            self.current_sort_col = col
            self.current_sort_reverse = False
            
        # Sortujemy self.all_records w pamięci i odświeżamy
        def sort_key(rec):
            val = rec.get(col, "")
            if col in ("Lp", "Ilość"):
                try:
                    return float(val)
                except ValueError:
                    return 0.0
            return str(val).lower()
            
        self.all_records.sort(key=sort_key, reverse=self.current_sort_reverse)
        self.apply_filters()
        
    def export_visible(self):
        if not self.all_records:
            messagebox.showwarning("Brak danych", "Brak danych do eksportu. Najpierw wykonaj porównanie.")
            return
            
        file_path = filedialog.asksaveasfilename(
            title="Eksportuj raport",
            defaultextension=".xlsx",
            filetypes=[("Plik Excel", "*.xlsx"), ("Plik CSV", "*.csv")]
        )
        if not file_path:
            return
            
        # Zbieramy dane obecnie widoczne w Treeview
        export_data = []
        for item_id in self.tree.get_children():
            vals = self.tree.item(item_id, "values")
            export_data.append({
                "Lp": vals[0],
                "Alejka": vals[1],
                "Produkt": vals[2],
                "Ilość": vals[3],
                "Data": vals[4],
                "Status": vals[5]
            })
            
        df_export = pd.DataFrame(export_data)
        try:
            if file_path.endswith(".csv"):
                df_export.to_csv(file_path, index=False, sep=";", encoding="utf-8-sig")
            else:
                df_export.to_excel(file_path, index=False, sheet_name="Raport inwentaryzacji")
            messagebox.showinfo("Sukces", f"Zapisano raport w pliku:\n{file_path}")
        except Exception as e:
            messagebox.showerror("Błąd zapisu", f"Nie udało się zapisać pliku:\n{e}")

class RawMaterialsTab(BaseInventoryTab):
    """Zakładka surowców z tabelą różnic sumarycznych."""
    def __init__(self, parent):
        super().__init__(parent)
        self.all_records = []
        self.current_sort_col = "Lp"
        self.current_sort_reverse = False
        
        self.create_widgets()
        
    def create_widgets(self):
        # 1. Panel KPI
        self.kpi_frame = ttk.LabelFrame(self, text=" 📊 Podsumowanie Surowców ", padding=10)
        self.kpi_frame.pack(fill="x", padx=10, pady=5)
        
        self.lbl_matched = ttk.Label(self.kpi_frame, text="🟢 Zgodne: 0", font=("Segoe UI", 10, "bold"), foreground="#0a7e07")
        self.lbl_matched.pack(side="left", padx=15)
        
        self.lbl_missing = ttk.Label(self.kpi_frame, text="🔴 Braki: 0", font=("Segoe UI", 10, "bold"), foreground="#c61a09")
        self.lbl_missing.pack(side="left", padx=15)
        
        self.lbl_surplus = ttk.Label(self.kpi_frame, text="🟡 Nadwyżki: 0", font=("Segoe UI", 10, "bold"), foreground="#b57d00")
        self.lbl_surplus.pack(side="left", padx=15)
        
        # 2. Panel Filtrowania
        self.filter_frame = ttk.Frame(self, padding=5)
        self.filter_frame.pack(fill="x", padx=10, pady=5)
        
        ttk.Label(self.filter_frame, text="Widok:").pack(side="left", padx=5)
        self.filter_var = tk.StringVar(value="all")
        ttk.Radiobutton(self.filter_frame, text="Wszystkie", variable=self.filter_var, value="all", command=self.apply_filters).pack(side="left", padx=5)
        ttk.Radiobutton(self.filter_frame, text="🟢 Zgodne", variable=self.filter_var, value="zgodne", command=self.apply_filters).pack(side="left", padx=5)
        ttk.Radiobutton(self.filter_frame, text="🔴 Braki", variable=self.filter_var, value="brakujace", command=self.apply_filters).pack(side="left", padx=5)
        ttk.Radiobutton(self.filter_frame, text="🟡 Nadwyżki", variable=self.filter_var, value="nadwyzka", command=self.apply_filters).pack(side="left", padx=5)
        
        ttk.Label(self.filter_frame, text=" |   🔍 Szukaj:").pack(side="left", padx=(15, 5))
        self.entry_search = ttk.Entry(self.filter_frame, width=25)
        self.entry_search.pack(side="left", padx=5)
        self.entry_search.bind("<KeyRelease>", lambda e: self.apply_filters())
        ttk.Button(self.filter_frame, text="Czyść", command=self.clear_search).pack(side="left", padx=2)
        
        # 3. Tabela
        self.table_frame = ttk.Frame(self)
        self.table_frame.pack(fill="both", expand=True, padx=10, pady=5)
        
        columns = ("Lp", "Produkt", "Oczekiwana Ilość", "Zeskanowana Ilość", "Różnica", "Status")
        self.tree = ttk.Treeview(self.table_frame, columns=columns, show="headings", selectmode="extended")
        
        vsb = ttk.Scrollbar(self.table_frame, orient="vertical", command=self.tree.yview)
        hsb = ttk.Scrollbar(self.table_frame, orient="horizontal", command=self.tree.xview)
        self.tree.configure(yscrollcommand=vsb.set, xscrollcommand=hsb.set)
        
        self.tree.grid(row=0, column=0, sticky="nsew")
        vsb.grid(row=0, column=1, sticky="ns")
        hsb.grid(row=1, column=0, sticky="ew")
        
        self.table_frame.grid_rowconfigure(0, weight=1)
        self.table_frame.grid_columnconfigure(0, weight=1)
        
        self.tree.heading("Lp", text="Lp", command=lambda: self.sort_column("Lp"))
        self.tree.heading("Produkt", text="Produkt (Excel Baza)", command=lambda: self.sort_column("Produkt"))
        self.tree.heading("Oczekiwana Ilość", text="Oczekiwana Ilość", command=lambda: self.sort_column("Oczekiwana Ilość"))
        self.tree.heading("Zeskanowana Ilość", text="Zeskanowana Ilość", command=lambda: self.sort_column("Zeskanowana Ilość"))
        self.tree.heading("Różnica", text="Różnica", command=lambda: self.sort_column("Różnica"))
        self.tree.heading("Status", text="Status", command=lambda: self.sort_column("Status"))
        
        self.tree.column("Lp", width=40, anchor="center")
        self.tree.column("Produkt", width=250, anchor="w")
        self.tree.column("Oczekiwana Ilość", width=120, anchor="center")
        self.tree.column("Zeskanowana Ilość", width=120, anchor="center")
        self.tree.column("Różnica", width=100, anchor="center")
        self.tree.column("Status", width=150, anchor="center")
        
        self.tree.tag_configure("zgodne", background="#e8f8e8", foreground="#000000")
        self.tree.tag_configure("brakujace", background="#fde8e8", foreground="#000000")
        self.tree.tag_configure("nadwyzka", background="#fef6e4", foreground="#000000")
        
        # 4. Panel dolny z eksportem
        self.bottom_frame = ttk.Frame(self, padding=10)
        self.bottom_frame.pack(fill="x", padx=10, pady=5)
        
        self.btn_export = ttk.Button(self.bottom_frame, text="📥 Eksportuj widoczną tabelę...", command=self.export_visible)
        self.btn_export.pack(side="right", padx=5)
        
        self.lbl_count = ttk.Label(self.bottom_frame, text="Wyświetlono wierszy: 0")
        self.lbl_count.pack(side="left", padx=5)

    def clear_search(self):
        self.entry_search.delete(0, tk.END)
        self.apply_filters()
        
    def populate_data(self, data):
        summary = data.get("summary", {})
        self.all_records = data.get("records", [])
        
        self.lbl_matched.config(text=f"🟢 Zgodne: {summary.get('matched_groups', 0)}")
        self.lbl_missing.config(text=f"🔴 Braki: {summary.get('missing_groups', 0)}")
        self.lbl_surplus.config(text=f"🟡 Nadwyżki: {summary.get('surplus_groups', 0)}")
        
        self.apply_filters()
        
    def apply_filters(self):
        for item in self.tree.get_children():
            self.tree.delete(item)
            
        filter_type = self.filter_var.get()
        search_query = self.entry_search.get().strip().lower()
        
        displayed_count = 0
        for rec in self.all_records:
            if filter_type != "all" and rec.get("Kategoria") != filter_type:
                continue
                
            if search_query:
                prod_str = str(rec.get("Produkt", "")).lower()
                if search_query not in prod_str:
                    continue
                    
            tag = rec.get("Kategoria", "")
            self.tree.insert("", "end", values=(
                rec.get("Lp", ""),
                rec.get("Produkt", ""),
                rec.get("Oczekiwana Ilość", ""),
                rec.get("Zeskanowana Ilość", ""),
                rec.get("Różnica", ""),
                rec.get("Status", "")
            ), tags=(tag,))
            displayed_count += 1
            
        self.lbl_count.config(text=f"Wyświetlono wierszy: {displayed_count}")
        
    def sort_column(self, col):
        if self.current_sort_col == col:
            self.current_sort_reverse = not self.current_sort_reverse
        else:
            self.current_sort_col = col
            self.current_sort_reverse = False
            
        def sort_key(rec):
            val = rec.get(col, "")
            if col in ("Lp", "Oczekiwana Ilość", "Zeskanowana Ilość", "Różnica"):
                try:
                    return float(val)
                except ValueError:
                    return 0.0
            return str(val).lower()
            
        self.all_records.sort(key=sort_key, reverse=self.current_sort_reverse)
        self.apply_filters()
        
    def export_visible(self):
        if not self.all_records:
            messagebox.showwarning("Brak danych", "Brak danych do eksportu.")
            return
            
        file_path = filedialog.asksaveasfilename(
            title="Eksportuj raport",
            defaultextension=".xlsx",
            filetypes=[("Plik Excel", "*.xlsx"), ("Plik CSV", "*.csv")]
        )
        if not file_path:
            return
            
        export_data = []
        for item_id in self.tree.get_children():
            vals = self.tree.item(item_id, "values")
            export_data.append({
                "Lp": vals[0],
                "Produkt": vals[1],
                "Oczekiwana Ilość": vals[2],
                "Zeskanowana Ilość": vals[3],
                "Różnica": vals[4],
                "Status": vals[5]
            })
            
        df_export = pd.DataFrame(export_data)
        try:
            if file_path.endswith(".csv"):
                df_export.to_csv(file_path, index=False, sep=";", encoding="utf-8-sig")
            else:
                df_export.to_excel(file_path, index=False, sheet_name="Raport surowców")
            messagebox.showinfo("Sukces", f"Zapisano raport w pliku:\n{file_path}")
        except Exception as e:
            messagebox.showerror("Błąd zapisu", f"Nie udało się zapisać pliku:\n{e}")

class MainWindow(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("System Kontroli Inwentaryzacji - NaklejkiQR")
        self.geometry("1050x700")
        self.minsize(800, 500)
        
        self.state_data = load_state()
        
        self.create_top_panel()
        self.create_notebook()
        self.on_tab_changed()
        
    def create_top_panel(self):
        top_frame = ttk.LabelFrame(self, text=" 📂 Parametry Wejściowe ", padding=10)
        top_frame.pack(fill="x", padx=10, pady=5)
        
        # Wiersz 1: Plik CSV (Inwentaryzacja)
        ttk.Label(top_frame, text="Raport CSV (Inwentaryzacja):", width=25).grid(row=0, column=0, sticky="w", pady=5)
        self.entry_csv = ttk.Entry(top_frame)
        self.entry_csv.grid(row=0, column=1, sticky="ew", padx=5, pady=5)
        # Value will be set by on_tab_changed
        
        btn_browse_csv = ttk.Button(top_frame, text="Wybierz plik CSV...", command=self.browse_csv)
        btn_browse_csv.grid(row=0, column=2, padx=5, pady=5)
        
        # Wiersz 2: Plik Excel (Arkusz kontroli jakości)
        ttk.Label(top_frame, text="Arkusz Excel (Plik QA):", width=25).grid(row=1, column=0, sticky="w", pady=5)
        self.entry_excel = ttk.Entry(top_frame)
        self.entry_excel.grid(row=1, column=1, sticky="ew", padx=5, pady=5)
        # Value will be set by on_tab_changed
        
        btn_browse_ex = ttk.Button(top_frame, text="Wybierz plik Excel...", command=self.browse_excel)
        btn_browse_ex.grid(row=1, column=2, padx=5, pady=5)
        
        # Wiersz 3: Przycisk Uruchom
        self.btn_run = ttk.Button(top_frame, text="▶ Uruchom porównanie i analizę", command=self.run_reconciliation)
        self.btn_run.grid(row=0, column=3, rowspan=2, padx=15, pady=5, sticky="nsew")
        
        top_frame.grid_columnconfigure(1, weight=1)
        
    def create_notebook(self):
        self.notebook = ttk.Notebook(self)
        self.notebook.pack(fill="both", expand=True, padx=10, pady=5)
        
        # Zakładka 1
        self.tab_palety = PalletInventoryTab(self.notebook, update_excel_callback=self.update_excel_dates)
        self.notebook.add(self.tab_palety, text="Inwentaryzacja palet ")
        
        # Zakładka 2
        self.tab_surowce = RawMaterialsTab(self.notebook)
        self.notebook.add(self.tab_surowce, text="Surowce ")
        
        self.notebook.bind("<<NotebookTabChanged>>", self.on_tab_changed)
        
    def on_tab_changed(self, event=None):
        current_tab = self.notebook.index(self.notebook.select())
        prefix = "_palety" if current_tab == 0 else "_surowce"
        
        self.entry_csv.delete(0, tk.END)
        self.entry_csv.insert(0, self.state_data.get(f"last_csv_path{prefix}", ""))
        
        self.entry_excel.delete(0, tk.END)
        self.entry_excel.insert(0, self.state_data.get(f"last_excel_path{prefix}", ""))

    def browse_csv(self):
        path = filedialog.askopenfilename(
            title="Wybierz raport CSV z inwentaryzacji",
            filetypes=[("Pliki CSV", "*.csv"), ("Wszystkie pliki", "*.*")]
        )
        if path:
            self.entry_csv.delete(0, tk.END)
            self.entry_csv.insert(0, path)
            current_tab = self.notebook.index(self.notebook.select())
            prefix = "_palety" if current_tab == 0 else "_surowce"
            self.state_data[f"last_csv_path{prefix}"] = path
            save_state(self.state_data)
            
    def browse_excel(self):
        path = filedialog.askopenfilename(
            title="Wybierz arkusz kontroli jakości (Excel)",
            filetypes=[("Pliki Excel", "*.xlsx;*.xls"), ("Wszystkie pliki", "*.*")]
        )
        if path:
            self.entry_excel.delete(0, tk.END)
            self.entry_excel.insert(0, path)
            current_tab = self.notebook.index(self.notebook.select())
            prefix = "_palety" if current_tab == 0 else "_surowce"
            self.state_data[f"last_excel_path{prefix}"] = path
            save_state(self.state_data)
            
    def run_reconciliation(self):
        csv_path = self.entry_csv.get().strip()
        excel_path = self.entry_excel.get().strip()
        
        if not csv_path or not os.path.exists(csv_path):
            messagebox.showerror("Błąd", "Wskazany plik CSV nie istnieje lub ścieżka jest pusta.")
            return
            
        if not excel_path or not os.path.exists(excel_path):
            messagebox.showerror("Błąd", "Wskazany plik Excel nie istnieje lub ścieżka jest pusta.")
            return
            
        # Zapis stanu
        current_tab = self.notebook.index(self.notebook.select())
        prefix = "_palety" if current_tab == 0 else "_surowce"
        self.state_data[f"last_csv_path{prefix}"] = csv_path
        self.state_data[f"last_excel_path{prefix}"] = excel_path
        save_state(self.state_data)
        
        self.btn_run.config(state="disabled", text="Przetwarzanie...")
        self.update()
        
        try:
            current_tab = self.notebook.index(self.notebook.select())
            if current_tab == 0:
                results = reconcile_inventory(excel_path, csv_path)
                self.tab_palety.populate_data(results)
                messagebox.showinfo("Gotowe", f"Porównanie palet zakończone sukcesem!\n\nPrzeanalizowano:\n• Oczekiwanie w systemie: {results['summary']['total_expected']}\n• Zeskanowane na magazynie: {results['summary']['total_scanned']}")
            else:
                results = reconcile_raw_materials(excel_path, csv_path)
                self.tab_surowce.populate_data(results)
                messagebox.showinfo("Gotowe", f"Porównanie surowców zakończone sukcesem!\n\nPrzeanalizowano:\n• Zgodne: {results['summary']['matched_groups']}\n• Braki: {results['summary']['missing_groups']}\n• Nadwyżki: {results['summary']['surplus_groups']}")
        except Exception as e:
            messagebox.showerror("Błąd przetwarzania", f"Wystąpił błąd podczas porównywania:\n\n{e}")
        finally:
            self.btn_run.config(state="normal", text="▶ Uruchom porównanie i analizę")
        
    def update_excel_dates(self):
        records = getattr(self.tab_palety, 'all_records', [])
        matched_rows = [r.get('Excel_Row') for r in records if r.get('Kategoria') == 'zgodne' and r.get('Excel_Row')]
        
        if not matched_rows:
            messagebox.showinfo("Brak danych", "Brak zgodnych palet do zaktualizowania.")
            return
            
        excel_path = self.entry_excel.get().strip()
        if not os.path.exists(excel_path):
            messagebox.showerror("Błąd", "Plik Excel nie istnieje lub nie został wybrany.")
            return
            
        confirm = messagebox.askyesno("Potwierdzenie", f"Czy na pewno chcesz zapisać dzisiejszą datę w kolumnie 'data inwentaryzacji' dla {len(matched_rows)} zgodnych palet w pliku oryginalnym?\n\nUWAGA: Zmiany zostaną naniesione bezpośrednio na plik:\n{os.path.basename(excel_path)}")
        if not confirm:
            return
            
        try:
            import xlwings as xw
            from datetime import datetime
            
            # Wymuszamy uruchomienie Excela w tle
            with xw.App(visible=False) as app_excel:
                wb = app_excel.books.open(os.path.abspath(excel_path))
                ws = wb.sheets['palety']
                
                # Szybkie szukanie kolumny z datą w pierwszym wierszu (zakres A1:Z1)
                header_row = ws.range('A1:Z1').value
                col_idx = None
                for i, val in enumerate(header_row):
                    if val and str(val).strip().lower() == 'data inwentaryzacji':
                        col_idx = i + 1  # xlwings indeksuje od 1
                        break
                        
                if not col_idx:
                    messagebox.showerror("Błąd", "Nie znaleziono kolumny 'data inwentaryzacji' w arkuszu 'palety'.")
                    return
                    
                today = datetime.now().date()
                
                # Zapisujemy daty bez naruszania struktury pliku
                for row_idx in matched_rows:
                    ws.range((row_idx, col_idx)).value = today
                    
                wb.save()
                
            messagebox.showinfo("Sukces", "Plik Excel został zaktualizowany.")
            
        except ImportError:
            messagebox.showerror("Brak biblioteki", "Brakuje biblioteki 'xlwings'. Upewnij się, że zaktualizowałeś środowisko (requirements.txt).")
        except Exception as e:
            messagebox.showerror("Błąd zapisu", f"Wystąpił błąd podczas aktualizacji pliku Excel (sprawdź czy plik nie jest zablokowany):\n{e}")

if __name__ == "__main__":
    app = MainWindow()
    app.mainloop()
