import contextlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from datetime import date
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from typer.testing import CliRunner

PROJECT_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_DIR))

import gui as generator_gui
import label_generator
import main
from label_generator import LatexError, TemplateError, generate_pdf
from label_utils import (
    escape_latex,
    normalize_label_date,
    numbered_pack_names,
    validate_label_input,
    validate_qr_fields,
    validate_lot_allocations,
    validate_lot_number,
)


PDFLATEX = shutil.which('pdflatex') or (shutil.which('pdflatex.exe') if os.name == 'nt' else None)


class FakeVar:
    def __init__(self, value=''):
        self.value = value

    def get(self):
        return self.value

    def set(self, value):
        self.value = value


class GeneratorTestCase(unittest.TestCase):
    @contextlib.contextmanager
    def project_directory(self, production_template=False, template=None):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            if production_template:
                shutil.copy2(PROJECT_DIR / 'sticker_template.tex', root)
                shutil.copy2(PROJECT_DIR / 'sticker_template_lots.tex', root)
                shutil.copy2(PROJECT_DIR / 'logo.jpg', root)
            elif template is not None:
                (root / 'sticker_template.tex').write_text(template, encoding='utf-8')
            previous = Path.cwd()
            os.chdir(root)
            try:
                yield root
            finally:
                os.chdir(previous)

    @staticmethod
    def successful_pdflatex(tex_path):
        Path(tex_path).with_suffix('.pdf').write_bytes(b'%PDF-1.4\n%%EOF\n')
        return subprocess.CompletedProcess(['pdflatex'], 0, 'ok', '')


class LabelInputTests(GeneratorTestCase):
    def test_lot_allocations_must_match_the_pack_count(self):
        self.assertEqual(validate_lot_allocations([(' A-01 ', ' 3 '), ('B/02', '9')], 12), [('A-01', 3), ('B/02', 9)])
        for rows in ([('A', 11)], [('A', 13)], []):
            with self.subTest(rows=rows), self.assertRaises(ValueError):
                validate_lot_allocations(rows, 12)

    def test_lot_rows_reject_missing_numbers_bad_counts_and_qr_separators(self):
        for lot in ('', ' ', 'A|B', 'A\nB', 'A\tB'):
            with self.subTest(lot=lot), self.assertRaisesRegex(ValueError, 'Numer partii'):
                validate_lot_number(lot)
        for count in ('', '0', '-1', '1.5', 'abc'):
            with self.subTest(count=count), self.assertRaisesRegex(ValueError, 'Wiersz 1: Liczba opakowań'):
                validate_lot_allocations([('A', count)], 12)

    def test_all_latex_special_characters_are_escaped(self):
        value = '50%_A&B#1${x}~^\\'

        self.assertEqual(
            escape_latex(value),
            r'50\%\_A\&B\#1\$\{x\}\textasciitilde{}\textasciicircum{}\textbackslash{}',
        )

    def test_polish_and_other_safe_characters_are_unchanged(self):
        value = 'Zażółć gęślą jaźń / + - (12) [A] “test”'

        self.assertEqual(escape_latex(value), value)

    def test_separator_is_rejected_in_every_qr_field_with_field_name(self):
        field_sets = [
            (('A|B', '10', 'kg', 'QC'), 'product name'),
            (('AB', '1|0', 'kg', 'QC'), 'quantity'),
            (('AB', '10', 'k|g', 'QC'), 'unit'),
            (('AB', '10', 'kg', 'Q|C'), 'operator'),
        ]

        for fields, field_name in field_sets:
            with self.subTest(fields=fields):
                with self.assertRaisesRegex(ValueError, field_name):
                    validate_qr_fields(*fields)

    def test_valid_inputs_are_trimmed_and_decimal_comma_is_accepted(self):
        result = validate_label_input('  Materiał Łódź  ', ' 12,5 ', 'kg', ' QC ')

        self.assertEqual(result, ('Materiał Łódź', '12,5', 'kg', 'QC'))

    def test_invalid_label_inputs_have_clear_messages(self):
        cases = [
            (('', '10', 'kg', ''), 'Product name is required'),
            (('Name\nNext', '10', 'kg', ''), 'line breaks or tabs'),
            (('Name', '', 'kg', ''), 'Quantity must be a number'),
            (('Name', 'heavy', 'kg', ''), 'Quantity must be a number'),
            (('Name', '0', 'kg', ''), 'greater than zero'),
            (('Name', '-1', 'kg', ''), 'greater than zero'),
            (('Name', 'nan', 'kg', ''), 'greater than zero'),
            (('Name', 'inf', 'kg', ''), 'greater than zero'),
            (('Name', '10', 'litres', ''), "Unit must be 'kg', 'szt.' or empty"),
            (('Name', '10', 'kg', 'Q'), '2 or 3 characters'),
            (('Name', '10', 'kg', 'LONG'), '2 or 3 characters'),
        ]

        for fields, message in cases:
            with self.subTest(fields=fields):
                with self.assertRaisesRegex(ValueError, message):
                    validate_label_input(*fields)

    def test_date_formats_are_normalized_and_invalid_date_is_clear(self):
        self.assertEqual(normalize_label_date('2026-08-25 09:05'), '2026-08-25 09:05')
        self.assertEqual(normalize_label_date('2026-08-25 09:05:59'), '2026-08-25 09:05')
        with self.assertRaisesRegex(ValueError, 'YYYY-MM-DD HH:MM'):
            normalize_label_date('25.08.2026 09:05')

    def test_pack_names_and_invalid_counts(self):
        names = numbered_pack_names('Raw Material', 12)

        self.assertEqual(names[0], 'Raw Material (1)')
        self.assertEqual(names[-1], 'Raw Material (12)')
        for value in (0, -1, 1.5, True, '2'):
            with self.subTest(value=value):
                with self.assertRaisesRegex(ValueError, 'positive integer'):
                    numbered_pack_names('Raw Material', value)


