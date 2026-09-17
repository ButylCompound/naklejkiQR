"""Launcher for the numbered raw-material pack label generator."""

import tkinter as tk

from gui import App


if __name__ == "__main__":
    root = tk.Tk()
    app = App(root, pack_mode=True)
    root.mainloop()
