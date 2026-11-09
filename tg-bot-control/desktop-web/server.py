"""Local BotControl editor and Telegram polling runtime; no external dependencies."""
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import argparse
import json
import ipaddress
import sys
import threading
import urllib.request
import webbrowser
from animations import frames, validate_animation

ROOT = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))


class Runtime:
    def __init__(self, request=None):
        self.lock = threading.Lock()
        self.stop_event = threading.Event()
        self.thread = None
        self.state = 'stopped'
        self.error = ''
        self.request = request or self.telegram
        self.animation_slots = threading.BoundedSemaphore(5)

    @staticmethod
    def telegram(token, method, payload):
        request = urllib.request.Request('https://api.telegram.org/bot' + token + '/' + method,
            data=json.dumps(payload).encode(), headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(request, timeout=35) as response:
            result = json.load(response)
        if not result.get('ok'):
            raise ValueError('Telegram API error')
        return result['result']

    def status(self):
        with self.lock:
            return {'state': self.state, 'error': self.error}

    def start(self, token, bot):
        if not isinstance(token, str) or not token or len(token) > 200 or any(c not in '0123456789:ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz_-' for c in token):
            raise ValueError('Укажите корректный токен бота.')
        if not isinstance(bot, dict) or not isinstance(bot.get('buttons'), list) or not isinstance(bot.get('scenarios'), list):
            raise ValueError('Некорректные данные бота.')
        scenarios = {}
        for s in bot['scenarios']:
            if not isinstance(s, dict) or not isinstance(s.get('id'), str):
                raise ValueError('Некорректный сценарий.')
            if s.get('animation') is not None:
                validate_animation(s['animation'])
                scenarios[s['id']] = {'animation': s['animation']}
            else:
                text = s.get('text')
                if not isinstance(text, str): raise ValueError('Некорректный ответ.')
                scenarios[s['id']] = {'text': text}
        buttons = [(b.get('label'), scenarios.get(b.get('scenarioId'))) for b in bot['buttons'] if isinstance(b, dict)]
        if not buttons or any(not isinstance(label, str) or not label.strip() or len(label) > 100 or not isinstance(action, dict) or ('text' in action and (not action['text'].strip() or len(action['text']) > 4096)) for label, action in buttons):
            raise ValueError('Добавьте кнопки с непустыми названиями и сценариями (ответ до 4096 символов).')
        if len({label for label, _ in buttons}) != len(buttons):
            raise ValueError('Названия кнопок должны быть уникальными.')
        if len(buttons) > 100:
            raise ValueError('Допускается не более 100 кнопок.')
        with self.lock:
            if self.thread and self.thread.is_alive():
                raise ValueError('Бот уже запущен или останавливается.')
            self.stop_event = threading.Event()
            self.state, self.error = 'starting', ''
            self.thread = threading.Thread(target=self.run, args=(token, buttons, self.stop_event), daemon=True)
            self.thread.start()

    def stop(self):
        with self.lock:
            if self.thread and self.thread.is_alive():
                self.stop_event.set()
                self.state = 'stopping'

    def run(self, token, buttons, stop):
        try:
            self.request(token, 'getMe', {})
            with self.lock:
                if not stop.is_set(): self.state = 'running'
            offset = None
            keyboard = {'keyboard': [[{'text': label}] for label, _ in buttons], 'resize_keyboard': True}
            replies = dict(buttons)
            while not stop.is_set():
                try:
                    payload = {'timeout': 20, 'allowed_updates': ['message']}
                    if offset is not None: payload['offset'] = offset
                    updates = self.request(token, 'getUpdates', payload)
                    for update in updates:
                        offset = update['update_id'] + 1
                        message = update.get('message', {})
                        chat = message.get('chat', {})
                        text = message.get('text', '')
                        if not chat.get('id') or not isinstance(text, str): continue
                        if text.split(' ', 1)[0].split('@')[0] == '/start':
                            self.request(token, 'sendMessage', {'chat_id': chat['id'], 'text': 'Выберите кнопку:', 'reply_markup': keyboard})
                        elif text in replies:
                            action = replies[text]
                            if 'animation' in action:
                                if self.animation_slots.acquire(blocking=False):
                                    user = message.get('from', {}).get('first_name', '')
                                    threading.Thread(target=self.animate, args=(token, chat['id'], action['animation'], user, stop), daemon=True).start()
                                else:
                                    self.request(token, 'sendMessage', {'chat_id': chat['id'], 'text': 'Слишком много анимаций, попробуйте позже.'})
                            else:
                                self.request(token, 'sendMessage', {'chat_id': chat['id'], 'text': action['text']})
                    with self.lock: self.error = ''
                except Exception:
                    with self.lock: self.error = 'Ошибка связи с Telegram. Повторная попытка через 5 секунд. Проверьте сеть и убедитесь, что бот не запущен в другом месте.'
                    stop.wait(5)
        except Exception:
            with self.lock: self.error = 'Не удалось подключить бота. Проверьте токен и интернет.'
        finally:
            with self.lock: self.state = 'stopped'


    def animate(self, token, chat_id, spec, user, stop):
        try:
            sequence = frames(spec, user if isinstance(user, str) else '')
            final = spec.get('final', '').replace('{user}', user if isinstance(user, str) else '')
            if not final and spec['preset'] == 'countdown': final = '🚀'
            if final.strip() and final != sequence[-1]: sequence.append(final[:3500])
            if stop.is_set(): return
            sent = self.request(token, 'sendMessage', {'chat_id': chat_id, 'text': sequence[0]})
            for frame in sequence[1:]:
                if stop.wait(spec.get('interval', 700) / 1000): break
                self.request(token, 'editMessageText', {'chat_id': chat_id, 'message_id': sent['message_id'], 'text': frame})
        except Exception:
            with self.lock: self.error = 'Не удалось воспроизвести анимацию. Проверьте связь и ограничения Telegram.'
        finally:
            self.animation_slots.release()


runtime = Runtime()


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(ROOT), **kwargs)

    def valid_host(self):
        return self.headers.get('Host') == f'{self.server.server_address[0]}:{self.server.server_port}'

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
        if self.path not in ('/', '/index.html', '/style.css', '/app.js'):
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
        parser.exit(1, f'Cannot start server: {exc}\n')
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
