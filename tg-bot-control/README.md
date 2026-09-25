# BotControl — Telegram-бот + Admin API + Android-приложение

Управление, создание, редактирование и мониторинг Telegram-бота **прямо
с телефона**: без Termux, без ручного редактирования файлов.

## Архитектура

```
┌──────────────────────────┐   HTTPS/WSS + JWT    ┌────────────────────────────┐
│ Android (Kotlin/Compose) │ ──────────────────── │ FastAPI Admin API          │
│ Retrofit · WS · DataStore│   Tailscale/CF tunnel │ JWT + RBAC · audit log     │
│ Room (черновики) · WebView│                      │ REST + /logs/stream (WS)   │
│ CodeMirror-редактор      │                      └─────────────┬──────────────┘
└──────────────────────────┘                                    │ in-process
                                                  ┌─────────────▼──────────────┐
                                                  │ Ядро бота (aiogram 3)      │
                                                  │ BotManager: start/stop/    │
                                                  │ restart · hot reload       │
                                                  └─────────────┬──────────────┘
                                                                │
                                                  ┌─────────────▼──────────────┐
                                                  │ PluginManager + Sandbox    │
                                                  │ 1) manifest triggers (без  │
                                                  │    кода — всегда безопасно)│
                                                  │ 2) main.py → AST-валидация │
                                                  │    + restricted builtins + │
                                                  │    timeout + минимальный   │
                                                  │    ctx (без aiogram/OS/сети)│
                                                  └────────────────────────────┘
```

**Ключевое решение по безопасности:** произвольный Python из приложения
не выполняется напрямую. Сначала — декларативные триггеры (рекомендовано),
затем — код, прошедший AST-валидацию (запрещены `os/sys/subprocess/socket`,
`open/exec/eval`, dunder, приватные атрибуты), исполняемый с урезанными
builtins, таймаутом 5с и доступом только к `ctx.reply/text/user/config`.
Прямого shell/exec из приложения нет в принципе — только REST/WS.

## Структура проекта

```
tg-bot-control/
├── README.md · .env.example · docker-compose.yml
├── server/                    # Python 3.12: Admin API + ядро бота
│   ├── requirements.txt · pytest.ini · Dockerfile
│   ├── app/
│   │   ├── main.py            # FastAPI app + lifespan
│   │   ├── config.py          # pydantic-settings (.env)
│   │   ├── security.py        # JWT + bcrypt + RBAC
│   │   ├── database.py models.py schemas.py audit.py
│   │   ├── routers/           # auth status bot_control plugins logs config llm backup
│   │   ├── services/          # log_bus (WS fan-out) backup config llm_service llm_jobs
│   │   └── bot/               # core (BotManager) plugin_manager sandbox rule_engine
│   ├── plugins/               # _template/ welcome/ (codeless) echo/ (sandboxed)
│   └── tests/                 # 22 теста: sandbox, manifest, API-контракт
├── android/                   # Kotlin + Jetpack Compose
│   ├── settings.gradle.kts build.gradle.kts gradle.properties
│   ├── gradle/libs.versions.toml
│   └── app/                   # build.gradle.kts + исходники
│       └── src/main/
│           ├── AndroidManifest.xml · assets/editor.html (CodeMirror)
│           └── java/com/botcontrol/admin/
│               ├── MainActivity.kt BotControlApp.kt
│               ├── data/      # AuthStore (EncryptedSharedPrefs+DataStore)
│               │              # BotRepository · WebSocketManager · local/Room
│               ├── ui/        # theme navigation screens/* components
│               └── worker/    # StatusWorker (WorkManager)
├── docs/CONNECT.md            # подключение приложения к боту
├── scripts/                   # run_server.sh build_apk.sh tunnel_*
└── tools/                     # kotlin-harness, фикстуры импорта, check-bot-isolation.py
# CI: /.github/workflows/botcontrol-apk.yml в корне репозитория
```

## API (все обязательные методы)

