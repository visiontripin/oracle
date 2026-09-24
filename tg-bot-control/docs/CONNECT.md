# Подключение Android-приложения к боту

## 1. Запусти сервер

```bash
cp .env.example .env
# отредактируй .env: SECRET_KEY (32+ символов), BOT_TOKEN, ADMIN_PASSWORD
./scripts/run_server.sh
# или: cd server && uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Проверка: `curl http://localhost:8000/health` → `{"ok": true, ...}`.

## 2. Выбери канал связи

| Вариант | Когда | Base URL в приложении |
|---|---|---|
| Та же Wi-Fi сеть | дом/офис, быстро | `http://192.168.x.x:8000` |
| Tailscale | сервер дома/Termux, без публичного доступа (рекомендуется) | `http://<tailnet-name>:8000` |
| Cloudflare Tunnel | нужен доступ из любого интернета | `https://xxx.trycloudflare.com` |
| VPS + домен + nginx | продакшн | `https://bot.example.com` |

### Tailscale (приватно, без открытого порта)

```bash
# на сервере и на телефоне — один аккаунт Tailscale
tailscale up
tailscale status   # узнай имя хоста, напр. homeserver
```

В приложении: `http://homeserver:8000`. Трафик уже шифрован WireGuard.

### Cloudflare Tunnel (публичный HTTPS)

```bash
./scripts/tunnel_cloudflared.sh
# скопируй выданный https://... URL в приложение
```

## 3. Войди в приложении

1. Base URL → из шага 2.
2. Username/password → `ADMIN_USERNAME`/`ADMIN_PASSWORD` из `.env`.
3. Токен сохранится в EncryptedSharedPreferences (AndroidKeyStore).

## 4. (Опционально) LLM

**Вариант А — модель на телефоне (без облака, без Termux):**
Dashboard → Local LLM → **On-device →** → скачайте модель (каталог/URL/SAF-импорт)
→ сохраните параметры и промт → **Start agent** → добавьте привязку (`all`
или `command /ask`) на вкладке Local LLM → бот отвечает силами телефона.

**Вариант Б — серверный провайдер:** на хосте с сервером
`ollama serve && ollama pull llama3.1:8b`; в приложении Local LLM → kind
`ollama` → Save → Test → модель → Activate.

Проверить генерацию можно во вкладке **Test chat** (сервер) или
«Локальный тест модели» (телефон).

## 5. Минимальный сценарий проверки

1. Dashboard → Start (бот должен стать ONLINE).
2. Plugins → `+` → создай `test1` → открой → Edit code → Save.
3. Включи `test1` (switch) → Reload.
4. Напиши боту в Telegram `/hello` → должен ответить.
5. Live logs → видно события; Audit → видно твои действия.
6. Backup → Create backup → Rollback проверяет восстановление.

## Устранение проблем

- **Connection refused** — API слушает не на том интерфейсе: нужен `--host 0.0.0.0`.
- **401** — неверный пароль или протухший токен: перелогинься.
- **WS offline** — WebSocket идёт на тот же хост: `wss://…/logs/stream`; убедись, что туннель пропускает WebSocket (оба варианта выше — пропускают).
- **Редактор без подсветки** — CodeMirror грузится с CDN; без интернета работает plain-textarea fallback, код всё равно сохраняется.
