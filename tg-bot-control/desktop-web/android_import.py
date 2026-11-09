"""Extract literal answer lists from Android-style Python configs without executing code."""
import ast

# Служебные списки из конфигов Android — это НЕ ответы (реестр в Промт-создание-ботов.md)
SERVICE_NAMES = {'SCHEDULE', 'COMMANDS', 'CONTENT_TYPES', 'MESSAGES', 'HISTORY', 'DAYS',
                 'BUTTONS', 'MENU', 'ITEMS', 'PAYLOAD', 'CHOICES', 'KEYBOARDS'}


def answers_only(name):
    """Список — кандидат в набор ответов, а не служебный/кадры анимации."""
    up = name.upper()
    if up in SERVICE_NAMES:
        return False
    if up.startswith('ANIM') or up.endswith('FRAMES') or up.endswith('_MENU'):
        return False
    if up.endswith('_BUTTONS') or up.endswith('_KEYBOARD') or up.endswith('KEYBOARDS'):
        return False
    return True


def extract_lists(source):
    if not isinstance(source, str) or len(source) > 512_000:
        raise ValueError('Файл должен быть текстом размером не более 512 КБ.')
    try:
        tree = ast.parse(source)
    except (SyntaxError, ValueError, RecursionError, MemoryError):
        raise ValueError('Не удалось разобрать Python-файл.') from None
    result = []
    for node in tree.body:
        if not isinstance(node, ast.Assign) or len(node.targets) != 1 or not isinstance(node.targets[0], ast.Name):
            continue
        name = node.targets[0].id
        if not name.isupper() or 'TOKEN' in name or 'KEY' in name or 'SECRET' in name or 'PASSWORD' in name:
            continue
        if not answers_only(name):
            continue
        if not isinstance(node.value, (ast.List, ast.Tuple)):
            continue
        values = []
        for item in node.value.elts:
            if not isinstance(item, ast.Constant) or not isinstance(item.value, str) or not item.value.strip() or len(item.value) > 4096:
                values = []
                break
            values.append(item.value)
        # photo: … — кадры фото-анимации, а не текстовые ответы
        if any(v.lstrip().startswith('photo:') for v in values):
            values = []
        if 2 <= len(values) <= 500:
            result.append({'name': name, 'items': values})
        if len(result) > 100: raise ValueError('Слишком много наборов в файле.')
    return result