class PdfGenerationTests(GeneratorTestCase):
    def test_shared_android_reader_fixtures_match_generated_payloads(self):
        fixtures = json.loads((PROJECT_DIR.parent / 'contracts' / 'generated-labels.json').read_text(encoding='utf-8'))
        for row in fixtures:
            if 'generator' not in row:
                continue
            with self.subTest(row=row['name']), self.project_directory(production_template=True):
                with (
                    patch.object(label_generator.qrcode, 'QRCode') as qr,
                    patch.object(label_generator, 'compile_pdf', return_value='label.pdf'),
                    patch.object(label_generator.secrets, 'token_urlsafe', return_value=row['labelId']),
                ):
                    generate_pdf(**row['generator'])
                self.assertEqual(row['payload'], qr.return_value.add_data.call_args.args[0])

    def test_lot_is_escaped_on_the_label_and_appended_as_sixth_qr_field(self):
        with self.project_directory(production_template=True):
            with (
                patch.object(label_generator.qrcode, 'QRCode') as qr,
                patch.object(label_generator, 'compile_pdf', return_value='label.pdf') as compile_pdf,
                patch.object(label_generator.secrets, 'token_urlsafe', return_value='aB3dE_fG'),
            ):
                generate_pdf('Material (1)', '25', operator='QC', date_override='2026-11-02 08:00',
                             lot_number='LOT_01&A#5', template_file='sticker_template_lots.tex')

            self.assertEqual(qr.return_value.add_data.call_args.args[0],
                             'Material (1) | 25 kg | 2026-11-02 08:00 | QC | aB3dE_fG | LOT_01&A#5')
            tex = compile_pdf.call_args.args[0]
            self.assertIn(r'LOT\_01\&A\#5', tex)
            self.assertLess(tex.index('Nazwa:'), tex.index('Nr partii:'))
            self.assertLess(tex.index('Nr partii:'), tex.index('Ilość netto:'))

    def test_invalid_lot_cannot_reach_qr_or_pdf_generation(self):
        with patch.object(label_generator.qrcode, 'QRCode') as qr:
            with self.assertRaisesRegex(ValueError, 'Numer partii'):
                generate_pdf('Material', '25', lot_number='A|B')
        qr.assert_not_called()

    def test_qr_contains_a_fresh_short_id_without_printing_it_as_text(self):
        for operator in ('QC', ''):
            with self.subTest(operator=operator), self.project_directory(production_template=True):
                with (
                    patch.object(label_generator.qrcode, 'QRCode') as qr,
                    patch.object(label_generator, 'compile_pdf', return_value='label.pdf') as compile_pdf,
                    patch.object(
                        label_generator.secrets, 'token_urlsafe',
                        wraps=label_generator.secrets.token_urlsafe,
                    ) as token,
                ):
                    for _ in range(2):
                        generate_pdf('Materiał (1)', '12,5', operator=operator, date_override='2026-10-16 08:00')
                        payload = qr.return_value.add_data.call_args.args[0]
                        fields = [field.strip() for field in payload.split('|')]
                        self.assertEqual(fields[:4], ['Materiał (1)', '12,5 kg', '2026-10-16 08:00', operator])
                        self.assertRegex(fields[4], r'^[A-Za-z0-9_-]{8}$')
                        self.assertNotIn(fields[4], compile_pdf.call_args.args[0])
                    self.assertEqual(token.call_count, 2)
                    token.assert_called_with(6)

    def test_gui_and_cli_use_the_shared_generator(self):
        self.assertIs(generator_gui.generate_pdf, label_generator.generate_pdf)
        self.assertIs(main.generate_pdf, label_generator.generate_pdf)

    def test_unusual_characters_are_escaped_in_tex_and_pdf_is_returned(self):
        product = 'Zażółć gęślą jaźń & 50%_A#1${x}~^\\'
        with self.project_directory(production_template=True) as root:
            with patch.object(
                label_generator,
                'run_pdflatex',
                side_effect=self.successful_pdflatex,
            ):
                pdf_path = generate_pdf(
                    product,
                    '12,5',
                    operator='Q#',
                    date_override='2026-08-25 09:05:59',
                    unit='kg',
                )

            tex = (root / 'output.tex').read_text(encoding='utf-8')
            self.assertIn('Zażółć gęślą jaźń', tex)
            self.assertIn(r'\& 50\%\_A\#1\$\{x\}', tex)
            self.assertIn(r'\textasciitilde{}\textasciicircum{}\textbackslash{}', tex)
            self.assertIn(r'12,5 kg', tex)
            self.assertIn(r'Q\#', tex)
            self.assertIn('2026-08-25', tex)
            self.assertIn('09:05', tex)
            self.assertTrue((root / 'output_qr.png').is_file())
            self.assertTrue(Path(pdf_path).samefile(root / 'output.pdf'))

    def test_custom_output_name_with_spaces_is_supported(self):
        template = r'\documentclass{article}\begin{document}<< product_name >>\end{document}'
        with self.project_directory(template=template) as root:
            with patch.object(
                label_generator,
                'run_pdflatex',
                side_effect=self.successful_pdflatex,
            ):
                result = generate_pdf('Name', '10', output_stem='label 01')

            self.assertTrue(Path(result).samefile(root / 'label 01.pdf'))
            self.assertTrue((root / 'label 01.tex').is_file())
            self.assertTrue((root / 'label 01_qr.png').is_file())

    def test_missing_template_has_clear_error(self):
        with self.project_directory():
            with self.assertRaisesRegex(TemplateError, "Template file 'missing.tex' was not found"):
                generate_pdf('Name', '10', template_file='missing.tex')

    def test_invalid_template_syntax_has_clear_error(self):
        with self.project_directory(template='<BLOCK> if product_name </BLOCK>'):
            with self.assertRaisesRegex(TemplateError, "Template file 'sticker_template.tex' is invalid"):
                generate_pdf('Name', '10')

    def test_missing_template_variable_has_clear_error(self):
        with self.project_directory(template='<< unknown_value >>'):
            with self.assertRaisesRegex(TemplateError, 'unknown_value'):
                generate_pdf('Name', '10')

    def test_pdflatex_failure_contains_file_and_compiler_output(self):
        template = r'\documentclass{article}\begin{document}test\end{document}'
        failed = subprocess.CompletedProcess(
            ['pdflatex'],
            1,
            '! Undefined control sequence on line 7.',
            'fatal compile error',
        )
        with self.project_directory(template=template):
            with patch.object(label_generator, 'run_pdflatex', return_value=failed):
                with self.assertRaises(LatexError) as caught:
                    generate_pdf('Name', '10')

        message = str(caught.exception)
        self.assertIn("pdflatex failed while compiling 'output.tex'", message)
        self.assertIn('Undefined control sequence', message)
        self.assertIn('fatal compile error', message)

    def test_success_without_expected_pdf_has_clear_error(self):
        template = r'\documentclass{article}\begin{document}test\end{document}'
        success = subprocess.CompletedProcess(['pdflatex'], 0, 'ok', '')
        with self.project_directory(template=template):
            with patch.object(label_generator, 'run_pdflatex', return_value=success):
                with self.assertRaisesRegex(LatexError, 'did not create the expected PDF'):
                    generate_pdf('Name', '10')

    @unittest.skipUnless(PDFLATEX, 'pdflatex.exe or pdflatex is not available')
    def test_real_pdflatex_compiles_production_template_with_unusual_characters(self):
        with self.project_directory(production_template=True):
            for label_size in label_generator.LABEL_SIZES:
                with self.subTest(label_size=label_size):
                    pdf_path = generate_pdf(
                        'Zażółć & 50%_A#1',
                        '12,5',
                        operator='Q#',
                        date_override='2026-08-25 09:05',
                        label_size=label_size,
                    )

                    pdf = Path(pdf_path)
                    self.assertTrue(pdf.is_file())
                    self.assertGreater(pdf.stat().st_size, 1000)
                    self.assertEqual(pdf.read_bytes()[:4], b'%PDF')

    @unittest.skipUnless(PDFLATEX, 'pdflatex.exe or pdflatex is not available')
    def test_real_pdflatex_compiles_lot_template_at_both_sizes(self):
        with self.project_directory(production_template=True):
            for label_size in label_generator.LABEL_SIZES:
                with self.subTest(label_size=label_size):
                    result = generate_pdf(
                        'MIESZANKA XYZ-123 CZARNA (12)', '1234,5', operator='QC',
                        lot_number='LOT_2026/11&A#5', template_file='sticker_template_lots.tex',
                        date_override='2026-11-02 08:00', label_size=label_size,
                    )
                    self.assertGreater(Path(result).stat().st_size, 1000)


