"""Text animation presets derived from Android Anim.kt; no code execution."""
import math
import random

PRESETS = ('spinner', 'progress', 'dots', 'countdown', 'typewriter', 'matrix', 'slot', 'moon', 'clock', 'heart', 'custom')


def validate_animation(a):
    if not isinstance(a, dict) or a.get('preset') not in PRESETS:
        raise ValueError('Неизвестный шаблон анимации.')
    for key, default, low, high in [('interval', 700, 500, 3000), ('loops', 1, 1, 10), ('count', 5, 1, 30)]:
        v = a.get(key, default)
        if type(v) is not int or not low <= v <= high:
            raise ValueError('Недопустимый параметр анимации: ' + key)
    for key in ('text', 'final', 'frames'):
        if not isinstance(a.get(key, ''), str) or len(a.get(key, '')) > 20000:
            raise ValueError('Недопустимый текст анимации.')
    result = frames(a)
    if not result: raise ValueError('Анимация не содержит кадров.')
    return a


def frames(a, user=''):
    sub = lambda s: s.replace('{user}', user)
    text = sub(a.get('text', ''))
    loops = a.get('loops', 1)
    p = a['preset']
    if p == 'spinner': raw = [f'{c} {text}'.strip() for c in '⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏'] * loops
    elif p == 'progress': raw = [(text + '\n' if text.strip() else '') + '▰' * i + '▱' * (10-i) + f' {i*10}%' for i in range(11)]
    elif p == 'dots': raw = [text + d for d in ('', '.', '..', '...')] * loops
    elif p == 'countdown': raw = [(text + '\n\n' if text.strip() else '') + f'⏳ {i}' for i in range(a.get('count', 5), 0, -1)]
    elif p == 'typewriter':
        full = text or sub(a.get('final', ''))
        step = max(1, math.ceil(len(full)/20))
        raw = [full[:i] + '▌' for i in range(step, len(full)+1, step)] + [full]
    elif p == 'matrix':
        alphabet = '01ｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉﾊﾋﾌﾍﾎﾏﾐﾑﾒﾓ'
        raw = ['\n'.join(''.join(' ' if random.randrange(4) == 0 else random.choice(alphabet) for _ in range(16)) for _ in range(5)) + ('\n' + text if text.strip() else '') for _ in range(6 * loops)]
    elif p == 'slot': raw = ['🎰 | ' + ' '.join(random.choice(['🍒','🍋','🔔','⭐','💎','7️⃣']) for _ in range(3)) + ' |' for _ in range(8)]
    elif p in ('moon', 'clock', 'heart'):
        symbols = {'moon':['🌑','🌒','🌓','🌔','🌕','🌖','🌗','🌘'], 'clock':['🕐','🕑','🕒','🕓','🕔','🕕','🕖','🕗','🕘','🕙','🕚','🕛'], 'heart':['❤️','🧡','💛','💚','💙','💜']}[p]
        raw = [f'{c} {text}'.strip() for c in symbols] * loops
    else:
        chunks, current = [], []
        for line in a.get('frames', '').split('\n'):
            if line.strip() == '---':
                part = '\n'.join(current).rstrip('\n')
                if part.strip(): chunks.append(sub(part))
                current = []
            else: current.append(line)
        part = '\n'.join(current).rstrip('\n')
        if part.strip(): chunks.append(sub(part))
        raw = chunks * loops
    out = []
    for frame in raw:
        frame = frame[:3500]
        if frame.strip() and (not out or frame != out[-1]): out.append(frame)
        if len(out) == 60: break
    return out
