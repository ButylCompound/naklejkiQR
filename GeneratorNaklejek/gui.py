import tkinter as tk
from tkinter import ttk, messagebox
from datetime import datetime, timedelta
import json
import os
import subprocess

from label_generator import LABEL_SIZES, generate_pdf
from label_utils import normalize_label_date, numbered_pack_names, validate_label_input, validate_lot_allocations

CREATE_NO_WINDOW = 0x08000000 if os.name == 'nt' else 0

STATE_FILE = "state.json"
def load_state():
    if os.path.exists(STATE_FILE):
        try:
            with open(STATE_FILE, "r") as f:
                return json.load(f)
        except:
            pass
    return {"last_product_name": ""}

def save_state(state):
    with open(STATE_FILE, "w") as f:
        json.dump(state, f)

def factory_date_needs_warning(label_date, today=None):
    today = today or datetime.now().date()
    entered_date = datetime.strptime(label_date, "%Y-%m-%d %H:%M").date()
    return entered_date not in (today, today - timedelta(days=1))

def polish_error_message(error):
    message = str(error)
    translations = {
        'Product name is required.': 'Nazwa produktu jest wymagana.',
        'Product name cannot contain line breaks or tabs.': 'Nazwa produktu nie może zawierać nowych wierszy ani tabulatorów.',
        'Quantity must be a number, for example 500 or 123.5.': 'Ilość netto musi być liczbą, na przykład 500 lub 123,5.',
        'Quantity must be greater than zero.': 'Ilość netto musi być większa od zera.',
        "Unit must be 'kg', 'szt.' or empty.": "Jednostką musi być „kg” lub „szt.”.",
        'Operator initials must contain 2 or 3 characters, or be empty.': 'Inicjały operatora muszą mieć 2 lub 3 znaki albo pozostać puste.',
        'Operator initials cannot contain line breaks or tabs.': 'Inicjały operatora nie mogą zawierać nowych wierszy ani tabulatorów.',
        'Date must use the format YYYY-MM-DD HH:MM or YYYY-MM-DD HH:MM:SS.': 'Data i czas muszą mieć format RRRR-MM-DD GG:MM.',
        'Pack count must be a positive integer.': 'Liczba opakowań musi być dodatnią liczbą całkowitą.',
    }
    if message in translations:
        return translations[message]

    qr_fields = {
        'product name': 'Nazwa produktu',
        'quantity': 'Ilość netto',
        'unit': 'Jednostka',
        'operator': 'Inicjały operatora',
    }
    for english_name, polish_name in qr_fields.items():
        if message.startswith(f'The {english_name} cannot contain'):
            return f"{polish_name} nie może zawierać znaku „|”, ponieważ oddziela on pola kodu QR."

    if message.startswith("Template file '"):
        template_name = message.split("'", 2)[1]
        if message.endswith(' was not found.'):
            return f'Nie znaleziono pliku szablonu „{template_name}”.'
        return f'Plik szablonu „{template_name}” jest nieprawidłowy.'
    if message.startswith("Neither 'pdflatex.exe'"):
        return 'Nie można uruchomić programu pdflatex. Sprawdź, czy jest dostępny w zmiennej PATH.'
    if message.startswith('pdflatex failed while compiling'):
        return 'Nie udało się utworzyć pliku PDF etykiety.'
    if message.startswith('pdflatex did not create the expected PDF file'):
        return 'Program pdflatex nie utworzył oczekiwanego pliku PDF.'
    if message.startswith('Błąd drukowania (Windows):'):
        return 'Nie udało się wysłać pliku PDF do drukarki w systemie Windows.'
    if message.startswith('Błąd drukowania z WSL:'):
        return 'Nie udało się wysłać pliku PDF do drukarki z WSL.'
    if message.startswith(('Błąd ', 'Automatyczne ')):
        return message
    return 'Wystąpił nieoczekiwany błąd.'

def get_printers():
    printers = ["Zebra ZD421"]
    if os.name == 'nt':
        try:
            output = subprocess.check_output(
                ['powershell', '-Command', 'Get-Printer | Select-Object -ExpandProperty Name'],
                text=True,
                creationflags=CREATE_NO_WINDOW
            )
            found = [p.strip() for p in output.split('\n') if p.strip()]
            if found:
                printers = found
        except Exception:
            pass
    return printers

