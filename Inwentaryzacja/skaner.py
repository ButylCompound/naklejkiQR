import os
import sys
from pathlib import Path

# Fix for pyzbar DLL loading on Windows (Python 3.8+)
if sys.platform == 'win32' and sys.version_info >= (3, 8):
    for path in sys.path:
        pyzbar_path = os.path.join(path, 'pyzbar')
        if os.path.exists(pyzbar_path):
            try:
                os.add_dll_directory(pyzbar_path)
            except Exception:
                pass

from pyzbar.pyzbar import decode, ZBarSymbol
from PIL import Image, ImageOps, ImageEnhance

def process_inventory(folder_path, log_callback=print):
    log_callback(f"Rozpoczynanie skanowania w folderze: {folder_path}")
    
    # List for individual pallets
    items = []
    
    valid_ext = {".png", ".jpg", ".jpeg"}
    
    path = Path(folder_path)
    if not path.is_dir():
        log_callback(f"Błąd: Podana ścieżka '{folder_path}' nie jest folderem.")
        return None
        
    image_files = [f for f in path.iterdir() if f.suffix.lower() in valid_ext]
    if not image_files:
        log_callback(f"Brak zdjęć w folderze '{folder_path}'.")
        return None
        
    log_callback(f"Znaleziono {len(image_files)} zdjęć. Odczytywanie QR...")
    
    for img_path in image_files:
        try:
            # Read image using PIL
            img = Image.open(str(img_path))
            
            # Attempt 1: Original image, restrict to QRCODE to prevent PDF417 warnings
            decoded_objects = decode(img, symbols=[ZBarSymbol.QRCODE])
            
            # Attempt 2: High contrast grayscale
            if not decoded_objects:
                gray = ImageOps.grayscale(img)
                enhancer = ImageEnhance.Contrast(gray)
                high_contrast = enhancer.enhance(2.0)
                decoded_objects = decode(high_contrast, symbols=[ZBarSymbol.QRCODE])
                
            # Attempt 3: Scaled down (smartphones take massive photos that confuse zbar)
            if not decoded_objects:
                small = img.resize((img.width // 3, img.height // 3))
                decoded_objects = decode(small, symbols=[ZBarSymbol.QRCODE])
            
            if not decoded_objects:
                log_callback(f"  [!] {img_path.name}: Nie znaleziono kodu QR.")
                continue
                
            for obj in decoded_objects:
                data = obj.data.decode("utf-8")
            # Expected format: "Product XYZ | 500kg | 2023-10-25 12:00:00 | XX"
            # (operator initials at the end are optional — older stickers don't have them)
            parts = [p.strip() for p in data.split("|")]
            if len(parts) >= 2:
                product_name = parts[0]
                weight_str = parts[1].replace("kg", "").strip()
                date_str = parts[2] if len(parts) >= 3 else "Brak daty"
                initials = parts[3] if len(parts) >= 4 else ""
                try:
                    weight = float(weight_str)
                except ValueError:
                    log_callback(f"  [!] {img_path.name}: Zignorowano (nieprawidłowa waga: '{parts[1]}')")
                    continue

                items.append({
                    "product": product_name,
                    "weight": weight,
                    "date": date_str,
                    "initials": initials,
                    "file": img_path.name
                })
                
                log_callback(f"  [OK] {img_path.name}: {product_name} ({weight}kg)")
            else:
                log_callback(f"  [!] {img_path.name}: Zignorowano (nierozpoznany format: '{data}')")
                    
        except Exception as e:
            log_callback(f"  [BŁĄD] {img_path.name}: {e}")

    report_name = f"wyniki_inwentaryzacji_{path.name}.txt"
    report_path = path / report_name
    
    with open(report_path, "w", encoding="utf-8") as f:
        f.write(f"RAPORT INWENTARYZACJI - Folder: {path.name}\n")
        f.write("="*40 + "\n\n")
        
        if not items:
            f.write("Brak pomyślnie zeskanowanych palet.\n")
        else:
            for idx, item in enumerate(items, 1):
                f.write(f"Paleta #{idx}\n")
                f.write(f"  Produkt: {item['product']}\n")
                f.write(f"  Waga:    {item['weight']} kg\n")
                f.write(f"  Data:    {item['date']}\n")
                if item['initials']:
                    f.write(f"  Inicjały: {item['initials']}\n")
                f.write(f"  Plik:    {item['file']}\n")
                f.write("-" * 20 + "\n")
                
            total_weight = sum(item['weight'] for item in items)
            f.write(f"\nPodsumowanie ogólne:\n")
            f.write(f"Liczba zeskanowanych palet: {len(items)}\n")
            f.write(f"Łączna waga wszystkich palet: {total_weight} kg\n")
                
    log_callback(f"\nZakończono. Podsumowanie zapisano w pliku: {report_path}")
    return report_path

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Użycie: python skaner.py <ścieżka_do_folderu>")
        sys.exit(1)
        
    process_inventory(sys.argv[1])
