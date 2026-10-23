import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import gui


class ReportIdViewTests(unittest.TestCase):
    def test_review_filter_preserves_separate_quantities_and_units(self):
        tree = Mock()
        tree.get_children.return_value = []
        tab = SimpleNamespace(
            tree=tree,
            filter_var=SimpleNamespace(get=lambda: 'weryfikacja'),
            entry_search=SimpleNamespace(get=lambda: ''),
            lbl_count=Mock(),
            all_records=[
                {'Produkt': 'Mixed', 'Kategoria': 'weryfikacja', 'Zeskanowana Ilość': '500 kg, 20 szt.', 'Jednostka': 'kg, szt.'},
                {'Produkt': 'Matched', 'Kategoria': 'zgodne'},
            ],
        )
        gui.RawMaterialsTab.apply_filters(tab)
        tree.insert.assert_called_once()
        values = tree.insert.call_args.kwargs['values']
        self.assertEqual(values[3], '500 kg, 20 szt.')
        self.assertEqual(values[7], 'kg, szt.')

    def test_both_tables_display_and_export_ids(self):
        for tab_class in (gui.PalletInventoryTab, gui.RawMaterialsTab):
            for extension in ('csv', 'xlsx'):
                with self.subTest(tab=tab_class.__name__, extension=extension):
                    tree = Mock()
                    tree.get_children.return_value = []
                    tab = SimpleNamespace(
                        tree=tree,
                        filter_var=SimpleNamespace(get=lambda: 'all'),
                        entry_search=SimpleNamespace(get=lambda: ''),
                        lbl_count=Mock(),
                        all_records=[{'Produkt': 'Materiał Łódź', 'ID': '00000001'}],
                    )
                    tab_class.apply_filters(tab)
                    values = tree.insert.call_args.kwargs['values']
                    self.assertEqual(values[6], '00000001')
                    tree.get_children.return_value = ['row']
                    tree.item.return_value = values

                    with tempfile.TemporaryDirectory() as temp_dir:
                        path = Path(temp_dir) / f'report.{extension}'
                        with (
                            patch.object(gui.filedialog, 'asksaveasfilename', return_value=str(path)),
                            patch.object(gui.messagebox, 'showinfo'),
                            patch.object(gui.messagebox, 'showerror') as error,
                        ):
                            tab_class.export_visible(tab)
                        error.assert_not_called()
                        report = (
                            pd.read_csv(path, sep=';', dtype={'ID': str})
                            if extension == 'csv' else pd.read_excel(path, dtype={'ID': str})
                        )
                        self.assertEqual(report.iloc[0]['ID'], '00000001')

                    tab.all_records[0].pop('ID')
                    tab_class.apply_filters(tab)
                    self.assertEqual(tree.insert.call_args.kwargs['values'][6], '')


if __name__ == '__main__':
    unittest.main()
