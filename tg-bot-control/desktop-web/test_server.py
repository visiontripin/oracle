import threading
import time
import unittest
from server import Runtime


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

if __name__ == '__main__': unittest.main()