class PdflatexCommandTests(GeneratorTestCase):
    def test_windows_name_is_tried_first(self):
        completed = subprocess.CompletedProcess(['pdflatex.exe'], 0, '', '')
        with patch.object(label_generator.subprocess, 'run', return_value=completed) as run:
            result = label_generator.run_pdflatex('label.tex')

        self.assertIs(result, completed)
        self.assertEqual(run.call_args.args[0][0], 'pdflatex.exe')
        self.assertIn('-halt-on-error', run.call_args.args[0])

    def test_portable_name_is_used_if_windows_name_is_missing(self):
        completed = subprocess.CompletedProcess(['pdflatex'], 0, '', '')
        with patch.object(
            label_generator.subprocess,
            'run',
            side_effect=[FileNotFoundError(), completed],
        ) as run:
            result = label_generator.run_pdflatex('label.tex')

        self.assertIs(result, completed)
        self.assertEqual(run.call_args_list[1].args[0][0], 'pdflatex')

    def test_missing_pdflatex_has_clear_error(self):
        with patch.object(
            label_generator.subprocess,
            'run',
            side_effect=[FileNotFoundError(), FileNotFoundError()],
        ):
            with self.assertRaisesRegex(LatexError, "Neither 'pdflatex.exe' nor 'pdflatex'"):
                label_generator.run_pdflatex('label.tex')


