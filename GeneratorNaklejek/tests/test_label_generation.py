import contextlib
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
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
            pdf_path = generate_pdf(
                'Zażółć & 50%_A#1',
                '12,5',
                operator='Q#',
                date_override='2026-08-25 09:05',
            )

            pdf = Path(pdf_path)
            self.assertTrue(pdf.is_file())
            self.assertGreater(pdf.stat().st_size, 1000)
            self.assertEqual(pdf.read_bytes()[:4], b'%PDF')


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
    def test_twelve_packs_and_six_copies_print_seventy_two_labels(self):
        fake_app = SimpleNamespace(
            pack_mode=True,
            name_var=FakeVar('Raw Material'),
            weight_var=FakeVar('25'),
            unit_var=FakeVar('kg'),
            operator_var=FakeVar('QC'),
            date_var=FakeVar(''),
            copies_var=FakeVar('6'),
            pack_count_var=FakeVar('12'),
            printer_var=FakeVar('Zebra'),
            status_var=FakeVar('Gotowy.'),
            state={},
            root=SimpleNamespace(update_idletasks=lambda: None),
            weight_entry=SimpleNamespace(focus=lambda: None),
        )

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
        self.assertIn('Łącznie naklejek: 72', confirm.call_args.args[1])
        self.assertIn('Wydrukowano 72 naklejek', fake_app.status_var.get())
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


class PrintingTests(GeneratorTestCase):
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
