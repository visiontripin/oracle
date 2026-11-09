"""Local BotControl editor and Telegram polling runtime; no external dependencies."""
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import argparse
import json
import re
import random
from collections import deque
from datetime import datetime
import ipaddress
import sys
import threading
import time
import urllib.error
import urllib.request
from urllib.parse import urlsplit
import webbrowser
from animations import frames, validate_animation
from android_import import extract_lists

ROOT = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))


def compile_bot(bot):
    if not isinstance(bot, dict) or not isinstance(bot.get('buttons'), list) or not isinstance(bot.get('scenarios'), list):
        raise ValueError('Некорректные данные бота.')
    scenarios = {}
    triggers = []
    seen_commands = set()
    for s in bot['scenarios']:
        if not isinstance(s, dict) or not isinstance(s.get('id'), str):
            raise ValueError('Некорректный сценарий.')
        if s.get('animation') is not None:
            validate_animation(s['animation'])
            scenarios[s['id']] = {'animation': s['animation']}
        elif s.get('replies') is not None:
            replies = s['replies']
            if not isinstance(replies, list) or not replies or len(replies) > 500 or any(not isinstance(x, str) or not x.strip() or len(x) > 4096 for x in replies):
                raise ValueError('Набор должен содержать от 1 до 500 непустых ответов до 4096 символов.')
            scenarios[s['id']] = {'replies': replies}
        else:
            text = s.get('text')
            if not isinstance(text, str): raise ValueError('Некорректный ответ.')
            scenarios[s['id']] = {'text': text}
        scenarios[s['id']]['name'] = s.get('name') or ''
        links = s.get('links', [])
        if not isinstance(links, list) or len(links) > 3:
            raise ValueError('Можно добавить не более трёх ссылок под сообщением.')
        inline = []
        for link in links:
            if not isinstance(link, dict) or not isinstance(link.get('label'), str) or not link['label'].strip() or len(link['label']) > 100 or not isinstance(link.get('url'), str) or len(link['url']) > 2048:
                raise ValueError('Заполните название и URL кнопки-ссылки.')
            try: parsed = urlsplit(link['url'])
            except ValueError: raise ValueError('Неверный URL кнопки.') from None
            if parsed.scheme not in ('http', 'https') or not parsed.hostname or parsed.username or parsed.password:
                raise ValueError('Кнопка-ссылка должна содержать URL http или https без учётных данных.')
            inline.append({'text': link['label'], 'url': link['url']})
        if inline: scenarios[s['id']]['inline'] = {'inline_keyboard': [inline]}
        trigger = s.get('trigger')
        if trigger is not None:
            if not isinstance(trigger, dict) or trigger.get('type') not in ('command', 'contains') or not isinstance(trigger.get('value'), str):
                raise ValueError('Некорректное условие сценария.')
            kind, value = trigger['type'], trigger['value'].strip()
            if not value or len(value) > 200:
                raise ValueError('Укажите команду или фразу длиной до 200 символов.')
            if kind == 'command':
                if not re.fullmatch(r'/[a-zA-Z0-9_]{1,32}', value):
                    raise ValueError('Команда должна начинаться с / и содержать до 32 латинских букв, цифр или _.')
                if value.lower() in seen_commands: raise ValueError('Команды не должны повторяться.')
                seen_commands.add(value.lower())
            action = scenarios[s['id']]
            if 'text' in action and (not action['text'].strip() or len(action['text']) > 4096):
                raise ValueError('Заполните ответ для сценария с условием (до 4096 символов).')
            triggers.append((kind, value.casefold() if kind == 'command' else value, action))
    buttons = [(b.get('label'), scenarios.get(b.get('scenarioId'))) for b in bot['buttons'] if isinstance(b, dict)]
    if any(not isinstance(label, str) or not label.strip() or len(label) > 100 or not isinstance(action, dict) or ('text' in action and (not action['text'].strip() or len(action['text']) > 4096)) for label, action in buttons):
        raise ValueError('Добавьте кнопки с непустыми названиями и сценариями (ответ до 4096 символов).')
    schedule = bot.get('schedule', [])
    if not isinstance(schedule, list) or len(schedule) > 100: raise ValueError('Недопустимое расписание.')
    events = []
    for item in schedule:
        if not isinstance(item, dict) or not isinstance(item.get('time'), str) or not re.fullmatch(r'(?:[01][0-9]|2[0-3]):[0-5][0-9]', item['time']):
            raise ValueError('Укажите время напоминания в формате ЧЧ:ММ.')
        chat = item.get('chatId')
        if not isinstance(chat, str) or not re.fullmatch(r'-?[0-9]{1,20}', chat) or int(chat) == 0:
            raise ValueError('Укажите числовой chat ID для напоминания.')
        days = item.get('days')
        if not isinstance(days, list) or not days or any(type(d) is not int or d < 0 or d > 6 for d in days):
            raise ValueError('Укажите дни напоминания.')
        action = scenarios.get(item.get('scenarioId'))
        if not action or ('text' in action and (not action['text'].strip() or len(action['text']) > 4096)):
            raise ValueError('Укажите непустой сценарий для напоминания.')
        events.append((item['time'], int(chat), tuple(days), action))
    if not buttons and not triggers and not events:
        raise ValueError('Добавьте кнопку, условие сценария или напоминание.')
    if len({label.casefold() for label, _ in buttons}) != len(buttons):
        raise ValueError('Названия кнопок должны быть уникальными.')
    if len(buttons) > 100:
        raise ValueError('Допускается не более 100 кнопок.')

    return buttons, triggers, events