class CliTests(GeneratorTestCase):
    def setUp(self):
        self.runner = CliRunner()

    def invoke(self, arguments, state=None):
        state = state or {'last_product_name': '', 'last_operator': ''}
        with patch.object(main, 'load_state', return_value=state):
            return self.runner.invoke(main.app, arguments)

    def test_invalid_cli_inputs_have_clear_errors(self):
        cases = [
            (['--weight', '10', '--name', ' '], 'Product name is required'),
            (['--weight', 'heavy', '--name', 'Name'], 'Quantity must be a number'),
            (['--weight', '0', '--name', 'Name'], 'greater than zero'),
            (['--weight', '10', '--name', 'Name', '--unit', 'litres'], 'Unit must be'),
            (['--weight', '10', '--name', 'Name', '--operator', 'Q'], '2 or 3 characters'),
            (['--weight', '10', '--name', 'A|B'], 'product name'),
            (['--weight', '10', '--name', 'Name', '--date', '25.08.2026'], 'Date must use'),
            (['--weight', '10', '--name', 'Name', '--copies', '0'], 'at least 1'),
        ]

        with self.project_directory():
            for arguments, message in cases:
                with self.subTest(arguments=arguments):
                    result = self.invoke([*arguments, '--no-print'])
                    self.assertEqual(result.exit_code, 1)
                    self.assertIn('Error:', result.output)
                    self.assertIn(message, result.output)

    def test_missing_name_and_empty_state_has_clear_error(self):
        result = self.invoke(['--weight', '10', '--no-print'])

        self.assertEqual(result.exit_code, 1)
        self.assertIn('No product name provided', result.output)

    def test_template_and_pdflatex_errors_reach_cli(self):
        errors = [
            TemplateError("Template file 'missing.tex' was not found."),
            LatexError("Neither 'pdflatex.exe' nor 'pdflatex' could be run from PATH."),
            LatexError("pdflatex failed while compiling 'output.tex'."),
        ]
        for error in errors:
            with self.subTest(error=error):
                with patch.object(main, 'generate_pdf', side_effect=error):
                    result = self.invoke(['--weight', '10', '--name', 'Name', '--no-print'])
                self.assertEqual(result.exit_code, 1)
                self.assertIn(str(error), result.output)

    def test_successful_cli_preserves_unicode_and_saves_state_after_generation(self):
        with (
            patch.object(main, 'generate_pdf', return_value='output.pdf') as generate,
            patch.object(main, 'save_state') as save_state,
        ):
            result = self.invoke([
                '--weight', '12,5',
                '--name', 'Zażółć & test',
                '--operator', 'Q#',
                '--date', '2026-08-25 09:05',
                '--no-print',
            ])

        self.assertEqual(result.exit_code, 0)
        self.assertEqual(generate.call_args.args[:2], ('Zażółć & test', '12,5'))
        save_state.assert_called_once_with({
            'last_product_name': 'Zażółć & test',
            'last_operator': 'Q#',
        })
        self.assertIn('PDF successfully generated', result.output)


