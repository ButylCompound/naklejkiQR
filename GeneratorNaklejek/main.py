import typer
import qrcode
import json
import os
import subprocess
from datetime import datetime
import jinja2

app = typer.Typer()
STATE_FILE = "state.json"
TEMPLATE_FILE = "sticker_template.tex"

def load_state():
    if os.path.exists(STATE_FILE):
        with open(STATE_FILE, "r") as f:
            return json.load(f)
    return {"last_product_name": ""}

def save_state(state):
    with open(STATE_FILE, "w") as f:
        json.dump(state, f)

@app.command()
def create(
    weight: str = typer.Argument(..., help="Weight of the product/pallet (e.g., 500)"),
    product_name: str = typer.Option(None, "--name", "-n", help="Product name (defaults to last used)"),
    print_job: bool = typer.Option(True, "--print/--no-print", help="Whether to send to printer automatically"),
    printer_name: str = typer.Option("Zebra ZD421", "--printer", "-p", help="Windows printer name for SumatraPDF")
):
    """
    Generate and print a QR code sticker for a product pallet.
    """
    state = load_state()
    
    if not product_name:
        product_name = state.get("last_product_name")
        if not product_name:
            typer.echo("Error: No product name provided and no previous name saved.", err=True)
            raise typer.Exit(code=1)
    
    # Save the used product name
    state["last_product_name"] = product_name
    save_state(state)
    
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    
    # Generate QR Code
    qr_data = f"{product_name} | {weight}kg | {now}"
    typer.echo(f"Generating QR for: {qr_data}")
    
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
        template = env.get_template(TEMPLATE_FILE)
    except jinja2.exceptions.TemplateNotFound:
        typer.echo(f"Error: Template file {TEMPLATE_FILE} not found.", err=True)
        raise typer.Exit(code=1)
        
    # Render template (replace backslashes for LaTeX path compatibility)
    tex_content = template.render(
        product_name=product_name,
        weight=weight,
        datetime=now,
        qr_path=qr_path.replace("\\", "/")
    )
    
    out_tex = "output.tex"
    with open(out_tex, "w", encoding="utf-8") as f:
        f.write(tex_content)
        
    typer.echo("Compiling LaTeX to PDF...")
    # Compile PDF using pdflatex
    result = subprocess.run(["pdflatex.exe", "-interaction=nonstopmode", out_tex], capture_output=True, text=True)
    
    if result.returncode != 0:
        typer.echo("Error compiling LaTeX:", err=True)
        typer.echo(result.stdout, err=True)
        typer.echo("Ensure that 'pdflatex.exe' is installed and in your PATH.", err=True)
        raise typer.Exit(code=1)
        
    typer.echo("PDF successfully generated: output.pdf")
    
    if print_job:
        typer.echo(f"Sending to printer '{printer_name}'...")
        pdf_path = os.path.abspath("output.pdf")
        
        # Check OS environment
        is_wsl = False
        try:
            if "microsoft" in os.uname().release.lower():
                is_wsl = True
        except AttributeError:
            pass
            
        is_windows = os.name == 'nt'
        
        if is_windows:
            _print_windows(pdf_path, printer_name)
        elif is_wsl:
            _print_wsl(pdf_path, printer_name)
        else:
            typer.echo("Auto-printing is only configured for Windows/WSL with SumatraPDF. Please print manually.")

def _print_windows(pdf_path, printer_name):
    sumatra_path = "SumatraPDF.exe"
    if not os.path.exists(sumatra_path):
        # Fallback to checking if it's in PATH, though subprocess handles it
        pass
        
    print_cmd = [sumatra_path, "-print-to", printer_name, "-silent", pdf_path]
    try:
        subprocess.run(print_cmd, check=True)
        typer.echo("Print job sent successfully!")
    except FileNotFoundError:
        typer.echo("Error: SumatraPDF.exe not found. Please place it in this directory or add it to PATH.", err=True)
    except Exception as e:
        typer.echo(f"Failed to print: {e}", err=True)

def _print_wsl(pdf_path, printer_name):
    typer.echo("WSL Environment Detected.")
    try:
        # Convert WSL path to Windows path for SumatraPDF
        win_path_result = subprocess.run(["wslpath", "-w", pdf_path], capture_output=True, text=True, check=True)
        win_pdf_path = win_path_result.stdout.strip()
        
        print_cmd = ["SumatraPDF.exe", "-print-to", printer_name, "-silent", win_pdf_path]
        subprocess.run(print_cmd, check=True)
        typer.echo("Print job sent successfully from WSL!")
    except FileNotFoundError:
        typer.echo("Error: 'SumatraPDF.exe' not found. It must be accessible from WSL (e.g. in your Windows PATH).", err=True)
    except Exception as e:
        typer.echo(f"Failed to print from WSL: {e}", err=True)

if __name__ == "__main__":
    app()
