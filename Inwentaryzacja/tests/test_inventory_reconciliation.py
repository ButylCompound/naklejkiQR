import sys
import tempfile
import unittest
from datetime import date, time
from pathlib import Path

import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from reconciliation import reconcile_inventory


class PalletReconciliationTests(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.excel_path = self.root / 'expected.xlsx'
        self.csv_path = self.root / 'scans.csv'

    def tearDown(self):
        self.temp_dir.cleanup()

    def write_excel(self, rows):
        pd.DataFrame(rows).to_excel(self.excel_path, sheet_name='palety', index=False)

    def write_csv(self, rows):
        pd.DataFrame(rows).to_csv(self.csv_path, sep=';', index=False)

    @staticmethod
    def expected(product, quantity, status='Stan', day=date(2026, 8, 25), end=time(9, 5)):
        return {
            'Nazwa handlowa': product,
            'ilość na palecie': quantity,
            'Stan': status,
            'Data': day,
            'Godzina końca palety': end,
        }

    @staticmethod
    def scan(product, quantity, label_date='2026-08-25 09:05:00', number=1, aisle=1):
        return {
            'Lp': number,
            'Alejka': aisle,
            'Produkt': product,
            'Ilość': quantity,
            'Data naklejki': label_date,
        }

    def test_matches_missing_surplus_and_excludes_issued_pallets(self):
        self.write_excel([
            self.expected('Matched', 100),
            self.expected('Missing', 200),
            self.expected('Issued', 300, status='  WYDANA  '),
        ])
        self.write_csv([
            self.scan('Matched', 100, number=1, aisle=4),
            self.scan('Issued', 300, number=2),
            self.scan('Unknown', 50, number=3),
        ])

        result = reconcile_inventory(self.excel_path, self.csv_path)
        records = {record['Produkt']: record for record in result['records']}

        self.assertEqual(result['summary'], {
            'total_expected': 2,
            'total_scanned': 3,
            'matched': 1,
            'missing': 1,
            'surplus': 2,
        })
        self.assertEqual(records['Matched']['Kategoria'], 'zgodne')
        self.assertEqual(records['Matched']['Alejka'], '4')
        self.assertEqual(records['Matched']['Excel_Row'], 2)
        self.assertEqual(records['Missing']['Kategoria'], 'brakujace')
        self.assertEqual(records['Issued']['Kategoria'], 'nadwyzka')
        self.assertEqual(records['Unknown']['Kategoria'], 'nadwyzka')

    def test_duplicate_pallets_match_one_to_one_and_footer_is_ignored(self):
        repeated_header = {
            'Nazwa handlowa': 'Nazwa handlowa',
            'ilość na palecie': 'ilość na palecie',
            'Stan': 'Stan',
            'Data': 'Data',
            'Godzina końca palety': 'Godzina końca palety',
        }
        self.write_excel([
            self.expected('Duplicate', 50),
            repeated_header,
            self.expected('Duplicate', 50),
        ])
        rows = [self.scan('Duplicate', 50, number=index) for index in range(1, 4)]
        rows.append({
            'Lp': 'Liczba palet',
            'Alejka': 3,
            'Produkt': 'Duplicate',
            'Ilość': 3,
            'Data naklejki': 'invalid footer date',
        })
        self.write_csv(rows)

        result = reconcile_inventory(self.excel_path, self.csv_path)

        self.assertEqual(result['summary']['total_expected'], 2)
        self.assertEqual(result['summary']['total_scanned'], 3)
        self.assertEqual(result['summary']['matched'], 2)
        self.assertEqual(result['summary']['surplus'], 1)

    def test_polish_date_decimal_comma_and_quantity_suffix_are_supported(self):
        self.write_excel([self.expected('Material', '10,5 kg', end='9:05')])
        self.write_csv([
            self.scan('Material', '10,5 kg', label_date='25.08.2026 09:05'),
        ])

        result = reconcile_inventory(self.excel_path, self.csv_path)
        record = result['records'][0]

        self.assertEqual(result['summary']['matched'], 1)
        self.assertEqual(record['Ilość'], 10.5)
        self.assertEqual(record['Data'], '2026-08-25 09:05')

    def test_missing_excel_time_defaults_to_midnight(self):
        self.write_excel([self.expected('Midnight', 10, end=None)])
        self.write_csv([
            self.scan('Midnight', 10, label_date='2026-08-25 00:00'),
        ])

        result = reconcile_inventory(self.excel_path, self.csv_path)

        self.assertEqual(result['summary']['matched'], 1)
        self.assertEqual(result['records'][0]['Data'], '2026-08-25 00:00')

    def test_blank_rows_are_ignored(self):
        self.write_excel([
            self.expected('Present', 10),
            self.expected(None, None),
        ])
        self.write_csv([
            self.scan('Present', 10),
            self.scan(None, None, number=2),
        ])

        result = reconcile_inventory(self.excel_path, self.csv_path)

        self.assertEqual(result['summary'], {
            'total_expected': 1,
            'total_scanned': 1,
            'matched': 1,
            'missing': 0,
            'surplus': 0,
        })

    def test_alternate_sheet_and_weight_column_are_supported(self):
        pd.DataFrame([self.expected('Alternate', 75)]).to_excel(
            self.excel_path,
            sheet_name='custom',
            index=False,
        )
        scan = self.scan('Alternate', 75)
        scan['Waga (kg)'] = scan.pop('Ilość')
        self.write_csv([scan])

        result = reconcile_inventory(self.excel_path, self.csv_path, excel_sheet='custom')

        self.assertEqual(result['summary']['matched'], 1)


if __name__ == '__main__':
    unittest.main()
