import threading
import json
from unittest.mock import patch
from datetime import datetime
import http.client
import subprocess
import sys
from http.server import ThreadingHTTPServer
import time
import unittest
from server import Runtime, Handler, compile_bot, action_text, match_action, connect_error, TgError
from android_import import extract_lists
from animations import PRESETS, frames, validate_animation


class RuntimeTests(unittest.TestCase):
    def test_poll_and_reply(self):
        sent = []
        finished = threading.Event()
        def request(token, method, payload):
            if method == 'getMe': return {'id': 1}
            if method == 'getUpdates':
                if not sent: return [{'update_id': 1, 'message': {'chat': {'id': 12}, 'text': 'Привет'}}]
                finished.set()
                time.sleep(.01)
                return []
            if method == 'sendMessage': sent.append(payload); return {}
        r = Runtime(request)
        r.start('123:ABC', {'scenarios': [{'id': 'a', 'text': 'Ответ'}], 'buttons': [{'label': 'Привет', 'scenarioId': 'a'}]})
        self.assertTrue(finished.wait(2))
        r.stop()
        r.thread.join(2)
        self.assertEqual(sent[0]['text'], 'Ответ')
        self.assertEqual(r.status()['state'], 'stopped')

    def test_validation(self):
        r = Runtime()
        with self.assertRaises(ValueError): r.start('token', {'scenarios': [], 'buttons': []})
        with self.assertRaises(ValueError): r.start('bad/token', {'scenarios': [], 'buttons': []})
        with self.assertRaises(ValueError): r.start('123:abc', {'scenarios': [{'id':'1','text':'x'}], 'buttons':[{'label':'a','scenarioId':'1'},{'label':'a','scenarioId':'1'}]})

    def test_failure_does_not_leak_token(self):
        def fail(*args): raise RuntimeError('secret token')
        r = Runtime(fail)
        r.start('123:secret', {'scenarios': [{'id':'1','text':'x'}], 'buttons':[{'label':'a','scenarioId':'1'}]})
        r.thread.join(2)
        self.assertEqual(r.status()['state'], 'stopped')
        self.assertNotIn('secret', r.status()['error'])



class TriggerTests(unittest.TestCase):
    def test_commands_phrases_buttons_and_start(self):
        sent, completed = [], threading.Event()
        messages = ['/start', '/HELP@mybot extra', 'Привет МИР', 'нажми']
        def request(token, method, payload):
            if method == 'getMe': return {'id': 1}
            if method == 'getUpdates':
                if not sent:
                    return [{'update_id': i + 1, 'message': {'chat': {'id': 123}, 'text': text}} for i, text in enumerate(messages)]
                completed.set(); time.sleep(.01); return []
            if method == 'sendMessage': sent.append(payload); return {}
        bot = {'scenarios': [
            {'id':'start', 'text':'Добро пожаловать', 'trigger':{'type':'command','value':'/start'}},
            {'id':'help', 'text':'Помощь', 'trigger':{'type':'command','value':'/help'}},
            {'id':'phrase', 'text':'На фразу', 'trigger':{'type':'contains','value':'мир'}},
            {'id':'button', 'text':'На кнопку'}],
            'buttons':[{'label':'Нажми','scenarioId':'button'}]}
        runtime = Runtime(request)
        runtime.start('123:ABC', bot)
        self.assertTrue(completed.wait(2))
        runtime.stop(); runtime.thread.join(2)
        self.assertEqual([p['text'] for p in sent], ['Добро пожаловать','Помощь','На фразу','На кнопку'])
        self.assertEqual(sent[0]['reply_markup']['keyboard'][0][0]['text'], 'Нажми')

    def test_invalid_and_duplicate_command(self):
        runtime = Runtime()
        for trigger in [{'type':'command','value':'help'}, {'type':'command','value':'/привет'}, {'type':'contains','value':''}]:
            with self.assertRaises(ValueError):
                runtime.start('123:ABC', {'scenarios':[{'id':'1','text':'OK','trigger':trigger}], 'buttons':[]})
        bot = {'scenarios':[{'id':'1','text':'a','trigger':{'type':'command','value':'/go'}}, {'id':'2','text':'b','trigger':{'type':'command','value':'/GO'}}], 'buttons':[]}
        with self.assertRaises(ValueError): runtime.start('123:ABC', bot)

    def test_command_without_keyboard(self):
        runtime = Runtime(lambda *args: (_ for _ in ()).throw(RuntimeError()))
        runtime.start('123:ABC', {'scenarios':[{'id':'1','text':'OK','trigger':{'type':'command','value':'/go'}}], 'buttons':[]})
        runtime.thread.join(2)
        self.assertEqual(runtime.status()['state'], 'stopped')


