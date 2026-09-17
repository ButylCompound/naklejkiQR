"""Shared validation and formatting helpers for generated labels."""

import math
from datetime import datetime


_LATEX_ESCAPES = {
    '\\': r'\textbackslash{}',
    '{': r'\{',
    '}': r'\}',
    '$': r'\$',
    '&': r'\&',
    '#': r'\#',
    '%': r'\%',
    '_': r'\_',
    '~': r'\textasciitilde{}',
    '^': r'\textasciicircum{}',
}


def escape_latex(value):
    """Escape user-controlled text before inserting it into LaTeX."""
    return ''.join(_LATEX_ESCAPES.get(char, char) for char in str(value))


def validate_qr_fields(product_name, weight, unit, operator=''):
    """Reject the separator used by the QR payload format in its fields."""
    fields = {
        'product name': product_name,
        'quantity': weight,
        'unit': unit,
        'operator': operator,
    }
    for field_name, value in fields.items():
        if '|' in str(value):
            raise ValueError(
                f"The {field_name} cannot contain the '|' character because it "
                "is reserved as the QR field separator."
            )


def validate_label_input(product_name, weight, unit='kg', operator=''):
    product_name = str(product_name).strip()
    weight = str(weight).strip()
    unit = str(unit).strip()
    operator = str(operator).strip()

    if not product_name:
        raise ValueError('Product name is required.')
    if any(character in product_name for character in '\r\n\t'):
        raise ValueError('Product name cannot contain line breaks or tabs.')

    try:
        quantity = float(weight.replace(',', '.'))
    except ValueError as error:
        raise ValueError('Quantity must be a number, for example 500 or 123.5.') from error
    if not math.isfinite(quantity) or quantity <= 0:
        raise ValueError('Quantity must be greater than zero.')

    if unit not in ('kg', 'szt.', ''):
        raise ValueError("Unit must be 'kg', 'szt.' or empty.")
    if len(operator) not in (0, 2, 3):
        raise ValueError('Operator initials must contain 2 or 3 characters, or be empty.')
    if any(character in operator for character in '\r\n\t'):
        raise ValueError('Operator initials cannot contain line breaks or tabs.')

    validate_qr_fields(product_name, weight, unit, operator)
    return product_name, weight, unit, operator


def normalize_label_date(value=None):
    if not value:
        return datetime.now().strftime('%Y-%m-%d %H:%M')
    for date_format in ('%Y-%m-%d %H:%M', '%Y-%m-%d %H:%M:%S'):
        try:
            return datetime.strptime(value, date_format).strftime('%Y-%m-%d %H:%M')
        except ValueError:
            pass
    raise ValueError('Date must use the format YYYY-MM-DD HH:MM or YYYY-MM-DD HH:MM:SS.')


def numbered_pack_names(product_name, pack_count):
    """Return product names suffixed from ``(1)`` through ``(pack_count)``."""
    if not isinstance(pack_count, int) or isinstance(pack_count, bool) or pack_count < 1:
        raise ValueError('Pack count must be a positive integer.')
    return [f'{product_name} ({number})' for number in range(1, pack_count + 1)]
