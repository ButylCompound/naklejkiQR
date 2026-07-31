import sys
import os

# Zapewnienie, że katalog bieżący jest w sys.path
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from gui import MainWindow

def main():
    app = MainWindow()
    app.mainloop()

if __name__ == "__main__":
    main()