class NewFeaturesTests(unittest.TestCase):
    def test_pack_and_inline(self):
        bot = {'scenarios': [{'id':'1','text':'','replies':['один','два'], 'links':[{'label':'Сайт','url':'https://example.com'}], 'trigger':{'type':'command','value':'/go'}}], 'buttons':[]}
        _, triggers, _ = compile_bot(bot)
        action = triggers[0][2]
        self.assertIn(action_text(action), ['один','два'])
        self.assertEqual(action['inline']['inline_keyboard'][0][0]['url'], 'https://example.com')
        bot['scenarios'][0]['links'][0]['url'] = 'javascript:alert(1)'
        with self.assertRaises(ValueError): compile_bot(bot)
        bot['scenarios'][0]['links'] = []
        bot['scenarios'][0]['replies'] = []
        with self.assertRaises(ValueError): compile_bot(bot)

    def test_extract_python_only_literal_lists(self):
        source = "import os\nos.system('this must never run')\nMY_REPLIES = ['один', 'два']\nBOT_TOKEN = 'secret'\n"
        self.assertEqual(extract_lists(source), [{'name':'MY_REPLIES','items':['один','два']}])
        with self.assertRaises(ValueError): extract_lists('def broken(')
    def test_schedule_once_per_day(self):
        bot = {'scenarios':[{'id':'1','text':'Привет'}], 'buttons':[], 'schedule':[{'id':'a','time':'09:00','chatId':'123', 'days':[0], 'scenarioId':'1'}]}
        _, _, events = compile_bot(bot)
        sent = []
        runtime = Runtime(lambda token, method, payload: sent.append((method, payload)))
        class Stop:
            count = 0
            def is_set(self): return self.count >= 2
            def wait(self, timeout): self.count += 1; return False
        with patch('server.datetime') as clock:
            clock.now.return_value = datetime(2026, 11, 9, 9, 0)
            runtime.schedule_loop('123:abc', events, Stop())
        self.assertEqual(len(sent), 1)
        self.assertEqual(sent[0][1]['chat_id'], 123)
        self.assertEqual(sent[0][1]['text'], 'Привет')
        bot['schedule'][0]['time'] = '25:00'
        with self.assertRaises(ValueError): compile_bot(bot)


class AnimationTests(unittest.TestCase):
    def spec(self, preset):
        return {'preset': preset, 'text': 'Тест {user}', 'interval': 500, 'loops': 1, 'count': 3, 'final': 'Готово', 'frames': 'Один\n---\nДва'}

    def test_all_android_text_presets(self):
        for preset in PRESETS:
            with self.subTest(preset=preset):
                spec = self.spec(preset)
                validate_animation(spec)
                result = frames(spec, 'Ира')
                self.assertTrue(result)
                self.assertLessEqual(len(result), 60)
                self.assertTrue(all(x.strip() for x in result))
        self.assertEqual(frames(self.spec('custom')), ['Один', 'Два'])
        self.assertEqual(frames(self.spec('countdown'), 'Ира')[0], 'Тест Ира\n\n⏳ 3')

    def test_invalid_animation(self):
        for patch in ({'preset': 'unknown'}, {'interval': 20}, {'loops': True}, {'preset':'custom','frames':''}):
            spec = self.spec('spinner'); spec.update(patch)
            with self.assertRaises(ValueError): validate_animation(spec)

    def test_send_and_edit_single_message(self):
        requests = []
        def request(token, method, payload):
            requests.append((method, payload))
            return {'message_id': 77}
        runtime = Runtime(request)
        runtime.animation_slots.acquire()
        spec = self.spec('custom')
        class Stop:
            def is_set(self): return False
            def wait(self, timeout): return False
        runtime.animate('123:abc', 42, spec, 'Ира', Stop())
        self.assertEqual([method for method, _ in requests], ['sendMessage', 'editMessageText', 'editMessageText'])
        self.assertEqual(requests[1][1]['message_id'], 77)
        self.assertEqual(requests[2][1]['text'], 'Готово')

    def test_legacy_bot_validates(self):
        runtime = Runtime(lambda *args: (_ for _ in ()).throw(RuntimeError()))
        runtime.start('123:abc', {'scenarios':[{'id':'s','name':'old','text':'Привет'}], 'buttons':[{'label':'Кнопка','scenarioId':'s'}]})
        runtime.thread.join(2)
        self.assertEqual(runtime.status()['state'], 'stopped')