def print_pdf(pdf_path, printer_name="Zebra ZD421", match_pdf_size=False):
    is_wsl = False
    try:
        if "microsoft" in os.uname().release.lower():
            is_wsl = True
    except AttributeError:
        pass
        
    is_windows = os.name == 'nt'
    print_settings = "shrink,disable-auto-rotation" if match_pdf_size else "shrink,landscape"
    
    if is_windows:
        import glob
        sumatra_paths = glob.glob("SumatraPDF*.exe")
        sumatra_path = sumatra_paths[0] if sumatra_paths else "SumatraPDF.exe"
        
        print_cmd = [sumatra_path, "-print-settings", print_settings, "-print-to", printer_name, "-silent", pdf_path]
        try:
            subprocess.run(print_cmd, check=True, creationflags=CREATE_NO_WINDOW)
        except Exception as e:
            raise Exception(f"Błąd drukowania (Windows): {e}")
    elif is_wsl:
        try:
            win_path_result = subprocess.run(["wslpath", "-w", pdf_path], capture_output=True, text=True, check=True, creationflags=CREATE_NO_WINDOW)
            win_pdf_path = win_path_result.stdout.strip()
            print_cmd = ["SumatraPDF.exe", "-print-to", printer_name, "-silent", win_pdf_path]
            if match_pdf_size:
                print_cmd[1:1] = ["-print-settings", print_settings]
            subprocess.run(print_cmd, check=True, creationflags=CREATE_NO_WINDOW)
        except Exception as e:
            raise Exception(f"Błąd drukowania z WSL: {e}")
    else:
        raise Exception("Automatyczne drukowanie jest skonfigurowane tylko dla Windows/WSL.")

