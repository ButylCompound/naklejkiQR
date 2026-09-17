import tkinter as tk
from tkinter import ttk, messagebox
from datetime import datetime, timedelta
import json
import os
import subprocess

from label_generator import generate_pdf
from label_utils import normalize_label_date, numbered_pack_names, validate_label_input

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

def print_pdf(pdf_path, printer_name="Zebra ZD421"):
    is_wsl = False
    try:
        if "microsoft" in os.uname().release.lower():
            is_wsl = True
    except AttributeError:
        pass
        
    is_windows = os.name == 'nt'
    
    if is_windows:
        import glob
        sumatra_paths = glob.glob("SumatraPDF*.exe")
        sumatra_path = sumatra_paths[0] if sumatra_paths else "SumatraPDF.exe"
        
        print_cmd = [sumatra_path, "-print-settings", "shrink,landscape", "-print-to", printer_name, "-silent", pdf_path]
        try:
            subprocess.run(print_cmd, check=True, creationflags=CREATE_NO_WINDOW)
        except Exception as e:
            raise Exception(f"Błąd drukowania (Windows): {e}")
    elif is_wsl:
        try:
            win_path_result = subprocess.run(["wslpath", "-w", pdf_path], capture_output=True, text=True, check=True, creationflags=CREATE_NO_WINDOW)
            win_pdf_path = win_path_result.stdout.strip()
            print_cmd = ["SumatraPDF.exe", "-print-to", printer_name, "-silent", win_pdf_path]
            subprocess.run(print_cmd, check=True, creationflags=CREATE_NO_WINDOW)
        except Exception as e:
            raise Exception(f"Błąd drukowania z WSL: {e}")
    else:
        raise Exception("Automatyczne drukowanie jest skonfigurowane tylko dla Windows/WSL.")

class App:
    def __init__(self, root, pack_mode=False):
        self.root = root
        self.pack_mode = pack_mode
        if self.pack_mode:
            self.root.title("Naklejki QR - Generator opakowań")
            self.root.geometry("450x735")
        else:
            self.root.title("Naklejki QR - Generator")
            self.root.geometry("450x660")
        self.root.configure(padx=25, pady=25)
        
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
        
        ttk.Label(root, text="Liczba kopii:").pack(anchor="w", pady=(0, 5))
        self.copies_var = tk.StringVar(value="1")
        self.copies_spinbox = ttk.Spinbox(root, from_=1, to=100, textvariable=self.copies_var, font=('Segoe UI', 12))
        self.copies_spinbox.pack(fill="x", pady=(0, 15))
        
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

        # Bind Enter key to Print
        self.root.bind('<Return>', lambda e: self.on_print())

    def _process(self, do_print):
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
        total_labels = len(label_names) * copies

        if do_print and self.pack_mode:
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
                )
                pdf_paths.append(pdf_path)

                if do_print:
                    for _ in range(copies):
                        self.status_var.set(f"Wysyłanie do drukarki ({printed_count + 1}/{total_labels})...")
                        self.root.update_idletasks()
                        print_pdf(pdf_path, printer_name=self.printer_var.get())
                        printed_count += 1
            
            # Save state
            self.state["last_product_name"] = prod_name
            self.state["last_operator"] = operator
            self.state["last_printer"] = self.printer_var.get()
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
