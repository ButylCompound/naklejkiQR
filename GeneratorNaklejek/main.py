import typer
import qrcode
import json
import os
import subprocess
from datetime import datetime
import sys
import jinja2

CREATE_NO_WINDOW = 0x08000000 if os.name == 'nt' else 0

# Force UTF-8 output on Windows to prevent console crash with Polish diacritics
if sys.platform == 'win32':
    try:
        sys.stdout.reconfigure(encoding='utf-8')
        sys.stderr.reconfigure(encoding='utf-8')
    except AttributeError:
        pass

app = typer.Typer()
STATE_FILE = "state.json"
TEMPLATE_FILE = "sticker_template.tex"

def safe_print(text, err=False):
    try:
        typer.echo(text, err=err)
    except UnicodeEncodeError:
        typer.echo(str(text).encode('ascii', 'replace').decode('ascii'), err=err)

def load_state():
    if os.path.exists(STATE_FILE):
        with open(STATE_FILE, "r", encoding="utf-8") as f:
            return json.load(f)
    return {"last_product_name": ""}

def save_state(state):
    with open(STATE_FILE, "w", encoding="utf-8") as f:
        json.dump(state, f, ensure_ascii=False)

@app.command()
def create(
    weight: str = typer.Option(..., "--weight", "-w", help="Weight of the product/pallet (e.g., 500)"),
    product_name: str = typer.Option(None, "--name", "-n", help="Product name (defaults to last used)"),
    copies: int = typer.Option(1, "--copies", "-c", help="Number of copies to print"),
    print_job: bool = typer.Option(True, "--print/--no-print", help="Whether to send to printer automatically"),
    printer_name: str = typer.Option("Zebra ZD421", "--printer", "-p", help="Windows printer name for SumatraPDF"),
    date_override: str = typer.Option(None, "--date", "-d", help="Manual date override (e.g., '2023-10-25 12:00:00')"),
    operator: str = typer.Option(None, "--operator", "-o", help="Operator initials (defaults to last used)"),
    template_file: str = typer.Option("sticker_template.tex", "--template", "-t", help="Template file to use"),
    unit: str = typer.Option("kg", "--unit", "-u", help="Unit of measure ('kg' or 'szt.')")
):
    """
    Generate and print a QR code sticker for a product pallet.
    """
    state = load_state()
    
    if not product_name:
        product_name = state.get("last_product_name")
        if not product_name:
            safe_print("Error: No product name provided and no previous name saved.", err=True)
            raise typer.Exit(code=1)
    
    if operator is None:
        operator = state.get("last_operator", "")
    
    # Save the used product name
    state["last_product_name"] = product_name
    state["last_operator"] = operator
    save_state(state)
    
    if date_override:
        try:
            dt = datetime.strptime(date_override, "%Y-%m-%d %H:%M")
            now = dt.strftime("%Y-%m-%d %H:%M")
        except ValueError:
            try:
                dt = datetime.strptime(date_override, "%Y-%m-%d %H:%M:%S")
                now = dt.strftime("%Y-%m-%d %H:%M")
            except ValueError:
                safe_print("Error: Invalid date format! Must be YYYY-MM-DD HH:MM", err=True)
                raise typer.Exit(code=1)
    else:
        now = datetime.now().strftime("%Y-%m-%d %H:%M")
    
    # Generate QR Code
    qr_data = f"{product_name} | {weight} {unit} | {now}"
    if operator:
        qr_data += f" | {operator}"
    safe_print(f"Generating QR for: {qr_data}")
    
    qr = qrcode.QRCode(version=1, box_size=10, border=0)
    qr.add_data(qr_data)
    qr.make(fit=True)
    img = qr.make_image(fill_color="black", back_color="white")
    qr_path = "current_qr.png"
    img.save(qr_path)
    
    # Setup Jinja2 for LaTeX
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
        template = env.get_template(template_file)
    except jinja2.exceptions.TemplateNotFound:
        safe_print(f"Error: Template file {template_file} not found.", err=True)
        raise typer.Exit(code=1)
        
    # Render template (replace backslashes for LaTeX path compatibility)
    tex_content = template.render(
        product_name=product_name,
        weight=weight,
        unit=unit,
        datetime=now,
        operator=operator,
        qr_path=qr_path.replace("\\", "/")
    )
    
    out_tex = "output.tex"
    with open(out_tex, "w", encoding="utf-8") as f:
        f.write(tex_content)
        
    safe_print("Compiling LaTeX to PDF...")
    # Compile PDF using pdflatex
    result = subprocess.run(["pdflatex.exe", "-interaction=nonstopmode", out_tex], capture_output=True, text=True, creationflags=CREATE_NO_WINDOW)
    
    if result.returncode != 0:
        safe_print("Error compiling LaTeX:", err=True)
        safe_print(result.stdout, err=True)
        safe_print("Ensure that 'pdflatex.exe' is installed and in your PATH.", err=True)
        raise typer.Exit(code=1)
        
    safe_print("PDF successfully generated: output.pdf")
    
    if print_job:
        safe_print(f"Sending {copies} copies to printer '{printer_name}'...")
        pdf_path = os.path.abspath("output.pdf")
        
        # Check OS environment
        is_wsl = False
        try:
            if "microsoft" in os.uname().release.lower():
                is_wsl = True
        except AttributeError:
            pass
            
        is_windows = os.name == 'nt'
        
        for _ in range(copies):
            if is_windows:
                _print_windows(pdf_path, printer_name)
            elif is_wsl:
                _print_wsl(pdf_path, printer_name)
            else:
                safe_print("Auto-printing is only configured for Windows/WSL with SumatraPDF. Please print manually.")
                break

def _print_windows(pdf_path, printer_name):
    import glob
    sumatra_paths = glob.glob("SumatraPDF*.exe")
    sumatra_path = sumatra_paths[0] if sumatra_paths else "SumatraPDF.exe"
    
    if not os.path.exists(sumatra_path):
        # Fallback to checking if it's in PATH, though subprocess handles it
        pass
        
    print_cmd = [sumatra_path, "-print-settings", "shrink,landscape", "-print-to", printer_name, "-silent", pdf_path]
    try:
        subprocess.run(print_cmd, check=True, creationflags=CREATE_NO_WINDOW)
        safe_print("Print job sent successfully!")
    except FileNotFoundError:
        safe_print("Error: SumatraPDF.exe not found. Please place it in this directory or add it to PATH.", err=True)
    except Exception as e:
        safe_print(f"Failed to print: {e}", err=True)

def _print_wsl(pdf_path, printer_name):
    safe_print("WSL Environment Detected.")
    try:
        # Convert WSL path to Windows path for SumatraPDF
        win_path_result = subprocess.run(["wslpath", "-w", pdf_path], capture_output=True, text=True, check=True, creationflags=CREATE_NO_WINDOW)
        win_pdf_path = win_path_result.stdout.strip()
        
        print_cmd = ["SumatraPDF.exe", "-print-to", printer_name, "-silent", win_pdf_path]
        subprocess.run(print_cmd, check=True, creationflags=CREATE_NO_WINDOW)
        safe_print("Print job sent successfully from WSL!")
    except FileNotFoundError:
        safe_print("Error: 'SumatraPDF.exe' not found. It must be accessible from WSL (e.g. in your Windows PATH).", err=True)
    except Exception as e:
        safe_print(f"Failed to print from WSL: {e}", err=True)

if __name__ == "__main__":
    app()