class ScrollableForm(ttk.Frame):
    def __init__(self, parent):
        super().__init__(parent)
        self.columnconfigure(0, weight=1)
        self.rowconfigure(0, weight=1)
        self.canvas = tk.Canvas(self, highlightthickness=0, yscrollincrement=20)
        self.scrollbar = ttk.Scrollbar(self, orient="vertical", command=self.canvas.yview)
        self.canvas.configure(yscrollcommand=self.scrollbar.set)
        self.canvas.grid(row=0, column=0, sticky="nsew")
        self.scrollbar.grid(row=0, column=1, sticky="ns")
        self.body = ttk.Frame(self.canvas, padding=25)
        self.window = self.canvas.create_window((0, 0), window=self.body, anchor="nw")
        self.canvas.bind('<Configure>', self._resize)
        self.body.bind('<Configure>', self._resize)

    def _resize(self, event):
        self.canvas.itemconfigure(self.window, width=self.canvas.winfo_width())
        height = self.body.winfo_reqheight()
        self.canvas.configure(scrollregion=(0, 0, self.canvas.winfo_width(), height))
        if height > self.canvas.winfo_height():
            self.scrollbar.grid()
        else:
            self.scrollbar.grid_remove()
            self.canvas.yview_moveto(0)

    def bind_contents(self, widget=None):
        def bind(widget):
            widget.bind('<MouseWheel>', self._wheel, add='+')
            widget.bind('<Button-4>', self._wheel, add='+')
            widget.bind('<Button-5>', self._wheel, add='+')
            widget.bind('<FocusIn>', self._show_focus, add='+')
            for child in widget.winfo_children():
                bind(child)
        bind(widget or self)

    def _wheel(self, event):
        if self.body.winfo_reqheight() <= self.canvas.winfo_height():
            return
        if event.num in (4, 5):
            steps = -1 if event.num == 4 else 1
        else:
            steps = (-1 if event.delta > 0 else 1) * max(1, abs(event.delta) // 120)
        self.canvas.yview_scroll(steps * 3, 'units')
        return 'break'

    def _show_focus(self, event):
        widget = event.widget
        top = widget.winfo_rooty() - self.body.winfo_rooty() - 20
        bottom = top + widget.winfo_height() + 40
        visible_top = self.canvas.canvasy(0)
        visible_height = self.canvas.winfo_height()
        if top < visible_top:
            self.canvas.yview_moveto(top / self.body.winfo_reqheight())
        elif bottom > visible_top + visible_height:
            self.canvas.yview_moveto((bottom - visible_height) / self.body.winfo_reqheight())


class LotDialog(tk.Toplevel):
    def __init__(self, parent, lots, pack_count, on_confirm):
        super().__init__(parent)
        self.title("Numery partii")
        self.transient(parent)
        self.geometry(f"600x{min(480, parent.maxsize()[1])}")
        self.pack_count = pack_count
        self.on_confirm = on_confirm
        self.rows = []

        footer = ttk.Frame(self, padding=15)
        footer.pack(side="bottom", fill="x")
        self.total_var = tk.StringVar()
        ttk.Label(footer, textvariable=self.total_var).pack(anchor="w", pady=(0, 10))
        buttons = ttk.Frame(footer)
        buttons.pack(fill="x")
        ttk.Button(buttons, text="Anuluj", command=self.destroy).pack(side="left")
        ttk.Button(buttons, text="Zatwierdź i drukuj", command=lambda: self.confirm(True)).pack(side="right")
        ttk.Button(buttons, text="Zapisz", command=lambda: self.confirm(False)).pack(side="right", padx=10)

        self.form = ScrollableForm(self)
        self.form.pack(fill="both", expand=True)
        body = self.form.body
        ttk.Label(body, text="Przypisz wszystkie opakowania do partii.").pack(anchor="w", pady=(0, 15))
        header = ttk.Frame(body)
        header.pack(fill="x", pady=(0, 5))
        ttk.Label(header, text="Nr partii").pack(side="left", expand=True, fill="x")
        ttk.Label(header, text="Liczba opakowań", width=23).pack(side="right")
        self.table = ttk.Frame(body)
        self.table.pack(fill="x")
        ttk.Button(body, text="Dodaj wiersz", command=self.add_row).pack(anchor="w", pady=(10, 0))
        self.form.bind_contents()
        for lot, count in lots or [('', '')]:
            self.add_row(lot, count)
        self.bind('<Escape>', lambda event: self.destroy())
        self.grab_set()

    def add_row(self, lot='', count=''):
        frame = ttk.Frame(self.table)
        frame.pack(fill="x", pady=3)
        lot_var = tk.StringVar(value=lot)
        count_var = tk.StringVar(value=str(count))
        row = (frame, lot_var, count_var)
        ttk.Entry(frame, textvariable=lot_var, font=('Segoe UI', 11)).pack(side="left", fill="x", expand=True)
        ttk.Entry(frame, textvariable=count_var, width=12, font=('Segoe UI', 11)).pack(side="left", padx=8)
        ttk.Button(frame, text="Usuń", width=6, command=lambda: self.remove_row(row)).pack(side="right")
        self.rows.append(row)
        count_var.trace_add('write', lambda *_: self.update_total())
        self.update_total()
        self.form.bind_contents(frame)

    def remove_row(self, row):
        self.rows.remove(row)
        row[0].destroy()
        self.update_total()

    def update_total(self):
        try:
            total = sum(int(count.get()) for _, _, count in self.rows)
        except ValueError:
            total = '—'
        self.total_var.set(f'Suma opakowań: {total} / {self.pack_count}')

    def confirm(self, do_print):
        try:
            lots = validate_lot_allocations(
                [(lot.get(), count.get()) for _, lot, count in self.rows], self.pack_count,
            )
        except ValueError as error:
            messagebox.showerror('Błąd', str(error), parent=self)
            return
        self.destroy()
        self.on_confirm(lots, do_print)


class App:
    def __init__(self, root, pack_mode=False):
        self.root = root
        self.pack_mode = pack_mode
        if self.pack_mode:
            self.root.title("Naklejki QR - Generator opakowań")
        else:
            self.root.title("Naklejki QR - Generator")
        height = min(835 if self.pack_mode else 760, self.root.maxsize()[1])
        self.root.geometry(f"450x{height}")
        
        # Setup clean style
        style = ttk.Style()
        try:
            style.theme_use('vista') # Try Windows native theme
        except:
            style.theme_use('clam')
            
        style.configure('TLabel', font=('Segoe UI', 11))
        style.configure('Header.TLabel', font=('Segoe UI', 14, 'bold'))
        style.configure('TButton', font=('Segoe UI', 11))
        
        self.state = load_state()
        self.form = ScrollableForm(root)
        self.form.pack(fill="both", expand=True)
        root = self.form.body
        
        header_text = "Kreator Naklejek - Opakowania" if self.pack_mode else "Kreator Naklejek"
        ttk.Label(root, text=header_text, style='Header.TLabel').pack(anchor="center", pady=(0, 20))
        
        ttk.Label(root, text="Nazwa Produktu:").pack(anchor="w", pady=(0, 5))
        self.name_var = tk.StringVar(value=self.state.get("last_product_name", ""))
        self.name_entry = ttk.Entry(root, textvariable=self.name_var, font=('Segoe UI', 12))
        self.name_entry.pack(fill="x", pady=(0, 15))
        
        ttk.Label(root, text="Ilość netto:").pack(anchor="w", pady=(0, 5))
        weight_frame = ttk.Frame(root)
        weight_frame.pack(fill="x", pady=(0, 15))
        
        def validate_weight(P):
            if P == "":
                return True
            if all(c.isdigit() or c in '.,' for c in P):
                if P.count('.') + P.count(',') <= 1:
                    return True
            return False
            
        vcmd_weight = (root.register(validate_weight), '%P')
        self.weight_var = tk.StringVar()
        self.weight_entry = ttk.Entry(weight_frame, textvariable=self.weight_var, font=('Segoe UI', 12), width=15, validate='key', validatecommand=vcmd_weight)
        self.weight_entry.pack(side="left", fill="x", expand=True, padx=(0, 15))
        
        self.unit_var = tk.StringVar(value="kg")
        ttk.Radiobutton(weight_frame, text="kg", variable=self.unit_var, value="kg").pack(side="left", padx=(0, 10))
        ttk.Radiobutton(weight_frame, text="szt.", variable=self.unit_var, value="szt.").pack(side="left")
        
        ttk.Label(root, text="Inicjały operatora:").pack(anchor="w", pady=(0, 5))
        def validate_operator(P):
            if len(P) <= 3:
                return True
            return False
            
        vcmd_operator = (root.register(validate_operator), '%P')
        self.operator_var = tk.StringVar(value=self.state.get("last_operator", ""))
        self.operator_entry = ttk.Entry(root, textvariable=self.operator_var, font=('Segoe UI', 12), validate='key', validatecommand=vcmd_operator)
        self.operator_entry.pack(fill="x", pady=(0, 15))
        
        date_label = (
            "Własna data (opcjonalnie, format YYYY-MM-DD HH:MM):"
            if self.pack_mode
            else "Data i czas (wymagane, format YYYY-MM-DD HH:MM):"
        )
        ttk.Label(root, text=date_label).pack(anchor="w", pady=(0, 5))
        self.date_var = tk.StringVar()
        self.date_entry = ttk.Entry(root, textvariable=self.date_var, font=('Segoe UI', 12))
        self.date_entry.pack(fill="x", pady=(0, 15))

        if self.pack_mode:
            ttk.Label(root, text="Liczba opakowań:").pack(anchor="w", pady=(0, 5))
            self.pack_count_var = tk.StringVar(value=str(self.state.get("last_pack_count", 1)))
            self.pack_count_spinbox = ttk.Spinbox(
                root,
                from_=1,
                to=1000,
                textvariable=self.pack_count_var,
                font=('Segoe UI', 12),
            )
            self.pack_count_spinbox.pack(fill="x", pady=(0, 15))

            self.lots = []
            self.add_lots_var = tk.BooleanVar(value=False)
            lot_controls = ttk.Frame(root)
            lot_controls.pack(fill="x", pady=(0, 15))
            ttk.Checkbutton(
                lot_controls,
                text="Dodaj numery partii",
                variable=self.add_lots_var,
                command=lambda: self.lots_btn.configure(state="normal" if self.add_lots_var.get() else "disabled"),
            ).pack(side="left")
            self.lots_btn = ttk.Button(lot_controls, text="Numery partii…", command=self.edit_lots, state="disabled")
            self.lots_btn.pack(side="right")
        
        ttk.Label(root, text="Liczba kopii:").pack(anchor="w", pady=(0, 5))
        self.copies_var = tk.StringVar(value="1")
        self.copies_spinbox = ttk.Spinbox(root, from_=1, to=100, textvariable=self.copies_var, font=('Segoe UI', 12))
        self.copies_spinbox.pack(fill="x", pady=(0, 15))

        ttk.Label(root, text="Rozmiar etykiety:").pack(anchor="w", pady=(0, 5))
        self.label_size_var = tk.StringVar(value=self.state.get("last_label_size", LABEL_SIZES[0]))
        ttk.Combobox(
            root,
            textvariable=self.label_size_var,
            values=LABEL_SIZES,
            state="readonly",
            font=('Segoe UI', 11),
        ).pack(fill="x", pady=(0, 15))
        
        ttk.Label(root, text="Drukarka:").pack(anchor="w", pady=(0, 5))
        self.printer_var = tk.StringVar()
        self.printer_combo = ttk.Combobox(root, textvariable=self.printer_var, font=('Segoe UI', 11), state="readonly")
        
        printers = get_printers()
        self.printer_combo['values'] = printers
        
        saved_printer = self.state.get("last_printer", "Zebra ZD421")
        if saved_printer in printers:
            self.printer_var.set(saved_printer)
        elif printers:
            self.printer_var.set(printers[0])
            
        self.printer_combo.pack(fill="x", pady=(0, 25))
        
        # Focus on weight if name is already pre-filled
        if self.name_var.get():
            self.weight_entry.focus()
        else:
            self.name_entry.focus()
        
        print_button_text = "Drukuj naklejki dla opakowań" if self.pack_mode else "Drukuj naklejkę"
        self.print_btn = ttk.Button(root, text=print_button_text, command=self.on_print)
        self.print_btn.pack(fill="x", pady=5, ipady=8)
        
        self.pdf_btn = ttk.Button(root, text="Wygeneruj PDF bez drukowania", command=self.on_generate)
        self.pdf_btn.pack(fill="x", pady=5, ipady=8)
        
        self.status_var = tk.StringVar(value="Gotowy.")
        self.status_label = ttk.Label(root, textvariable=self.status_var, font=('Segoe UI', 9), foreground="gray")
        self.status_label.pack(side="bottom", anchor="w", pady=(10, 0))
        self.form.bind_contents()

        # Bind Enter key to Print
        self.root.bind('<Return>', lambda e: self.on_print())

    def edit_lots(self):
        try:
            pack_count = int(self.pack_count_var.get().strip())
            if pack_count < 1:
                raise ValueError()
        except ValueError:
            messagebox.showerror("Błąd", "Liczba opakowań musi być dodatnią liczbą całkowitą!")
            return
        LotDialog(self.root, self.lots, pack_count, self.apply_lots)

    def apply_lots(self, lots, do_print):
        self.lots = lots
        if do_print:
            self._process(do_print=True, print_confirmed=True)

    def _process(self, do_print, print_confirmed=False):
        prod_name = self.name_var.get().strip()
        weight = self.weight_var.get().strip()
        unit = self.unit_var.get()
        operator = self.operator_var.get().strip()
        date_override = self.date_var.get().strip()

        if not self.pack_mode and not date_override:
            messagebox.showerror("Błąd", "Data i czas są wymagane!")
            return
        if not weight:
            messagebox.showerror("Błąd", "Ilość netto jest wymagana!")
            return
        
        try:
            prod_name, weight, unit, operator = validate_label_input(
                prod_name,
                weight,
                unit,
                operator,
            )
            label_date = normalize_label_date(date_override)
        except ValueError as error:
            messagebox.showerror("Błąd", polish_error_message(error))
            return
            
        try:
            copies = int(self.copies_var.get().strip())
            if copies < 1:
                raise ValueError()
        except ValueError:
            messagebox.showerror("Błąd", "Liczba kopii musi być dodatnią liczbą całkowitą!")
            return

        pack_count = 1
        if self.pack_mode:
            try:
                pack_count = int(self.pack_count_var.get().strip())
                if pack_count < 1:
                    raise ValueError()
            except ValueError:
                messagebox.showerror("Błąd", "Liczba opakowań musi być dodatnią liczbą całkowitą!")
                return

        if not self.pack_mode and factory_date_needs_warning(label_date):
            confirmed = messagebox.askyesno(
                "Ostrzeżenie",
                f"Wprowadzona data ({label_date[:10]}) nie jest dzisiejsza ani wczorajsza.\n\n"
                "Czy chcesz kontynuować?",
            )
            if not confirmed:
                return

        label_names = numbered_pack_names(prod_name, pack_count) if self.pack_mode else [prod_name]
        pack_lots = [None] * pack_count
        if self.pack_mode and self.add_lots_var.get():
            try:
                lots = validate_lot_allocations(self.lots, pack_count)
            except ValueError as error:
                messagebox.showerror("Błąd", str(error))
                return
            pack_lots = [lot for lot, count in lots for _ in range(count)]
        total_labels = len(label_names) * copies

        if do_print and self.pack_mode and not print_confirmed:
            confirmed = messagebox.askyesno(
                "Potwierdzenie wydruku",
                f"Liczba opakowań: {pack_count}\n"
                f"Kopie na opakowanie: {copies}\n"
                f"Łącznie naklejek: {total_labels}\n\n"
                "Czy rozpocząć drukowanie?",
            )
            if not confirmed:
                return
            
        self.status_var.set("Generowanie PDF...")
        self.root.update_idletasks()
        
        printed_count = 0
        try:
            pdf_paths = []
            for pack_number, label_name in enumerate(label_names, 1):
                if self.pack_mode:
                    self.status_var.set(f"Generowanie opakowania {pack_number}/{pack_count}...")
                    output_stem = f"output_pack_{pack_number:03d}"
                else:
                    output_stem = "output"
                self.root.update_idletasks()

                pdf_path = generate_pdf(
                    label_name,
                    weight,
                    operator=operator,
                    date_override=label_date,
                    unit=unit,
                    output_stem=output_stem,
                    label_size=self.label_size_var.get(),
                    **({"lot_number": pack_lots[pack_number - 1], "template_file": "sticker_template_lots.tex"}
                       if pack_lots[pack_number - 1] is not None else {}),
                )
                pdf_paths.append(pdf_path)

                if do_print:
                    for _ in range(copies):
                        self.status_var.set(f"Wysyłanie do drukarki ({printed_count + 1}/{total_labels})...")
                        self.root.update_idletasks()
                        print_pdf(
                            pdf_path,
                            printer_name=self.printer_var.get(),
                            match_pdf_size=True,
                        )
                        printed_count += 1
            
            # Save state
            self.state["last_product_name"] = prod_name
            self.state["last_operator"] = operator
            self.state["last_printer"] = self.printer_var.get()
            self.state["last_label_size"] = self.label_size_var.get()
            if self.pack_mode:
                self.state["last_pack_count"] = pack_count
            save_state(self.state)
            
            if do_print:
                if self.pack_mode:
                    self.status_var.set(f"Wydrukowano {total_labels} naklejek ({pack_count} opakowań x {copies} kopii).")
                else:
                    self.status_var.set(f"Wydrukowano: {prod_name} ({weight} {unit}) x{copies}")
            else:
                if self.pack_mode and len(pdf_paths) > 1:
                    output_directory = os.path.dirname(pdf_paths[0])
                    self.status_var.set(f"Wygenerowano {len(pdf_paths)} plików PDF.")
                    messagebox.showinfo(
                        "Gotowe",
                        f"Wygenerowano {len(pdf_paths)} plików PDF w folderze:\n{output_directory}",
                    )
                else:
                    self.status_var.set("PDF wygenerowany pomyślnie.")
                    pdf_path = pdf_paths[0]
                    if hasattr(os, 'startfile'):
                        os.startfile(pdf_path) # Auto-open PDF if on Windows
                    else:
                        import sys
                        if sys.platform == "darwin":
                            subprocess.call(["open", pdf_path])
                        else:
                            # Try xdg-open for Linux, or wslview for WSL
                            try:
                                subprocess.call(["wslview", pdf_path])
                            except FileNotFoundError:
                                subprocess.call(["xdg-open", pdf_path])

            # Clear weight for the next print
            self.weight_var.set("")
            self.weight_entry.focus()
                
        except Exception as e:
            if do_print and printed_count:
                self.status_var.set(f"Błąd po wydrukowaniu {printed_count}/{total_labels} naklejek.")
            else:
                self.status_var.set("Wystąpił błąd.")
            messagebox.showerror("Błąd", polish_error_message(e))
            
    def on_print(self):
        self._process(do_print=True)
        
    def on_generate(self):
        self._process(do_print=False)

if __name__ == "__main__":
    root = tk.Tk()
    app = App(root)
    root.mainloop()