class PackModeTests(GeneratorTestCase):
    @staticmethod
    def app(label_size=label_generator.LABEL_SIZES[0]):
        return SimpleNamespace(
            pack_mode=True,
            name_var=FakeVar('Raw Material'),
            weight_var=FakeVar('25'),
            unit_var=FakeVar('kg'),
            operator_var=FakeVar('QC'),
            date_var=FakeVar(''),
            copies_var=FakeVar('6'),
            pack_count_var=FakeVar('12'),
            add_lots_var=FakeVar(False),
            lots=[],
            label_size_var=FakeVar(label_size),
            printer_var=FakeVar('Zebra'),
            status_var=FakeVar('Gotowy.'),
            state={},
            root=SimpleNamespace(update_idletasks=lambda: None),
            weight_entry=SimpleNamespace(focus=lambda: None),
        )

    def test_twelve_packs_and_six_copies_print_seventy_two_labels(self):
        fake_app = self.app()
        with (
            patch.object(generator_gui.messagebox, 'askyesno', return_value=True) as confirm,
            patch.object(generator_gui.messagebox, 'showerror') as show_error,
            patch.object(generator_gui, 'generate_pdf', return_value='label.pdf') as generate,
            patch.object(generator_gui, 'print_pdf') as print_label,
            patch.object(generator_gui, 'save_state'),
        ):
            generator_gui.App._process(fake_app, do_print=True)

        generated_names = [call.args[0] for call in generate.call_args_list]
        generated_dates = {call.kwargs['date_override'] for call in generate.call_args_list}
        self.assertEqual(generated_names[0], 'Raw Material (1)')
        self.assertEqual(generated_names[-1], 'Raw Material (12)')
        self.assertEqual(generate.call_args_list[0].kwargs['output_stem'], 'output_pack_001')
        self.assertEqual(generate.call_args_list[-1].kwargs['output_stem'], 'output_pack_012')
        self.assertEqual(len(generated_dates), 1)
        self.assertEqual(generate.call_count, 12)
        self.assertEqual(print_label.call_count, 72)
        self.assertEqual(generate.call_args.kwargs['label_size'], label_generator.LABEL_SIZES[0])
        self.assertTrue(print_label.call_args.kwargs['match_pdf_size'])
        self.assertIn('Łącznie naklejek: 72', confirm.call_args.args[1])
        self.assertIn('Wydrukowano 72 naklejek', fake_app.status_var.get())
        show_error.assert_not_called()

    def test_lot_packs_keep_numbering_and_multiply_copies_at_both_sizes(self):
        for label_size in label_generator.LABEL_SIZES:
            fake_app = self.app(label_size)
            fake_app.add_lots_var.set(True)
            fake_app.lots = [('A-01', 3), ('B-02', 9)]
            with (
                patch.object(generator_gui.messagebox, 'askyesno', return_value=True),
                patch.object(generator_gui.messagebox, 'showerror') as show_error,
                patch.object(generator_gui, 'generate_pdf', return_value='label.pdf') as generate,
                patch.object(generator_gui, 'print_pdf') as print_label,
                patch.object(generator_gui, 'save_state'),
            ):
                generator_gui.App._process(fake_app, do_print=True)

            self.assertEqual(generate.call_count, 12)
            self.assertEqual(print_label.call_count, 72)
            self.assertEqual([call.kwargs['lot_number'] for call in generate.call_args_list], ['A-01'] * 3 + ['B-02'] * 9)
            self.assertEqual([call.args[0] for call in generate.call_args_list], [f'Raw Material ({i})' for i in range(1, 13)])
            self.assertTrue(all(call.kwargs['template_file'] == 'sticker_template_lots.tex' for call in generate.call_args_list))
            self.assertTrue(all(call.kwargs['label_size'] == label_size for call in generate.call_args_list))
            show_error.assert_not_called()

    def test_mismatched_lot_totals_stop_printing_and_pdf_generation(self):
        for do_print in (True, False):
            for lots in ([('A', 11)], [('A', 13)], []):
                with self.subTest(do_print=do_print, lots=lots):
                    fake_app = self.app()
                    fake_app.add_lots_var.set(True)
                    fake_app.lots = lots
                    with (
                        patch.object(generator_gui.messagebox, 'showerror') as show_error,
                        patch.object(generator_gui, 'generate_pdf') as generate,
                        patch.object(generator_gui, 'print_pdf') as print_label,
                    ):
                        generator_gui.App._process(fake_app, do_print=do_print)
                    show_error.assert_called_once()
                    generate.assert_not_called()
                    print_label.assert_not_called()

    def test_unchecked_lots_use_the_original_template(self):
        fake_app = self.app()
        fake_app.lots = [('old lot', 1)]
        with (
            patch.object(generator_gui.messagebox, 'showinfo'),
            patch.object(generator_gui.messagebox, 'showerror') as show_error,
            patch.object(generator_gui, 'generate_pdf', return_value='label.pdf') as generate,
            patch.object(generator_gui, 'save_state'),
        ):
            generator_gui.App._process(fake_app, do_print=False)
        self.assertEqual(generate.call_count, 12)
        self.assertNotIn('lot_number', generate.call_args.kwargs)
        self.assertNotIn('template_file', generate.call_args.kwargs)
        show_error.assert_not_called()

    def test_lot_dialog_confirmation_prints_without_a_second_prompt(self):
        fake_app = self.app()
        fake_app.add_lots_var.set(True)
        fake_app.lots = [('A', 12)]
        with (
            patch.object(generator_gui.messagebox, 'askyesno') as confirm,
            patch.object(generator_gui.messagebox, 'showerror') as show_error,
            patch.object(generator_gui, 'generate_pdf', return_value='label.pdf'),
            patch.object(generator_gui, 'print_pdf') as print_label,
            patch.object(generator_gui, 'save_state'),
        ):
            generator_gui.App._process(fake_app, do_print=True, print_confirmed=True)
        confirm.assert_not_called()
        print_label.assert_called()
        show_error.assert_not_called()

    def test_both_sizes_apply_to_every_pack_when_printing_or_saving(self):
        for label_size in label_generator.LABEL_SIZES:
            for do_print in (True, False):
                with self.subTest(label_size=label_size, do_print=do_print):
                    fake_app = self.app(label_size)
                    with (
                        patch.object(generator_gui.messagebox, 'askyesno', return_value=True),
                        patch.object(generator_gui.messagebox, 'showinfo'),
                        patch.object(generator_gui.messagebox, 'showerror') as show_error,
                        patch.object(generator_gui, 'generate_pdf', return_value='label.pdf') as generate,
                        patch.object(generator_gui, 'print_pdf') as print_label,
                        patch.object(generator_gui, 'save_state') as save_state,
                    ):
                        generator_gui.App._process(fake_app, do_print=do_print)

                    self.assertEqual(generate.call_count, 12)
                    self.assertTrue(all(call.kwargs['label_size'] == label_size for call in generate.call_args_list))
                    self.assertEqual(print_label.call_count, 72 if do_print else 0)
                    self.assertTrue(all(call.kwargs['match_pdf_size'] for call in print_label.call_args_list))
                    self.assertEqual(save_state.call_args.args[0]['last_label_size'], label_size)
                    self.assertEqual(save_state.call_args.args[0]['last_pack_count'], 12)
                    show_error.assert_not_called()

    def test_invalid_copy_and_pack_counts_have_clear_errors(self):
        base = {
            'pack_mode': True,
            'name_var': FakeVar('Raw Material'),
            'weight_var': FakeVar('25'),
            'unit_var': FakeVar('kg'),
            'operator_var': FakeVar('QC'),
            'date_var': FakeVar(''),
            'printer_var': FakeVar('Zebra'),
            'status_var': FakeVar('Gotowy.'),
            'state': {},
            'root': SimpleNamespace(update_idletasks=lambda: None),
            'weight_entry': SimpleNamespace(focus=lambda: None),
        }
        cases = [
            (FakeVar('bad'), FakeVar('1'), 'Liczba kopii'),
            (FakeVar('1'), FakeVar('0'), 'Liczba opakowań'),
        ]
        for copies, packs, message in cases:
            with self.subTest(message=message):
                fake_app = SimpleNamespace(**base, copies_var=copies, pack_count_var=packs)
                with patch.object(generator_gui.messagebox, 'showerror') as show_error:
                    generator_gui.App._process(fake_app, do_print=True)
                self.assertIn(message, show_error.call_args.args[1])