def phrase_hits(value, text):
    """Фраза-триггер срабатывает по целым словам (без учёта регистра):
    «кот» не срабатывает на «скот», «привет» — на «приветствую».
    Фразы без букв и цифр (смайлы, «!!!») ищутся как подстрока."""
    if any(ch.isalnum() for ch in value):
        return re.search(r"(?<!\w)" + re.escape(value) + r"(?!\w)", text, re.IGNORECASE) is not None
    return value.casefold() in text.casefold()


def match_action(text, buttons, triggers):
    first = text.split()[0].split("@")[0].casefold() if text.split() else ""
    action = next((a for kind, value, a in triggers if kind == "command" and value == first), None)
    if action is None: action = next((a for label, a in buttons if label.casefold() == text.casefold()), None)
    if action is None: action = next((a for kind, value, a in triggers if kind == "contains" and phrase_hits(value, text)), None)
    return first == "/start", action


def action_text(action):
    return random.choice(action["replies"]) if "replies" in action else action.get("text", "")


class TgError(RuntimeError):
    """Ошибка Telegram API с понятной причиной (токен в текст не попадает).
    kind: 'token' — Telegram не принял токен; 'offline' — нет сети;
    'api' — прочие ошибки API (в message — description из ответа)."""
    def __init__(self, message, kind='api', code=None):
        super().__init__(message)
        self.kind = kind
        self.code = code


def reason(exc):
    """Причина ошибки для журнала."""
    if isinstance(exc, TgError):
        return str(exc) if str(exc) else exc.kind
    return exc.__class__.__name__


def connect_error(exc):
    """Ошибка первого подключения: различаем токен и сеть."""
    if isinstance(exc, TgError):
        if exc.kind == 'token':
            return 'Токен не принят Telegram. Проверьте токен в @BotFather.'
        if exc.kind == 'offline':
            return 'Нет связи с api.telegram.org. Проверьте интернет.'
        return f'Не удалось подключить бота: {exc}'
    return 'Не удалось подключить бота. Проверьте токен и интернет.'


def poll_error(exc):
    """Ошибка в цикле опроса: не путаем её со сбоями отправки."""
    if isinstance(exc, TgError):
        if exc.kind == 'offline':
            return 'Пропала связь с Telegram. Повторная попытка через 5 секунд.'
        return f'Ошибка Telegram: {exc}. Повторная попытка через 5 секунд.'
    return 'Ошибка связи с Telegram. Повторная попытка через 5 секунд. Проверьте сеть и убедитесь, что бот не запущен в другом месте.'


def schedule_due(now, events, sent, window=10):
    """Индексы событий, которые пора отправить: точное время + окно допуска
    window минут (пропущенный тик не теряет событие), один раз в день."""
    due = []
    now_min = now.hour * 60 + now.minute
    today = now.strftime('%Y-%m-%d')
    for index, (time_label, chat, days, action) in enumerate(events):
        hh, _, mm = time_label.partition(':')
        sched = int(hh) * 60 + int(mm)
        if now.weekday() not in days or not 0 <= now_min - sched < window:
            continue
        if sent.get(index) == today:
            continue
        due.append(index)
    return due