class HttpTests(unittest.TestCase):
    def setUp(self):
        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.port = self.server.server_port

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def request(self, method, path, headers=None, body=None):
        connection = http.client.HTTPConnection('127.0.0.1', self.port, timeout=3)
        connection.request(method, path, body, headers or {})
        response = connection.getresponse()
        result = (response.status, response.read())
        connection.close()
        return result

    def test_assets_and_status(self):
        self.assertEqual(self.request('GET', '/')[0], 200)
        self.assertEqual(self.request('GET', '/app.js')[0], 200)
        self.assertEqual(self.request('GET', '/api/status')[0], 200)
        self.assertEqual(self.request('GET', '/server.py')[0], 404)

    def test_rejects_cross_origin_and_bad_host(self):
        headers = {'Content-Type': 'application/json', 'Origin': 'http://evil.example'}
        self.assertEqual(self.request('POST', '/api/stop', headers, '{}')[0], 403)
        self.assertEqual(self.request('GET', '/', {'Host': 'evil.example'})[0], 403)
        headers['Origin'] = f'http://127.0.0.1:{self.port}'
        self.assertEqual(self.request('POST', '/api/stop', headers, '{}')[0], 200)

    def test_simulation_and_python_import(self):
        headers = {'Content-Type': 'application/json', 'Origin': f'http://127.0.0.1:{self.port}'}
        bot = {'scenarios':[{'id':'s', 'text':'OK', 'trigger':{'type':'command','value':'/go'}}], 'buttons':[]}
        status, body = self.request('POST', '/api/simulate', headers, json.dumps({'text':'/GO','bot':bot}))
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)['result'], 'OK')
        status, body = self.request('POST', '/api/import-python', headers, json.dumps({'source':"HELLO = ['A', 'B']"}))
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)['packs'][0]['items'], ['A','B'])

    def test_validate_generated_bot(self):
        headers = {'Content-Type': 'application/json', 'Origin': f'http://127.0.0.1:{self.port}'}
        bot = {'format':'botcontrol', 'version':1, 'name':'Тест', 'scenarios':[{'id':'s','name':'Привет','text':'Привет!', 'trigger':{'type':'command','value':'/start'}}], 'buttons':[], 'schedule':[]}
        status, body = self.request('POST', '/api/validate-bot', headers, json.dumps({'bot':bot}))
        self.assertEqual(status, 200)
        self.assertTrue(json.loads(body)['ok'])
        bot['scenarios'][0]['trigger']['value'] = 'не команда'
        status, body = self.request('POST', '/api/validate-bot', headers, json.dumps({'bot':bot}))
        self.assertEqual(status, 400)
        self.assertIn('Команда', json.loads(body)['error'])

    def test_public_bind_rejected(self):
        result = subprocess.run([sys.executable, 'server.py', '--host', '0.0.0.0', '--no-browser'], capture_output=True, text=True, timeout=5)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('private Wi-Fi', result.stderr)

