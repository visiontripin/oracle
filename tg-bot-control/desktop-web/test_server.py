import threading
import http.client
import subprocess
import sys
from http.server import ThreadingHTTPServer
import time
import unittest
from server import Runtime, Handler
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

    def test_public_bind_rejected(self):
        result = subprocess.run([sys.executable, 'server.py', '--host', '0.0.0.0', '--no-browser'], capture_output=True, text=True, timeout=5)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('private Wi-Fi', result.stderr)

if __name__ == '__main__': unittest.main()
