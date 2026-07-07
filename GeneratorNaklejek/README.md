# NaklejkiQR

This is a CLI application to generate and print QR code stickers for pallets. It creates a 10x6 cm sticker (PDF) using LaTeX and sends it to a Zebra ZD421 printer via SumatraPDF on Windows.

## Requirements
*   **Python 3.8+**
*   **python3-tk**: Required for the GUI on Linux/WSL (`sudo apt install python3-tk`).
*   **pdflatex**: Must be installed and available in your system's PATH. (On Windows, install [MiKTeX](https://miktex.org/); on WSL/Linux, run `sudo apt install texlive-latex-base`).
*   **SumatraPDF**: Used for printing silently on Windows. Download [SumatraPDF.exe](https://www.sumatrapdfreader.org/download-free-pdf-viewer) and place it in this folder, or add it to your Windows PATH.

## Installation
1.  (Optional) Create a virtual environment: `python -m venv .venv` and activate it.
2.  Install dependencies: `pip install -r requirements.txt`

## Usage

You can use either the Graphical User Interface (GUI) or the Command Line Interface (CLI).

### Graphical Interface (GUI)
Run the application by executing:

```bash
python gui.py
```

*(On Windows, you can also just double-click `run.bat` which will automatically activate the `.venv` virtual environment and start the GUI).*

### Command Line Interface (CLI)
Run the CLI application using Python:

```bash
# Print a sticker for a new product
python main.py 500 -n "Product XYZ"

# Print another sticker for the same product, just provide the new weight
python main.py 520

# Run without printing (just generates the PDF for testing)
python main.py 500 --no-print
```

### Options:
*   `weight`: (Required) The weight of the pallet in kg.
*   `--name`, `-n`: The product name. If omitted, the last used product name is pulled from `state.json`.
*   `--printer`, `-p`: The exact name of your Zebra printer as registered in Windows (defaults to "Zebra ZD421").
*   `--print` / `--no-print`: Whether to automatically send the PDF to the printer.

## Adding a Logo
To add a company logo to the sticker:
1. Place a file named `logo.png` in this directory.
2. Open `sticker_template.tex` and uncomment the line `% \includegraphics[height=1.2cm]{logo.png} \\[0.2cm]` by removing the `%`.
