import os
import errno
import secrets
import subprocess
from pathlib import Path

import jinja2
import qrcode

from label_utils import escape_latex, normalize_label_date, validate_label_input, validate_lot_number


CREATE_NO_WINDOW = 0x08000000 if os.name == 'nt' else 0
LABEL_SIZES = ('100 × 60 mm', '100 × 150 mm')


class TemplateError(RuntimeError):
    pass


class LatexError(RuntimeError):
    pass


def run_pdflatex(tex_path):
    arguments = ['-interaction=nonstopmode', '-halt-on-error', str(tex_path)]
    try:
        return subprocess.run(
            ['pdflatex.exe', *arguments],
            capture_output=True,
            text=True,
            creationflags=CREATE_NO_WINDOW,
        )
    except OSError as error:
        if not isinstance(error, FileNotFoundError) and error.errno != errno.ENOEXEC:
            raise
        try:
            return subprocess.run(
                ['pdflatex', *arguments],
                capture_output=True,
                text=True,
                creationflags=CREATE_NO_WINDOW,
            )
        except OSError as fallback_error:
            if not isinstance(fallback_error, FileNotFoundError) and fallback_error.errno != errno.ENOEXEC:
                raise
            raise LatexError(
                "Neither 'pdflatex.exe' nor 'pdflatex' could be run from PATH."
            ) from fallback_error


def generate_pdf(
    product_name,
    weight,
    operator='',
    date_override=None,
    unit='kg',
    output_stem='output',
    template_file='sticker_template.tex',
    label_size=LABEL_SIZES[0],
    lot_number=None,
):
    product_name, weight, unit, operator = validate_label_input(
        product_name,
        weight,
        unit,
        operator,
    )
    label_date = normalize_label_date(date_override)
    if lot_number is not None:
        lot_number = validate_lot_number(lot_number)
    label_id = secrets.token_urlsafe(6)
    qr_data = f'{product_name} | {weight} {unit} | {label_date} | {operator} | {label_id}'
    if lot_number is not None:
        qr_data += f' | {lot_number}'

    qr_path = Path(f'{output_stem}_qr.png')
    qr = qrcode.QRCode(version=1, box_size=10, border=0)
    qr.add_data(qr_data)
    qr.make(fit=True)
    qr.make_image(fill_color='black', back_color='white').save(qr_path)

    environment = jinja2.Environment(
        block_start_string='<BLOCK>',
        block_end_string='</BLOCK>',
        variable_start_string='<<',
        variable_end_string='>>',
        comment_start_string='<#',
        comment_end_string='#>',
        loader=jinja2.FileSystemLoader(Path.cwd()),
        undefined=jinja2.StrictUndefined,
    )
    try:
        template = environment.get_template(template_file)
        tex_content = template.render(
            product_name=escape_latex(product_name),
            product_name_length=len(product_name),
            lot_number=escape_latex(lot_number) if lot_number is not None else '',
            weight=escape_latex(weight),
            unit=escape_latex(unit),
            datetime=label_date,
            operator=escape_latex(operator),
            qr_path=qr_path.as_posix(),
        )
    except jinja2.TemplateNotFound as error:
        raise TemplateError(f"Template file '{template_file}' was not found.") from error
    except jinja2.TemplateError as error:
        raise TemplateError(f"Template file '{template_file}' is invalid: {error}") from error

    if label_size == LABEL_SIZES[1]:
        design_pdf = compile_pdf(tex_content, f'{output_stem}_design')
        tex_content = (
            r'\documentclass{article}' '\n'
            r'\usepackage{graphicx}' '\n'
            r'\begin{document}' '\n'
            r'\pdfpagewidth=100mm\pdfpageheight=150mm' '\n'
            r'\pdfhorigin=0pt\pdfvorigin=0pt' '\n'
            r'\shipout\hbox{\hskip5mm'
            r'\includegraphics[angle=90,height=150mm]{'
            + Path(design_pdf).name + '}}\n'
            r'\end{document}' '\n'
        )
    return compile_pdf(tex_content, output_stem)


def compile_pdf(tex_content, output_stem):
    tex_path = Path(f'{output_stem}.tex')
    pdf_path = Path(f'{output_stem}.pdf')
    tex_path.write_text(tex_content, encoding='utf-8')
    result = run_pdflatex(tex_path)
    if result.returncode != 0:
        details = '\n'.join(part.strip() for part in (result.stdout, result.stderr) if part.strip())
        if len(details) > 1000:
            details = details[-1000:]
        message = f"pdflatex failed while compiling '{tex_path}'."
        if details:
            message += f'\n{details}'
        raise LatexError(message)
    if not pdf_path.exists():
        raise LatexError(f"pdflatex did not create the expected PDF file '{pdf_path}'.")
    return str(pdf_path.resolve())