class FactoryModeTests(GeneratorTestCase):
    @staticmethod
    def app(date_value):
        return SimpleNamespace(
            pack_mode=False,
            name_var=FakeVar('Product'),
            weight_var=FakeVar('25'),
            unit_var=FakeVar('kg'),
            operator_var=FakeVar('QC'),
            date_var=FakeVar(date_value),
            copies_var=FakeVar('1'),
            label_size_var=FakeVar(label_generator.LABEL_SIZES[0]),
            printer_var=FakeVar('Zebra'),
            status_var=FakeVar('Gotowy.'),
            state={},
            root=SimpleNamespace(update_idletasks=lambda: None),
            weight_entry=SimpleNamespace(focus=lambda: None),
        )

    def test_date_and_time_are_required(self):
        fake_app = self.app('')
        with (
            patch.object(generator_gui.messagebox, 'showerror') as show_error,
            patch.object(generator_gui, 'generate_pdf') as generate,
        ):
            generator_gui.App._process(fake_app, do_print=False)

        self.assertIn('Data i czas są wymagane', show_error.call_args.args[1])
        generate.assert_not_called()

    def test_empty_quantity_error_is_polish(self):
        fake_app = self.app('2026-09-17 08:00')
        fake_app.weight_var = FakeVar('')
        with (
            patch.object(generator_gui.messagebox, 'showerror') as show_error,
            patch.object(generator_gui, 'generate_pdf') as generate,
        ):
            generator_gui.App._process(fake_app, do_print=False)

        self.assertEqual(show_error.call_args.args[1], 'Ilość netto jest wymagana!')
        generate.assert_not_called()

    def test_shared_errors_are_translated_for_the_gui(self):
        cases = [
            ('Product name is required.', 'Nazwa produktu jest wymagana.'),
            ('Quantity must be greater than zero.', 'Ilość netto musi być większa od zera.'),
            ('Date must use the format YYYY-MM-DD HH:MM or YYYY-MM-DD HH:MM:SS.', 'Data i czas muszą mieć format RRRR-MM-DD GG:MM.'),
            ("The product name cannot contain the '|' character because it is reserved as the QR field separator.", 'Nazwa produktu nie może zawierać znaku „|”, ponieważ oddziela on pola kodu QR.'),
            ("Template file 'missing.tex' was not found.", 'Nie znaleziono pliku szablonu „missing.tex”.'),
            ("Neither 'pdflatex.exe' nor 'pdflatex' could be run from PATH.", 'Nie można uruchomić programu pdflatex. Sprawdź, czy jest dostępny w zmiennej PATH.'),
            ('Błąd drukowania (Windows): returned non-zero exit status 1', 'Nie udało się wysłać pliku PDF do drukarki w systemie Windows.'),
        ]

        for message, expected in cases:
            with self.subTest(message=message):
                self.assertEqual(generator_gui.polish_error_message(Exception(message)), expected)

    def test_only_today_and_yesterday_avoid_warning(self):
        today = date(2026, 9, 17)

        self.assertFalse(generator_gui.factory_date_needs_warning('2026-09-17 08:00', today))
        self.assertFalse(generator_gui.factory_date_needs_warning('2026-09-16 23:59', today))
        self.assertTrue(generator_gui.factory_date_needs_warning('2026-09-15 23:59', today))
        self.assertTrue(generator_gui.factory_date_needs_warning('2026-09-18 00:00', today))

    def test_unusual_date_requires_confirmation(self):
        fake_app = self.app('2000-01-01 08:00')
        with (
            patch.object(generator_gui.messagebox, 'askyesno', return_value=False) as confirm,
            patch.object(generator_gui, 'generate_pdf') as generate,
        ):
            generator_gui.App._process(fake_app, do_print=False)

        self.assertIn('nie jest dzisiejsza ani wczorajsza', confirm.call_args.args[1])
        generate.assert_not_called()

    def test_selected_size_is_used_for_generation_printing_and_saved(self):
        for label_size in label_generator.LABEL_SIZES:
            with self.subTest(label_size=label_size):
                fake_app = self.app('2026-10-16 08:00')
                fake_app.label_size_var.set(label_size)
                with (
                    patch.object(generator_gui, 'factory_date_needs_warning', return_value=False),
                    patch.object(generator_gui, 'generate_pdf', return_value='label.pdf') as generate,
                    patch.object(generator_gui, 'print_pdf') as print_label,
                    patch.object(generator_gui, 'save_state') as save_state,
                    patch.object(generator_gui.messagebox, 'showerror') as show_error,
                ):
                    generator_gui.App._process(fake_app, do_print=True)

                self.assertEqual(generate.call_args.kwargs['label_size'], label_size)
                print_label.assert_called_once_with('label.pdf', printer_name='Zebra', match_pdf_size=True)
                self.assertEqual(save_state.call_args.args[0]['last_label_size'], label_size)
                show_error.assert_not_called()

    def test_selected_size_is_used_when_saving_a_pdf(self):
        fake_app = self.app('2026-10-16 08:00')
        fake_app.label_size_var.set(label_generator.LABEL_SIZES[1])
        with (
            patch.object(generator_gui, 'factory_date_needs_warning', return_value=False),
            patch.object(generator_gui, 'generate_pdf', return_value='label.pdf') as generate,
            patch.object(generator_gui, 'print_pdf') as print_label,
            patch.object(generator_gui, 'save_state'),
            patch.object(generator_gui.subprocess, 'call'),
            patch.object(generator_gui.os, 'startfile', create=True),
            patch.object(generator_gui.messagebox, 'showerror') as show_error,
        ):
            generator_gui.App._process(fake_app, do_print=False)

        self.assertEqual(generate.call_args.kwargs['label_size'], label_generator.LABEL_SIZES[1])
        print_label.assert_not_called()
        show_error.assert_not_called()