class Runtime:
    def __init__(self, request=None):
        self.lock = threading.Lock()
        self.stop_event = threading.Event()
        self.thread = None
        self.state = 'stopped'
        self.error = ''
        self.request = request or self.telegram
        self.animation_slots = threading.BoundedSemaphore(5)
        self.logs = deque(maxlen=100)
        self._last = None  # (token, bot) последнего запуска — для «Перезапустить»

    @staticmethod
    def telegram(token, method, payload):
        request = urllib.request.Request('https://api.telegram.org/bot' + token + '/' + method,
            data=json.dumps(payload).encode(), headers={'Content-Type': 'application/json'})
        try:
            with urllib.request.urlopen(request, timeout=35) as response:
                result = json.load(response)
        except urllib.error.HTTPError as exc:
            description = ''
            try:
                body = json.load(exc)
                description = str(body.get('description') or '')
            except Exception:
                pass
            kind = 'token' if exc.code in (401, 404) else 'api'
            raise TgError(description or f'HTTP {exc.code}', kind, exc.code) from None
        except (urllib.error.URLError, OSError, TimeoutError):
            raise TgError('нет сети', 'offline') from None
        if not result.get('ok'):
            raise TgError(str(result.get('description') or 'Telegram API error'), 'api')
        return result['result']

    def status(self):
        with self.lock:
            return {'state': self.state, 'error': self.error, 'logs': list(self.logs)}

    def log(self, message):
        with self.lock:
            self.logs.append(datetime.now().strftime('%H:%M:%S') + ' ' + message)

    def start(self, token, bot):
        if not isinstance(token, str) or not token or len(token) > 200 or any(c not in '0123456789:ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz_-' for c in token):
            raise ValueError('Укажите корректный токен бота.')
        buttons, triggers, events = compile_bot(bot)
        with self.lock:
            if self.thread and self.thread.is_alive():
                raise ValueError('Бот уже запущен или останавливается.')
            self.stop_event = threading.Event()
            self.state, self.error = 'starting', ''
            self._last = (token, bot)
            self.logs.append(datetime.now().strftime('%H:%M:%S') + ' Запуск бота')
            self.thread = threading.Thread(target=self.run, args=(token, buttons, triggers, events, self.stop_event), daemon=True)
            self.thread.start()

    def stop(self):
        with self.lock:
            if self.thread and self.thread.is_alive():
                self.stop_event.set()
                self.state = 'stopping'
                self.logs.append(datetime.now().strftime('%H:%M:%S') + ' Остановка бота')

    def restart(self, token=None, bot=None):
        """Перезапуск одной кнопкой: остановить, дождаться и запустить снова.
        Пустой токен = токен из памяти последнего запуска (не сохраняется на диск)."""
        self.stop()
        deadline = time.time() + 10
        while time.time() < deadline:
            with self.lock:
                if not (self.thread and self.thread.is_alive()):
                    break
            time.sleep(0.2)
        with self.lock:
            if self.thread and self.thread.is_alive():
                raise ValueError('Бот ещё останавливается — попробуйте через несколько секунд.')
        if not token and self._last:
            token, bot = self._last
        self.start(token, bot)

    def run(self, token, buttons, triggers, events, stop):
        try:
            self.request(token, 'getMe', {})
            with self.lock:
                if not stop.is_set(): self.state = 'running'
            if events:
                threading.Thread(target=self.schedule_loop, args=(token, events, stop), daemon=True).start()
            offset = None
            keyboard = {'keyboard': [[{'text': label}] for label, _ in buttons], 'resize_keyboard': True}
            while not stop.is_set():
                try:
                    payload = {'timeout': 5, 'allowed_updates': ['message']}
                    if offset is not None: payload['offset'] = offset
                    updates = self.request(token, 'getUpdates', payload)
                    for update in updates:
                        try:
                            self.handle_update(token, update, buttons, triggers, keyboard, stop)
                        except Exception as exc:
                            # Сбой ответа ОДНОМУ пользователю не должен терять
                            # остальной пакет сообщений (W1): пишем причину и идём дальше.
                            chat_id = update.get('message', {}).get('chat', {}).get('id', '?')
                            self.log(f'Не удалось ответить · chat ID {chat_id}: {reason(exc)}')
                    if updates:
                        # offset двигаем только после полной обработки пакета
                        offset = max(u['update_id'] for u in updates) + 1
                    with self.lock: self.error = ''
                except Exception as exc:
                    with self.lock: self.error = poll_error(exc)
                    stop.wait(5)
        except Exception as exc:
            with self.lock: self.error = connect_error(exc)
        finally:
            with self.lock: self.state = 'stopped'

    def handle_update(self, token, update, buttons, triggers, keyboard, stop):
        """Одно входящее сообщение: /chatid, /start, команды, кнопки, фразы."""
        message = update.get('message', {})
        chat = message.get('chat', {})
        text = message.get('text', '')
        if not chat.get('id') or not isinstance(text, str): return
        chat_id = chat['id']
        self.log(f"Получено сообщение · chat ID {chat_id}")
        stripped = text.strip()
        if stripped and stripped.split()[0].split("@")[0].casefold() == '/chatid':
            self.request(token, 'sendMessage', {'chat_id': chat_id, 'text': f'Ваш chat ID: {chat_id}'})
            self.log(f'Команда /chatid · chat ID {chat_id}')
            return
        is_start, action = match_action(text, buttons, triggers)
        if is_start and action is None and buttons:
            self.request(token, 'sendMessage', {'chat_id': chat_id, 'text': 'Выберите кнопку:', 'reply_markup': keyboard})
            return
        if action is None:
            self.log('Подходящий сценарий не найден')
            return
        name = action.get('name') or ''
        self.log(f'Найден сценарий «{name}»' if name else 'Найден сценарий')
        inline = action.get('inline')
        if is_start and buttons and inline:
            self.request(token, 'sendMessage', {'chat_id': chat_id, 'text': 'Выберите кнопку:', 'reply_markup': keyboard})
        markup = inline or (keyboard if is_start and buttons else None)
        if 'animation' in action:
            if self.animation_slots.acquire(blocking=False):
                user = message.get('from', {}).get('first_name', '')
                threading.Thread(target=self.animate, args=(token, chat_id, action['animation'], user, stop, markup), daemon=True).start()
            else:
                self.request(token, 'sendMessage', {'chat_id': chat_id, 'text': 'Слишком много анимаций, попробуйте позже.'})
        else:
            body = {'chat_id': chat_id, 'text': action_text(action)}
            if markup: body['reply_markup'] = markup
            self.request(token, 'sendMessage', body)

    def schedule_loop(self, token, events, stop):
        sent = {}
        while not stop.is_set():
            now = datetime.now()
            for index in schedule_due(now, events, sent):
                time_label, chat, days, action = events[index]
                try:
                    if 'animation' in action:
                        if self.animation_slots.acquire(blocking=False):
                            threading.Thread(target=self.animate, args=(token, chat, action['animation'], '', stop, action.get('inline')), daemon=True).start()
                        else: self.log('Напоминание пропущено: много анимаций')
                    else:
                        body = {'chat_id': chat, 'text': action_text(action)}
                        if action.get('inline'): body['reply_markup'] = action['inline']
                        self.request(token, 'sendMessage', body)
                    # Помечаем отправленным ТОЛЬКО после успеха: при сбое
                    # отправка повторится в окне допуска (W8).
                    sent[index] = now.strftime('%Y-%m-%d')
                    self.log('Выполнено напоминание')
                except Exception as exc:
                    self.log(f'Ошибка отправки напоминания ({reason(exc)}); повтор в окне допуска')
            stop.wait(5)

    def animate(self, token, chat_id, spec, user, stop, markup=None):
        try:
            sequence = frames(spec, user if isinstance(user, str) else '')
            final = spec.get('final', '').replace('{user}', user if isinstance(user, str) else '')
            if not final and spec['preset'] == 'countdown': final = '🚀'
            if final.strip() and final != sequence[-1]: sequence.append(final[:3500])
            if stop.is_set(): return
            body = {'chat_id': chat_id, 'text': sequence[0]}
            if markup and ('inline_keyboard' not in markup or len(sequence) == 1): body['reply_markup'] = markup
            sent = self.request(token, 'sendMessage', body)
            for index, frame in enumerate(sequence[1:], start=1):
                if stop.wait(spec.get('interval', 700) / 1000): break
                edit = {'chat_id': chat_id, 'message_id': sent['message_id'], 'text': frame}
                if markup and 'inline_keyboard' in markup and index == len(sequence) - 1: edit['reply_markup'] = markup
                self.request(token, 'editMessageText', edit)
        except Exception:
            with self.lock: self.error = 'Не удалось воспроизвести анимацию. Проверьте связь и ограничения Telegram.'
        finally:
            self.animation_slots.release()


