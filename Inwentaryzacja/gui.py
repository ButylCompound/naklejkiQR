import tkinter as tk
from tkinter import ttk, filedialog, messagebox
import threading
import os
from pathlib import Path

# Import the scanner logic
from skaner import process_inventory

class InventoryApp:
    def __init__(self, root):
        self.root = root
        self.root.title("Inventory Scanner - SharePoint Sync")
        self.root.geometry("600x500")
        self.root.configure(padx=20, pady=20)
        
        style = ttk.Style()
        try:
            style.theme_use('vista')
        except:
            style.theme_use('clam')
            
        # Hardcoded to the current SharePoint synchronized location
        default_sp_dir = str(Path(__file__).parent / "Zdjecia_z_magazynu")
        
        ttk.Label(root, text="SharePoint Folder Path (Photos location):").pack(anchor="w")
        
        self.folder_frame = ttk.Frame(root)
        self.folder_frame.pack(fill="x", pady=(5, 15))
        
        self.sp_path_var = tk.StringVar(value=default_sp_dir)
        self.sp_entry = ttk.Entry(self.folder_frame, textvariable=self.sp_path_var)
        self.sp_entry.pack(side="left", fill="x", expand=True, padx=(0, 10))
        
        self.browse_btn = ttk.Button(self.folder_frame, text="Browse", command=self.browse_sp)
        self.browse_btn.pack(side="right")
        
        # Subfolders
        ttk.Label(root, text="Select Inventory Date (Subfolder):").pack(anchor="w")
        self.date_frame = ttk.Frame(root)
        self.date_frame.pack(fill="x", pady=(5, 15))
        
        self.date_combo = ttk.Combobox(self.date_frame, state="readonly")
        self.date_combo.pack(side="left", fill="x", expand=True, padx=(0, 10))
        
        self.refresh_btn = ttk.Button(self.date_frame, text="Refresh Dates", command=self.refresh_dates)
        self.refresh_btn.pack(side="right")
        
        self.scan_btn = ttk.Button(root, text="Run Scanner", command=self.run_scanner)
        self.scan_btn.pack(fill="x", pady=10, ipady=5)
        
        ttk.Label(root, text="Output Log:").pack(anchor="w")
        self.log_text = tk.Text(root, height=12, state="disabled")
        self.log_text.pack(fill="both", expand=True, pady=(5, 0))
        
        # Initial load
        self.refresh_dates()
        
    def log(self, message):
        self.log_text.config(state="normal")
        self.log_text.insert(tk.END, message + "\n")
        self.log_text.see(tk.END)
        self.log_text.config(state="disabled")
        
    def browse_sp(self):
        folder = filedialog.askdirectory(initialdir=self.sp_path_var.get() or Path.home())
        if folder:
            self.sp_path_var.set(folder)
            self.refresh_dates()
            
    def refresh_dates(self):
        sp_path = Path(self.sp_path_var.get())
        if not sp_path.exists() or not sp_path.is_dir():
            self.date_combo['values'] = []
            self.date_combo.set("")
            return
            
        subdirs = [d.name for d in sp_path.iterdir() if d.is_dir()]
        self.date_combo['values'] = sorted(subdirs, reverse=True)
        if subdirs:
            self.date_combo.current(0)
        else:
            self.date_combo.set("")
            
    def run_scanner(self):
        sp_path = self.sp_path_var.get()
        date_folder = self.date_combo.get()
        
        if not sp_path or not date_folder:
            messagebox.showerror("Error", "Please select a valid SharePoint folder and a date subfolder.")
            return
            
        full_path = Path(sp_path) / date_folder
        
        self.scan_btn.config(state="disabled")
        self.log_text.config(state="normal")
        self.log_text.delete(1.0, tk.END)
        self.log_text.config(state="disabled")
        
        def worker():
            try:
                report_path = process_inventory(str(full_path), log_callback=lambda msg: self.root.after(0, self.log, msg))
                if report_path:
                    self.root.after(0, lambda: messagebox.showinfo("Success", f"Scan complete! Report saved to:\n{report_path}"))
            except Exception as e:
                self.root.after(0, self.log, f"Error: {e}")
            finally:
                self.root.after(0, lambda: self.scan_btn.config(state="normal"))
                
        # Run scanning in background thread so GUI doesn't freeze
        threading.Thread(target=worker, daemon=True).start()

if __name__ == "__main__":
    root = tk.Tk()
    app = InventoryApp(root)
    root.mainloop()
