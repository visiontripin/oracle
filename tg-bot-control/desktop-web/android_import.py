"""Extract literal answer lists from Android-style Python configs without executing code."""
import ast


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
        if not isinstance(node.value, (ast.List, ast.Tuple)):
            continue
        values = []
        for item in node.value.elts:
            if not isinstance(item, ast.Constant) or not isinstance(item.value, str) or not item.value.strip() or len(item.value) > 4096:
                values = []
                break
            values.append(item.value)
        if 2 <= len(values) <= 500:
            result.append({'name': name, 'items': values})
        if len(result) > 100: raise ValueError('Слишком много наборов в файле.')
    return result