class PrintingTests(GeneratorTestCase):
    def test_factory_windows_printing_uses_pdf_paper_size_without_forcing_landscape(self):
        with (
            patch.object(generator_gui.os, 'name', 'nt'),
            patch.object(generator_gui.subprocess, 'run') as run,
        ):
            generator_gui.print_pdf('label.pdf', 'Zebra', match_pdf_size=True)

        command = run.call_args.args[0]
        self.assertEqual(command[command.index('-print-settings') + 1], 'shrink,disable-auto-rotation')
        self.assertIn('Zebra', command)

    def test_default_windows_printing_uses_landscape(self):
        with (
            patch.object(generator_gui.os, 'name', 'nt'),
            patch.object(generator_gui.subprocess, 'run') as run,
        ):
            generator_gui.print_pdf('label.pdf', 'Zebra')

        command = run.call_args.args[0]
        self.assertEqual(command[command.index('-print-settings') + 1], 'shrink,landscape')

    @unittest.skipIf(os.name == 'nt', 'WSL-specific command test')
    def test_factory_wsl_printing_uses_pdf_size_and_windows_path(self):
        with (
            patch.object(generator_gui.os, 'uname', return_value=SimpleNamespace(release='microsoft')),
            patch.object(generator_gui.subprocess, 'run') as run,
        ):
            run.return_value.stdout = 'C:\\labels\\label.pdf\n'
            generator_gui.print_pdf('/mnt/c/labels/label.pdf', 'Zebra', match_pdf_size=True)

        command = run.call_args.args[0]
        self.assertEqual(command[command.index('-print-settings') + 1], 'shrink,disable-auto-rotation')
        self.assertEqual(command[-1], 'C:\\labels\\label.pdf')

    def test_windows_print_failure_is_returned_to_caller(self):
        with patch.object(
            main.subprocess,
            'run',
            side_effect=subprocess.CalledProcessError(1, ['SumatraPDF.exe']),
        ):
            self.assertFalse(main._print_windows('label.pdf', 'Zebra'))

    def test_windows_print_success_is_returned_to_caller(self):
        with patch.object(main.subprocess, 'run'):
            self.assertTrue(main._print_windows('label.pdf', 'Zebra'))

    @unittest.skipUnless(shutil.which('zsh'), 'zsh is not available')
    def test_batch_script_stops_and_reports_failure(self):
        script_path = PROJECT_DIR / 'print_batch.zsh'
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            (temp_path / 'dist').mkdir()
            (temp_path / 'do_wydruku.csv').write_text(
                'Nazwa,Data,Waga,Operator,Kopie\nProduct,2026-08-13 10:00,10,QC,1\n',
                encoding='utf-8',
            )
            fake_exe = temp_path / 'dist' / 'GeneratorNaklejek_CLI.exe'
            fake_exe.write_text('#!/bin/sh\nexit 1\n', encoding='utf-8')
            fake_exe.chmod(0o755)

            result = subprocess.run(
                ['zsh', str(script_path), '-d', '0'],
                cwd=temp_path,
                capture_output=True,
                text=True,
            )

        self.assertEqual(result.returncode, 1)
        self.assertIn('Pomyślnie wydrukowano etykiety dla 0 palet', result.stdout)


if __name__ == '__main__':
    unittest.main()