| Метод | Путь |
|---|---|
| POST | `/auth/login` `/auth/logout` |
| GET | `/status` |
| POST | `/bot/start` `/bot/stop` `/bot/restart` |
| GET/POST | `/plugins` |
| GET/PUT/DELETE | `/plugins/{id}` |
| POST | `/plugins/{id}/enable` `/disable` `/reload` |
| GET | `/plugins/{id}/logs` `/plugins/{id}/versions` |
| GET | `/logs` (+ WS `/logs/stream?token=…`) |
| GET | `/audit` |
| GET/PUT | `/config` |
| POST/GET | `/backup/create` `/backups` |
| POST | `/rollback/{version}` (`backup-…` или `pv:<id>`` |
| GET/POST | `/llm/status` `/llm/providers` `/llm/profile` `/llm/bindings` `/llm/chat` |
| POST/PUT/DELETE | `/llm/providers[/{id}][/activate][/test]` `/llm/bindings[/{id}]` |
| POST | `/llm/jobs/claim` `/llm/jobs/{id}/result` `/llm/jobs/{id}/fail` |
| GET | `/llm/jobs` `/llm/agent/status` |

Роли: `viewer` (чтение) < `operator` (управление) < `admin` (создание/удаление/бэкапы).

## Формат плагина

```
plugin_name/
  manifest.json   # id name version description enabled permissions
                  # entrypoint commands triggers dependencies min_bot_version
  config.json     # произвольный JSON-конфиг (доступен в ctx["config"])
  main.py         # async def handle(ctx) — sandboxed, опционален
  handlers/ assets/ logs/
```

Пример codeless-триггера (Python не выполняется вообще):

```json
{"type": "command", "pattern": "/start",
 "response_text": "Welcome, {user}!", "description": "Greet"}
```

Пример sandboxed-кода (`ctx`: `text user user_id chat_id config reply()`):

```python
async def handle(ctx):
    await ctx["reply"]("Echo: " + ctx["text"])
```

## Режим без сервера: бот целиком на телефоне (для новичков)

Первый экран приложения — выбор режима, логин не обязателен:

- **🚀 «Создать бота здесь, на телефоне»** — вставь токен от @BotFather,
  приложение проверит его и запустит бота: long-polling Telegram, команды
  и ответы добавляются кнопками (хранятся в Room), незнакомые сообщения
  отвечает локальная LLM, всё управление — крупными кнопками на русском.
- **🔌 «Подключиться к своему серверу»** — полная консоль (плагины, код,
  аудит, бэкапы): адрес можно ввести вручную, выбрать пресет канала
  (Wi-Fi / Tailscale / Cloudflare / VPS) или найти сервер кнопкой
  «🔍 Найти сервер в этой Wi-Fi сети» (сканирует подсету :8000/health).

Токен бота хранится в EncryptedSharedPreferences (AndroidKeyStore).
Сервис работает в фоне (foreground service, тип dataSync — Android 14+ ok).

## Локальные LLM — два режима

### Режим 1 (основной): модель на телефоне (on-device, без облака и без Termux)

В APK встроен нативный движок **MediaPipe GenAI** (`tasks-genai:0.10.27`):
Gemma / Phi-3 / Falcon / StableLM крутятся прямо на Android-телефоне.

Как это работает: бот на сервере ловит сообщение → по привязке создаёт
задание в очереди `llm_jobs` → приложение на телефоне (Foreground-сервис
`dataSync`, Android 14+ compliant) забирает задание, генерирует **локально**
и возвращает ответ → бот отвечает в Telegram. Если телефон офлайн — задание
истекает по таймауту (120 с), бот переходит на серверный провайдер (режим 2).

Во вкладке **Local LLM → On-device** есть всё: каталог моделей (ссылки на
официальные страницы — лицензию нужно принять), скачивание по прямому URL
(с прогрессом и опциональным Bearer-токеном), импорт файла через SAF,
параметры (temperature / topK / max_tokens / seed), системный промт
(единый серверный профиль), локальный тест модели и тумблер агента.

Требования: Android 14+, 4+ ГБ RAM для 1–2B моделей; модели (0.5–3 ГБ)
скачиваются в приватное хранилище приложения, в APK не входят.

### Режим 2 (fallback): сервер-сайд провайдер

LLM рядом с ботом по OpenAI-совместимому API — Ollama / LM Studio /
llama.cpp / vLLM:

```bash
curl -fsSL https://ollama.com/install.sh | sh
ollama serve &                      # порт 11434
ollama pull llama3.1:8b
```

Подключение: **Local LLM** → kind `ollama` → Save → Test → модель → Activate.

### Привязки (общие для обоих режимов)

`all` (все сообщения без плагина), `command` (`/ask …`), `chat` (chat_id),
`channel` (посты канала, `@channel`). Порядок: плагины → привязка LLM →
ответ (телефон-агент, иначе серверный провайдер).

Bootstrap-альтернатива вкладке: `LLM_BASE_URL=http://localhost:11434/v1`,
`LLM_MODEL=llama3.1:8b` в `.env` — провайдер создастся сам при первом старте.

API-ключи провайдеров хранятся только на сервере и наружу отдаются как
`api_key_set: true/false`. RBAC: CRUD провайдеров — admin, привязки/промт/тест/агент —
operator, просмотр — viewer. Ошибки генерации видны в live-логах (`source=llm`).

## Быстрый старт (сервер)

```bash
cp .env.example .env            # заполнить SECRET_KEY / BOT_TOKEN / ADMIN_PASSWORD
./scripts/run_server.sh          # pip install + pytest + uvicorn :8000
# вручную: cd server && python -m pytest -q && python -m uvicorn app.main:app --host 0.0.0.0 --port 8000
# docker:  docker compose up --build
```

## Сборка Android-приложения

```bash
export ANDROID_HOME=$HOME/Android/Sdk   # JDK 17 + API 34
./scripts/build_apk.sh
# APK: android/app/build/outputs/apk/debug/app-debug.apk
# вручную: cd android && ./gradlew :app:assembleDebug
```

Без локального SDK: запушь в GitHub — workflow `Android APK` соберёт
debug-APK автоматически, артефакт `BotControl-debug-apk`.

## Подключение приложения

**Подробная пошаговая установка и настройка — [docs/INSTALL.md](docs/INSTALL.md).**

Полная инструкция — [docs/CONNECT.md](docs/CONNECT.md). Кратко:

1. Подними API и открой доступ: та же Wi-Fi / Tailscale / Cloudflare Tunnel.
2. В приложении введи Base URL + логин/пароль админа.
3. Dashboard → Start → создай плагин → включи → пиши боту в Telegram.

## Тесты

```bash
cd server && python -m pytest -q     # 22 passed: sandbox/manifest/API
```

## Безопасность (что запрещено и как)

- Токены: только EncryptedSharedPreferences (AndroidKeyStore), в Room/логах их нет.
- Секреты сервера: только `.env`, редактируемый subset `/config` их не содержит.
- Нет endpoint'ов shell/exec; нет публичных методов без JWT (кроме `/health`).
- Каждое изменение: snapshot версии + запись в audit log с user/IP.
- Откат: `POST /rollback/{backup}` (плагины) или `pv:<id>` (манифест плагина).
