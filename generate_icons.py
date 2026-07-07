#!/usr/bin/env python3
"""
Generuje ikony launchera aplikacji Android z pliku źródłowego.

Użycie:
    python3 generate_icons.py                    # źródło: AndroidApp/art/icon.png
    python3 generate_icons.py sciezka/logo.png   # inne źródło (zostanie skopiowane do art/)

Skrypt tworzy ic_launcher_foreground.png dla wszystkich gęstości ekranu
(mdpi..xxxhdpi), wpasowując logo w "strefę bezpieczną" ikony adaptacyjnej,
żeby maski launchera (koło/squircle) niczego nie ucinały.

Przy pierwszym uruchomieniu automatycznie tworzy lokalny venv z biblioteką
Pillow (.venv-icons/), więc nie trzeba niczego instalować ręcznie.
"""
import os
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
SOURCE = os.path.join(ROOT, "AndroidApp", "art", "icon.png")
RES_DIR = os.path.join(ROOT, "AndroidApp", "app", "src", "main", "res")

# Rozmiar płótna warstwy foreground (108dp) w px dla każdej gęstości
DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

# Jaką część płótna zajmuje logo. Strefa bezpieczna ikony adaptacyjnej to
# ~61% (66/108dp) — 0.60 daje mały margines. Zwiększ = większe logo.
SAFE = 0.60

# Kolor tła dopinany pod logo (musi zgadzać się z ic_launcher_background w colors.xml)
BACKGROUND_RGBA = (255, 255, 255, 255)


def ensure_pillow():
    """Uruchamia ponownie skrypt z lokalnego venv, instalując Pillow jeśli trzeba."""
    try:
        import PIL  # noqa: F401
        return
    except ImportError:
        pass

    venv_dir = os.path.join(ROOT, ".venv-icons")
    python = os.path.join(venv_dir, "Scripts" if os.name == "nt" else "bin",
                          "python.exe" if os.name == "nt" else "python")
    if not os.path.exists(python):
        print("Pierwsze uruchomienie: tworzenie środowiska z Pillow (.venv-icons/)...", flush=True)
        subprocess.run([sys.executable, "-m", "venv", venv_dir], check=True)
        subprocess.run([python, "-m", "pip", "install", "--quiet", "pillow"], check=True)
    sys.stdout.flush()
    os.execv(python, [python, os.path.abspath(__file__)] + sys.argv[1:])


def main():
    ensure_pillow()
    from PIL import Image

    # Opcjonalny argument: nowe źródło — kopiujemy je do art/icon.png
    if len(sys.argv) > 1:
        src_arg = os.path.abspath(sys.argv[1])
        if not os.path.isfile(src_arg):
            sys.exit(f"Błąd: nie znaleziono pliku {src_arg}")
        os.makedirs(os.path.dirname(SOURCE), exist_ok=True)
        if src_arg != os.path.abspath(SOURCE):
            shutil.copyfile(src_arg, SOURCE)
            print(f"Skopiowano źródło do {os.path.relpath(SOURCE, ROOT)}")

    if not os.path.isfile(SOURCE):
        sys.exit(f"Błąd: brak pliku źródłowego {os.path.relpath(SOURCE, ROOT)}\n"
                 f"Umieść tam logo (PNG, najlepiej kwadratowe, 512px+) albo podaj ścieżkę jako argument.")

    src = Image.open(SOURCE).convert("RGBA")
    if src.width != src.height:
        print(f"Uwaga: źródło nie jest kwadratowe ({src.width}x{src.height}) — zostanie wpasowane bez przycinania.")
    if src.width < 432 * SAFE:
        print(f"Uwaga: źródło ma tylko {src.width}px — na ekranach xxxhdpi ikona będzie lekko rozmyta. Zalecane 512px+.")

    for density, canvas_px in DENSITIES.items():
        # wpasuj logo w kwadrat strefy bezpiecznej, zachowując proporcje
        box = round(canvas_px * SAFE)
        scale = min(box / src.width, box / src.height)
        w, h = round(src.width * scale), round(src.height * scale)
        scaled = src.resize((w, h), Image.LANCZOS)

        canvas = Image.new("RGBA", (canvas_px, canvas_px), BACKGROUND_RGBA)
        canvas.paste(scaled, ((canvas_px - w) // 2, (canvas_px - h) // 2), scaled)

        out_dir = os.path.join(RES_DIR, f"mipmap-{density}")
        os.makedirs(out_dir, exist_ok=True)
        out = os.path.join(out_dir, "ic_launcher_foreground.png")
        canvas.save(out)
        print(f"  {os.path.relpath(out, ROOT)}  ({canvas_px}x{canvas_px}px)")

    print("\nGotowe. Przebuduj aplikację (Run ▶). Jeśli launcher pokazuje starą ikonę,")
    print("odinstaluj aplikację z telefonu/emulatora i zainstaluj ponownie.")


if __name__ == "__main__":
    main()
