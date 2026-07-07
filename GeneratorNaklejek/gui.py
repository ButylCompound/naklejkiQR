import tkinter as tk
from tkinter import ttk, messagebox
import qrcode
import json
import os
import subprocess
from datetime import datetime
import jinja2

STATE_FILE = "state.json"
TEMPLATE_FILE = "sticker_template.tex"

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

def get_printers():
    printers = ["Zebra ZD421"]
    if os.name == 'nt':
        try:
            output = subprocess.check_output(
                ['powershell', '-Command', 'Get-Printer | Select-Object -ExpandProperty Name'],
                text=True,
                creationflags=0x08000000
            )
            found = [p.strip() for p in output.split('\n') if p.strip()]
            if found:
                printers = found
        except Exception:
            pass
    return printers

def generate_pdf(product_name, weight, date_override=None):
    if date_override:
        now = date_override
    else:
        now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    qr_data = f"{product_name} | {weight}kg | {now}"
    
    qr = qrcode.QRCode(version=1, box_size=10, border=0)
    qr.add_data(qr_data)
    qr.make(fit=True)
    img = qr.make_image(fill_color="black", back_color="white")
    qr_path = "current_qr.png"
    img.save(qr_path)
    
    env = jinja2.Environment(
        block_start_string='<BLOCK>',
        block_end_string='</BLOCK>',
        variable_start_string='<<',
        variable_end_string='>>',
        comment_start_string='<#',
        comment_end_string='#>',
        loader=jinja2.FileSystemLoader(os.path.abspath('.'))
    )
    
    try:
        template = env.get_template(TEMPLATE_FILE)
    except jinja2.exceptions.TemplateNotFound:
        raise Exception(f"Nie znaleziono pliku szablonu {TEMPLATE_FILE}.")
        
    tex_content = template.render(
        product_name=product_name,
        weight=weight,
        datetime=now,
        qr_path=qr_path.replace("\\", "/")
    )
    
    out_tex = "output.tex"
    with open(out_tex, "w", encoding="utf-8") as f:
        f.write(tex_content)
        
    # Try calling pdflatex.exe first (Windows), fallback to pdflatex (Linux/WSL)
    try:
        result = subprocess.run(["pdflatex.exe", "-interaction=nonstopmode", out_tex], capture_output=True, text=True)
    except FileNotFoundError:
        result = subprocess.run(["pdflatex", "-interaction=nonstopmode", out_tex], capture_output=True, text=True)
        
    if result.returncode != 0:
        raise Exception(f"Błąd kompilacji LaTeX.\n{result.stdout[-500:]}")
            
    return os.path.abspath("output.pdf")

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
            subprocess.run(print_cmd, check=True)
        except Exception as e:
            raise Exception(f"Błąd drukowania (Windows): {e}")
    elif is_wsl:
        try:
            win_path_result = subprocess.run(["wslpath", "-w", pdf_path], capture_output=True, text=True, check=True)
            win_pdf_path = win_path_result.stdout.strip()
            print_cmd = ["SumatraPDF.exe", "-print-to", printer_name, "-silent", win_pdf_path]
            subprocess.run(print_cmd, check=True)
        except Exception as e:
            raise Exception(f"Błąd drukowania z WSL: {e}")
    else:
        raise Exception("Automatyczne drukowanie jest skonfigurowane tylko dla Windows/WSL.")

class App:
    def __init__(self, root):
        self.root = root
        self.root.title("Naklejki QR - Generator")
        self.root.geometry("450x590")
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
        
        ttk.Label(root, text="Kreator Naklejek", style='Header.TLabel').pack(anchor="center", pady=(0, 20))
        
        ttk.Label(root, text="Nazwa Produktu:").pack(anchor="w", pady=(0, 5))
        self.name_var = tk.StringVar(value=self.state.get("last_product_name", ""))
        self.name_entry = ttk.Entry(root, textvariable=self.name_var, font=('Segoe UI', 12))
        self.name_entry.pack(fill="x", pady=(0, 15))
        
        ttk.Label(root, text="Ilość netto (kg):").pack(anchor="w", pady=(0, 5))
        self.weight_var = tk.StringVar()
        self.weight_entry = ttk.Entry(root, textvariable=self.weight_var, font=('Segoe UI', 12))
        self.weight_entry.pack(fill="x", pady=(0, 15))
        
        ttk.Label(root, text="Własna data (opcjonalnie):").pack(anchor="w", pady=(0, 5))
        self.date_var = tk.StringVar()
        self.date_entry = ttk.Entry(root, textvariable=self.date_var, font=('Segoe UI', 12))
        self.date_entry.pack(fill="x", pady=(0, 15))
        
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
        
        self.print_btn = ttk.Button(root, text="Drukuj naklejkę", command=self.on_print)
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
        date_override = self.date_var.get().strip()
        
        if not prod_name:
            messagebox.showerror("Błąd", "Proszę podać nazwę produktu!")
            return
        if not weight:
            messagebox.showerror("Błąd", "Proszę podać wagę!")
            return
            
        try:
            copies = int(self.copies_var.get().strip())
            if copies < 1:
                copies = 1
        except ValueError:
            copies = 1
            
        self.status_var.set("Generowanie PDF...")
        self.root.update_idletasks()
        
        try:
            pdf_path = generate_pdf(prod_name, weight, date_override=date_override if date_override else None)
            
            # Save state
            self.state["last_product_name"] = prod_name
            self.state["last_printer"] = self.printer_var.get()
            save_state(self.state)
            
            if do_print:
                self.status_var.set(f"Wysyłanie do drukarki ({copies} kopii)...")
                self.root.update_idletasks()
                for _ in range(copies):
                    print_pdf(pdf_path, printer_name=self.printer_var.get())
                self.status_var.set(f"Wydrukowano: {prod_name} ({weight}kg) x{copies}")
            else:
                self.status_var.set("PDF wygenerowany pomyślnie.")
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
            self.status_var.set("Wystąpił błąd.")
            messagebox.showerror("Błąd", str(e))
            
    def on_print(self):
        self._process(do_print=True)
        
    def on_generate(self):
        self._process(do_print=False)

if __name__ == "__main__":
    root = tk.Tk()
    app = App(root)
    root.mainloop()
