import os
import errno
import subprocess
from pathlib import Path

import jinja2
import qrcode

from label_utils import escape_latex, normalize_label_date, validate_label_input


CREATE_NO_WINDOW = 0x08000000 if os.name == 'nt' else 0


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
):
    product_name, weight, unit, operator = validate_label_input(
        product_name,
        weight,
        unit,
        operator,
    )
    label_date = normalize_label_date(date_override)
    qr_data = f'{product_name} | {weight} {unit} | {label_date}'
    if operator:
        qr_data += f' | {operator}'

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