class FixTests(unittest.TestCase):
    """Регрессия исправлений из рецензии: W1–W8 и кнопка «Перезапустить»."""

    BOT = {'scenarios': [{'id': 'a', 'name': 'Привет', 'text': 'Ответ', 'trigger': {'type': 'command', 'value': '/start'}}], 'buttons': []}

    def test_failed_send_does_not_drop_batch(self):
        # W1: сбой отправки одному чату не теряет остальные сообщения пакета
        received = []
        def request(token, method, payload):
            if method == 'getMe': return {'id': 1}
            if method == 'getUpdates':
                if not received:
                    return [
                        {'update_id': 1, 'message': {'chat': {'id': 1}, 'text': '/start'}},
                        {'update_id': 2, 'message': {'chat': {'id': 2}, 'text': '/start'}},
                        {'update_id': 3, 'message': {'chat': {'id': 3}, 'text': '/start'}},
                    ]
                time.sleep(.01); return []
            if method == 'sendMessage':
                if payload['chat_id'] == 2: raise RuntimeError('403: blocked by user')
                received.append(payload); return {}
            return {}
        r = Runtime(request)
        r.start('123:ABC', self.BOT)
        time.sleep(1.0)
        self.assertEqual([p['chat_id'] for p in received], [1, 3])
        st = r.status()
        self.assertEqual(st['state'], 'running')
        self.assertNotIn('Ошибка связи', st['error'])
        self.assertTrue(any('chat ID 2' in line for line in st['logs']))
        r.stop(); r.thread.join(3)

    def test_stop_is_quick_and_poll_is_short(self):
        # W2: остановка не ждёт 20-секундный long poll
        payloads = []
        def request(token, method, payload):
            if method == 'getMe': return {'id': 1}
            if method == 'getUpdates': payloads.append(payload); time.sleep(0.5); return []
            return {}
        r = Runtime(request)
        r.start('123:ABC', self.BOT)
        time.sleep(0.3)
        t0 = time.time()
        r.stop()
        r.thread.join(6)
        self.assertLess(time.time() - t0, 5)
        self.assertTrue(payloads)
        self.assertTrue(all(p.get('timeout') == 5 for p in payloads))
        self.assertEqual(r.status()['state'], 'stopped')

    def test_restart_reuses_last_token(self):
        starts = []
        def request(token, method, payload):
            if method == 'getMe': starts.append(token); return {'id': 1}
            if method == 'getUpdates': time.sleep(0.1); return []
            return {}
        r = Runtime(request)
        r.start('123:ABC', self.BOT)
        time.sleep(0.5)
        r.restart(None, None)
        time.sleep(0.5)
        self.assertEqual(r.status()['state'], 'running')
        self.assertEqual(starts, ['123:ABC', '123:ABC'])
        r.stop(); r.thread.join(3)

    def test_phrase_matches_whole_words(self):
        # W5: «кот» не срабатывает на «скот», «привет» — на «приветствую»
        bot = {'scenarios': [
            {'id': '1', 'name': 'Кот', 'text': 'Мяу', 'trigger': {'type': 'contains', 'value': 'кот'}},
            {'id': '2', 'name': 'Привет', 'text': 'Здравствуй', 'trigger': {'type': 'contains', 'value': 'привет'}}],
            'buttons': []}
        buttons, triggers, _ = compile_bot(bot)
        self.assertIsNone(match_action('скот и стадо', buttons, triggers)[1])
        self.assertIsNone(match_action('приветствую', buttons, triggers)[1])
        self.assertEqual(match_action('у нас кот', buttons, triggers)[1]['text'], 'Мяу')
        self.assertEqual(match_action('Привет!', buttons, triggers)[1]['text'], 'Здравствуй')

    def test_error_messages_are_specific(self):
        # W4/W6: токен и сеть различаются; внутренние тексты не утекают
        self.assertIn('токен', connect_error(TgError('Unauthorized', 'token')).lower())
        self.assertIn('интернет', connect_error(TgError('нет сети', 'offline')).lower())
        self.assertEqual(connect_error(RuntimeError('secret token')),
                         'Не удалось подключить бота. Проверьте токен и интернет.')
        self.assertNotIn('secret', connect_error(RuntimeError('secret token')))

    def test_extract_lists_skips_frames_and_service(self):
        # W7: кадры анимаций и служебные списки не выдаются за ответы
        source = ("INTRO_FRAMES = ['photo: https://x/y.png — подпись', 'photo: https://x/z.png']\n"
                  "ANIM_START = ['кадр один', 'кадр два']\n"
                  "SCHEDULE = ['a', 'b']\n"
                  "PHOTO_PACK = ['photo: https://x/1.png', 'photo: https://x/2.png']\n"
                  "GOOD = ['один', 'два']\n")
        self.assertEqual(extract_lists(source), [{'name': 'GOOD', 'items': ['один', 'два']}])

    def test_schedule_catchup_window(self):
        # W8: событие отрабатывает в окне допуска 10 минут, один раз в день
        bot = {'scenarios': [{'id': '1', 'text': 'Привет'}], 'buttons': [],
               'schedule': [{'id': 'a', 'time': '09:00', 'chatId': '123', 'days': [0], 'scenarioId': '1'}]}
        _, _, events = compile_bot(bot)
        sent = []
        runtime = Runtime(lambda token, method, payload: sent.append((method, payload)))
        class Stop:
            count = 0
            def is_set(self): return self.count >= 2
            def wait(self, timeout): self.count += 1; return False
        with patch('server.datetime') as clock:
            clock.now.return_value = datetime(2026, 11, 9, 9, 7)  # на 7 минут позже
            runtime.schedule_loop('123:abc', events, Stop())
        self.assertEqual(len(sent), 1)
        # позже окна — не отправляется
        sent.clear()
        runtime = Runtime(lambda token, method, payload: sent.append((method, payload)))
        class Stop2:
            count = 0
            def is_set(self): return self.count >= 2
            def wait(self, timeout): self.count += 1; return False
        with patch('server.datetime') as clock:
            clock.now.return_value = datetime(2026, 11, 9, 9, 11)
            runtime.schedule_loop('123:abc', events, Stop2())
        self.assertEqual(len(sent), 0)

    def test_schedule_retries_after_failure(self):
        # W8: сбой отправки — повтор в окне допуска, событие не теряется
        calls = []
        def request(token, method, payload):
            calls.append(1)
            if len(calls) == 1: raise RuntimeError('сбой сети')
            return {}
        bot = {'scenarios': [{'id': '1', 'text': 'Привет'}], 'buttons': [],
               'schedule': [{'id': 'a', 'time': '09:00', 'chatId': '123', 'days': [0], 'scenarioId': '1'}]}
        _, _, events = compile_bot(bot)
        runtime = Runtime(request)
        class Stop:
            count = 0
            def is_set(self): return self.count >= 3
            def wait(self, timeout): self.count += 1; return False
        with patch('server.datetime') as clock:
            clock.now.return_value = datetime(2026, 11, 9, 9, 3)
            runtime.schedule_loop('123:abc', events, Stop())
        self.assertEqual(len(calls), 2)

    def test_localhost_host_accepted(self):
        # W3: http://localhost:<порт>/ работает, как и 127.0.0.1
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            for host in (f'127.0.0.1:{server.server_port}', f'localhost:{server.server_port}'):
                connection = http.client.HTTPConnection('127.0.0.1', server.server_port, timeout=3)
                connection.request('GET', '/', headers={'Host': host})
                self.assertEqual(connection.getresponse().status, 200)
                connection.close()
            connection = http.client.HTTPConnection('127.0.0.1', server.server_port, timeout=3)
            connection.request('GET', '/', headers={'Host': 'evil.example'})
            response = connection.getresponse()
            self.assertEqual(response.status, 403)
            self.assertIn('127.0.0.1', response.read().decode('utf-8', 'replace'))
            connection.close()
        finally:
            server.shutdown()
            server.server_close()
            thread.join()


if __name__ == '__main__': unittest.main()
