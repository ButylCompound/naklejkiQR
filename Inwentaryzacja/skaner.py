import os
import sys
from pathlib import Path
import cv2

def process_inventory(folder_path, log_callback=print):
    log_callback(f"Starting scan in folder: {folder_path}")
    
    # Dictionary for aggregation: product_name -> {"total_weight": X, "pallets": Y}
    inventory = {}
    
    valid_ext = {".png", ".jpg", ".jpeg"}
    
    path = Path(folder_path)
    if not path.is_dir():
        log_callback(f"Error: The provided path '{folder_path}' is not a directory.")
        return None
        
    image_files = [f for f in path.iterdir() if f.suffix.lower() in valid_ext]
    if not image_files:
        log_callback(f"No images found in folder '{folder_path}'.")
        return None
        
    log_callback(f"Found {len(image_files)} images. Reading QR codes...")
    
    # Initialize OpenCV QR Code Detector
    detector = cv2.QRCodeDetector()
    
    for img_path in image_files:
        try:
            # Read image using OpenCV
            img = cv2.imread(str(img_path))
            if img is None:
                log_callback(f"  [!] {img_path.name}: Failed to read image file.")
                continue
                
            data, bbox, _ = detector.detectAndDecode(img)
            
            if not data:
                log_callback(f"  [!] {img_path.name}: No QR code found.")
                continue
                
            # Expected format: "Product XYZ | 500kg | 2023-10-25 12:00:00"
                parts = [p.strip() for p in data.split("|")]
                if len(parts) >= 2:
                    product_name = parts[0]
                    weight_str = parts[1].replace("kg", "").strip()
                    try:
                        weight = float(weight_str)
                    except ValueError:
                        log_callback(f"  [!] {img_path.name}: Ignored (invalid weight: '{parts[1]}')")
                        continue
                        
                    if product_name not in inventory:
                        inventory[product_name] = {"total_weight": 0.0, "pallets": 0}
                        
                    inventory[product_name]["total_weight"] += weight
                    inventory[product_name]["pallets"] += 1
                    
                    log_callback(f"  [OK] {img_path.name}: {product_name} ({weight}kg)")
                else:
                    log_callback(f"  [!] {img_path.name}: Ignored (unrecognized format: '{data}')")
                    
        except Exception as e:
            log_callback(f"  [ERROR] {img_path.name}: {e}")

    report_name = f"inventory_results_{path.name}.txt"
    report_path = path / report_name
    
    with open(report_path, "w", encoding="utf-8") as f:
        f.write(f"INVENTORY REPORT - Folder: {path.name}\n")
        f.write("="*40 + "\n\n")
        
        if not inventory:
            f.write("No pallets were successfully scanned.\n")
        else:
            total_pallets = 0
            for product, data in inventory.items():
                f.write(f"Product: {product}\n")
                f.write(f"  Pallets count: {data['pallets']}\n")
                f.write(f"  Total weight:  {data['total_weight']} kg\n")
                f.write("-" * 20 + "\n")
                total_pallets += data['pallets']
            f.write(f"\nOverall summary: {total_pallets} pallets across all categories.\n")
                
    log_callback(f"\nFinished. Summary saved in file: {report_path}")
    return report_path

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python skaner.py <path_to_date_folder>")
        sys.exit(1)
        
    process_inventory(sys.argv[1])