runtime = Runtime()


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(ROOT), **kwargs)

    def valid_host(self):
        host = self.headers.get('Host', '')
        try:
            parsed = urlsplit('//' + host)
            name = (parsed.hostname or '').lower()
            port = parsed.port
        except ValueError:
            return False
        if port is not None and port != self.server.server_port:
            return False
        # localhost и IPv6-петля теперь тоже работают (W3)
        allowed = {'127.0.0.1', 'localhost', '::1'}
        bind = str(self.server.server_address[0])
        if bind and bind != '0.0.0.0':
            allowed.add(bind)
        return name in allowed

    def send_error(self, code, message=None, explain=None):
        if code == 403:
            extra = ('BotControl — локальное приложение. Откройте '
                     'http://127.0.0.1:%d/ напрямую.' % self.server.server_port)
            explain = (explain + ' ' if explain else '') + extra
        super().send_error(code, message, explain)

    def send_json(self, code, data):
        raw = json.dumps(data, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Cache-Control', 'no-store')
        self.send_header('Content-Length', str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        if not self.valid_host(): return self.send_error(403)
        if self.path == '/api/status': return self.send_json(200, runtime.status())
        if self.path not in ('/', '/index.html', '/style.css', '/app.js', '/generated.js'):
            return self.send_error(404)
        return super().do_GET()

    def do_POST(self):
        origin = f'http://{self.server.server_address[0]}:{self.server.server_port}'
        if not self.valid_host() or self.headers.get('Origin') != origin or self.headers.get('Content-Type') != 'application/json':
            return self.send_error(403)
        try:
            length = int(self.headers.get('Content-Length', '0'))
            if not 0 < length <= 1024 * 1024: raise ValueError('Размер запроса недопустим.')
            data = json.loads(self.rfile.read(length))
            if not isinstance(data, dict): raise ValueError('Некорректный запрос.')
            if self.path == '/api/start': runtime.start(data.get('token'), data.get('bot'))
            elif self.path == '/api/stop': runtime.stop()
            elif self.path == '/api/restart': runtime.restart(data.get('token'), data.get('bot'))
            elif self.path == '/api/validate-bot':
                compile_bot(data.get('bot'))
                return self.send_json(200, {'ok': True})
            elif self.path == '/api/import-python':
                return self.send_json(200, {'packs': extract_lists(data.get('source'))})
            elif self.path == '/api/simulate':
                text = data.get('text')
                if not isinstance(text, str) or len(text) > 4096: raise ValueError('Введите сообщение до 4096 символов.')
                buttons, triggers, _ = compile_bot(data.get('bot'))
                is_start, selected = match_action(text, buttons, triggers)
                if selected is None:
                    return self.send_json(200, {'result': 'Показать клавиатуру' if is_start and buttons else 'Подходящий сценарий не найден.'})
                if 'animation' in selected:
                    result = 'Анимация: ' + selected['animation']['preset'] + '\nПервый кадр: ' + frames(selected['animation'], 'Пользователь')[0]
                else: result = action_text(selected)
                return self.send_json(200, {'result': result})
            else: return self.send_error(404)
            self.send_json(200, runtime.status())
        except (ValueError, TypeError, json.JSONDecodeError) as exc:
            self.send_json(400, {'error': str(exc)})


def main():
    parser = argparse.ArgumentParser(description='Local BotControl')
    parser.add_argument('--port', type=int, default=8765)
    parser.add_argument('--host', default='127.0.0.1', help='Local IPv4 address; use a private Wi-Fi address for LAN access')
    parser.add_argument('--no-browser', action='store_true')
    args = parser.parse_args()
    try:
        address = ipaddress.IPv4Address(args.host)
    except ipaddress.AddressValueError:
        parser.error('--host must be an IPv4 address')
    allowed = [ipaddress.IPv4Network(net) for net in ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16')]
    if address != ipaddress.IPv4Address('127.0.0.1') and not any(address in net for net in allowed):
        parser.error('--host must be 127.0.0.1 or a private Wi-Fi IPv4 address')
    try:
        server = ThreadingHTTPServer((str(address), args.port), Handler)
    except OSError as exc:
        parser.exit(1, f'Не удалось запустить сервер: {exc}\n'
            f'Порт {args.port} занят? Скорее всего, BotControl уже запущен — '
            f'закройте старый экземпляр (консоль или окно программы).\n')
    url = f'http://{address}:{server.server_port}/'
    if address != ipaddress.IPv4Address('127.0.0.1'):
        print('WARNING: LAN mode has no authentication or encryption. Use only a trusted network.', flush=True)
    print(f'BotControl: {url} (Ctrl+C для остановки)', flush=True)
    if not args.no_browser: webbrowser.open(url)
    try: server.serve_forever()
    except KeyboardInterrupt: pass
    finally:
        runtime.stop()
        server.server_close()

if __name__ == '__main__': main()
