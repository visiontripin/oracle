# BotControl — подробная установка и настройка

Полная инструкция «с нуля до работающего бота с LLM». Все команды проверены
на проекте; имена файлов и переменных соответствуют репозиторию.

---

## Состав системы (что где работает)

| Компонент | Где крутится | Что делает |
|---|---|---|
| **Admin API + ядро бота** | сервер: ПК, домашний сервер, VPS или Termux | FastAPI (:8000), Telegram-бот (aiogram 3), очередь заданий LLM |
| **Android-приложение** | телефон | пульт: статус, плагины, редактор кода, логи, аудит, бэкапы, LLM |
| **LLM на телефоне** (режим 1) | телефон, внутри приложения | MediaPipe GenAI — генерация локально, без облака |
| **LLM на сервере** (режим 2, fallback) | рядом с API | Ollama / LM Studio / llama.cpp / vLLM |

Требования: сервер — Python 3.11+ (рекомендуется 3.12), 1 ГБ RAM достаточно;
телефон — Android 8.0+ (для on-device LLM — **Android 14+ и 4+ ГБ RAM**).

---

## Быстрый путь: бот только на телефоне (без сервера, без логина)

Если не хочешь поднимать сервер вообще:

1. Установи APK (шаг 3).
2. На первом экране выбери **«🚀 Создать бота здесь, на телефоне»**.
3. Вставь токен от @BotFather (получишь за минуту — см. шаг 1) → «Проверить токен».
4. Нажми **▶ Запустить** — бот уже отвечает в Telegram.
5. Кнопкой **«+ Добавить команду»** задай пары «команда → ответ»;
   включи ИИ (модель скачается на телефон, см. раздел LLM) — он будет
   отвечать на все незнакомые сообщения.

Логин и пароль на этом пути не нужны вовсе.

---

## Шаг 1. Токен Telegram-бота

1. Открой [@BotFather](https://t.me/BotFather) → `/newbot`.
2. Задай имя и username (должен заканчиваться на `bot`).
3. Сохрани токен вида `123456789:AAHfqT...` — он пойдёт в `.env` (`BOT_TOKEN`).

---

## Шаг 2. Установка сервера

### Вариант A — вручную (Linux/macOS/Termux/Windows+WSL)

```bash
cd tg-bot-control

# 1) виртуальное окружение
python3 -m venv .venv
source .venv/bin/activate        # Windows: .venv\Scripts\activate

# 2) зависимости
pip install -r server/requirements.txt

# 3) конфигурация
cp .env.example server/.env
```

Отредактируй `server/.env`:

| Переменная | Обязательно | Что указать |
|---|---|---|
| `SECRET_KEY` | да | длинная случайная строка (32+ символа). Сгенерировать: `python3 -c "import secrets; print(secrets.token_hex(32))"` |
| `BOT_TOKEN` | да | токен из BotFather |
| `ADMIN_USERNAME` | нет (по умолч. `admin`) | логин в приложении |
| `ADMIN_PASSWORD` | да | **надёжный** пароль (мин. 8 символов) |
| `DATABASE_URL` | нет | SQLite по умолчанию; для PostgreSQL: `postgresql+asyncpg://bot:pass@host:5432/botdb` |
| `LLM_BASE_URL`, `LLM_MODEL` | нет | автобутстрап серверного LLM-провайдера (шаг 7.2), можно оставить пустыми |

```bash
# 4) проверка: 34 теста должны пройти
cd server && python3 -m pytest -q      # ожидай: 34 passed

# 5) запуск (порт 8000)
python3 -m uvicorn app.main:app --host 0.0.0.0 --port 8000
# или из корня: ./scripts/run_server.sh  (сам ставит зависимости, гоняет тесты, копирует .env)
```

Проверка: `curl http://localhost:8000/health` → `{"ok":true,...}`.

### Вариант B — Docker

```bash
cp .env.example .env && nano .env     # SECRET_KEY, BOT_TOKEN, ADMIN_PASSWORD
docker compose up --build -d
docker compose logs -f                # ждём "Admin API ready"
```

Плагины/БД/бэкапы монтируются из `server/plugins`, `server/data`, `server/backups`.

### Автозапуск как службы (systemd, опционально)

`/etc/systemd/system/botcontrol.service`:

```ini
[Unit]
Description=BotControl Admin API
After=network-online.target

[Service]
User=your-user          # замени на своего пользователя
WorkingDirectory=/path/to/tg-bot-control/server
EnvironmentFile=/path/to/tg-bot-control/server/.env
ExecStart=/path/to/tg-bot-control/.venv/bin/python -m uvicorn app.main:app --host 0.0.0.0 --port 8000
Restart=always

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload && sudo systemctl enable --now botcontrol
```

---

## Шаг 3. Установка Android-приложения (APK)

### Вариант A — через GitHub CI (не нужен Android SDK)

1. Запушь проект в свой репозиторий GitHub (ветка `main`).
2. Вкладка **Actions** → workflow **«Android APK»** → дождись зелёной галочки.
3. В артефактах скачай **BotControl-debug-apk** (zip) → внутри `app-debug.apk`.
4. Перекинь файл на телефон (Telegram «Избранное», USB, облако — что угодно).
5. На телефоне открой APK: Android спросит разрешение «устанавливать неизвестные
   приложения» для источника — разреши. Требуется Android 8.0+.

### Вариант B — локальная сборка

Нужны JDK 17 и Android SDK (Platform 34 + Build-Tools):

```bash
export ANDROID_HOME=$HOME/Android/Sdk
export JAVA_HOME=/usr/lib/jvm/temurin-17-jdk-amd64   # путь к JDK 17
./scripts/build_apk.sh
# → android/app/build/outputs/apk/debug/app-debug.apk
```

Или через Android Studio: **File → Open** → папка `android/` → **Run**.

> Дебаг-APK подписан отладочным ключом. Для постоянного использования
> собери release (`Build → Generate Signed App Bundle / APK`) со своим keystore.

---

## Шаг 4. Первый запуск приложения

1. Открой **BotControl**.
2. На экране входа три поля:
   - **API Base URL** — адрес сервера (см. шаг 5);
   - **Username / Password** — `ADMIN_USERNAME`/`ADMIN_PASSWORD` из `.env`.
3. **Login**. Токен сохранится в зашифрованном хранилище (AndroidKeyStore) —
   повторный вход не потребуется, пока токен жив (2 ч) или до Logout.

### Какой Base URL вводить

| Ситуация | Base URL |
|---|---|
| Телефон и сервер в одной Wi-Fi сети | `http://<IP-сервера>:8000` (IP: `ip a` / `ipconfig`) |
| Tailscale подключён на обоих | `http://<имя-ноды>` или `http://100.x.y.z:8000` |
| Cloudflare Tunnel запущен | `https://<случайные-слова>.trycloudflare.com` |
| VPS с доменом | `https://bot.example.com` |

---

## Шаг 5. Доступ с телефона к серверу

### 5.1. Только одна Wi-Fi сеть (просто, но только дома)

- Сервер уже слушает `0.0.0.0:8000` — из локальной сети доступен.
- Проверь с ПК: `curl http://<IP>:8000/health`. Если недоступен — firewall:
  `sudo ufw allow 8000/tcp` (или аналог).
- Минус: вне дома не работает (см. 5.2/5.3).

### 5.2. Tailscale — приватный доступ из любой точки (рекомендуется)

1. Зарегистрируйся на [tailscale.com](https://tailscale.com) (бесплатный план).
2. Установи на сервер: `curl -fsSL https://tailscale.com/install.sh | sh && tailscale up`
3. Установи приложение Tailscale из Google Play на телефон, войди в **тот же** аккаунт.
4. Узнай имя/IP сервера: `tailscale status` (например `homeserver`, `100.64.0.2`).
5. В приложении: Base URL `http://homeserver:8000`.

Трафик идёт через WireGuard-шифрование, порты наружу не открываются.

### 5.3. Cloudflare Tunnel — публичный HTTPS (если Tailscale не подходит)

```bash
# установка cloudflared: https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/
./scripts/tunnel_cloudflared.sh
# → скопируй выданный https://xxx.trycloudflare.com в приложение как Base URL
```

WebSocket (`/logs/stream`) через туннель работает. Быстрый URL меняется при
перезапуске; для постоянного адреса настрой именованный туннель (инструкция
по ссылке в скрипте) или купи домен на Cloudflare.

### 5.4. VPS

Если сервер уже в интернете: закрой `:8000` файрволом и поставь reverse-proxy
(nginx + `certbot --nginx` для TLS), проксирующий на `127.0.0.1:8000`, включая
`Upgrade`-заголовки для WebSocket.

---

## Шаг 6. Запуск бота

1. В приложении: **Dashboard → Start** → статус должен стать **ONLINE**.
2. Напиши боту в Telegram `/start` → получишь ответ (из плагина welcome или
   сообщение «Bot is online…»).
3. Кнопки **Stop / Restart** работают сразу; ошибки видны в «Errors» на
   Dashboard и в **Live logs**.

Важно: один токен = один активный polling. Если бот уже запущен где-то ещё,
Telegram вернёт 409 Conflict — останови второй экземпляр.

---

## Шаг 7. LLM

### 7.1. Режим 1 — модель на телефоне (основной; Android 14+, 4+ ГБ RAM)

1. **Dashboard → Local LLM → On-device →**.
2. **Получи модель** (любой способ):
   - **Каталог**: выбери модель (например Gemma-3 1B) → «Открыть страницу» →
     прими лицензию → получи прямую ссылку на файл `.task`/`.bin`;
   - **Скачать по URL**: вставь ссылку (+ Bearer-токен для gated-репозиториев
     HuggingFace/Kaggle) → следи за прогрессом;
   - **Импорт файла**: если модель уже скачана — передай файл через SAF.
3. Отметь модель радиокнопкой — она станет активной.
4. **Параметры**: temperature / topK / max_tokens / seed → «Сохранить параметры».
5. **Системный промт** → «Сохранить промт» (единый профиль для обоих режимов).
6. Проверь локально: «Локальный тест модели» → вопрос → «Сгенерировать локально».
7. **Start agent** — в шторке появится уведомление «Агент активен», а на экране —
   «Сервер видит агента онлайн».
8. На вкладке **Local LLM** добавь привязку: `all` (отвечать на всё без плагина)
   или `command` с паттерном `/ask` (отвечать только на `/ask вопрос`).
9. Тест: напиши боту в Telegram → ответ придёт с телефона. В Live logs появятся
   строки с `source=llm` (`job #N -> device agent`, `answered from device`).

Нюансы: первая загрузка модели занимает 10–60 с; экран держит уведомление —
не свайпай его (убьёт сервис); при нехватке памяти агент сообщит об ошибке
в задании, и оно вернётся как failed (бот промолчит — см. логи).

### 7.2. Режим 2 — серверный провайдер (fallback; нужен, если телефон офлайн)

На машине с сервером:

```bash
curl -fsSL https://ollama.com/install.sh | sh
ollama serve &                       # порт 11434
ollama pull llama3.1:8b              # или qwen2.5:7b, mistral и т.д.
```

В приложении: **Local LLM** → kind `ollama` → **Save** (URL подставится сам) →
**Test** (покажет модели сервера) → выбери модель → **Save** → **Activate** →
**Test chat** («Ask the model»).

Если Ollama на другом хосте: `OLLAMA_HOST=0.0.0.0 ollama serve` и укажи
`http://<хост>:11434` в поле Base URL.

### Приоритет ответов

Плагины → привязка LLM → **телефон-агент** (если онлайн) → серверный провайдер
(если настроен) → тишина. Привязки, промт и параметры общие.

---

## Шаг 8. Плагины (кратко)

- **Без кода**: Plugins → **+** → id `hello`, триггер `command /hello` с
  response_text → Create → включи switch → `/hello` в Telegram.
- **С кодом**: Plugins → плагин → **Edit code** → вкладка `main.py` →
  `async def handle(ctx): await ctx["reply"]("Привет, " + ctx["user"])` → Save.
  Код валидируется песочницей (запрещены os/subprocess/socket/exec/eval...).
- Версии/откат: вкладка версий в плагине; глобально — Backups & rollback.

---

## Шаг 9. Эксплуатация

- **Live logs** — поток событий в реальном времени (WebSocket), фильтры по
  уровню и тексту.
- **Audit** — кто и что менял (включая все действия с LLM).
- **Bot settings** — безопасный subset настроек (имя бота, язык, timezone...).
- **Backups** — снапшот плагинов + откат в один тап.
- Бэкап БД: просто скопируй `server/data/bot.db` (на остановленном боте).
- Обновление приложения: повтори шаг 3 (CI пересоберёт APK).

---

## Шаг 10. Типичные проблемы

| Симптом | Причина и решение |
|---|---|
| «Connection refused» в приложении | API не запущен / неверный IP / firewall (`ufw allow 8000`) |
| 401 при входе | неверный пароль или протух токен (2 ч) → Logout → Login |
| WS «offline» в Live logs | туннель/прокси не пропускает WebSocket (CF и Tailscale — пропускают; проверь nginx-заголовки Upgrade) |
| Бот не стартует, 409 Conflict | токен используется другим процессом (второй uvicorn, BotFather-Webhooks) — останови его |
| Проверка токена падает в простом режиме | теперь ошибки различаются: «401 — токен неверный» (скопируй целиком от @BotFather), «404 — не похож на токен» (формат 123456:AAH…), «Нет связи с api.telegram.org» (интернет/VPN). Старые версии проверяли токен в главном потоке — всегда падали, обнови APK |
| «Bot is online. No plugin handled this message.» | нет активного плагина/привязки — включи плагин или добавь LLM-привязку |
| Агент «Сервер не видит агента» | сервис остановлен (открой приложение и Start agent), или Base URL в приложении изменился |
| Задания истекают (`expired`) | телефон офлайн/спит, модель грузится дольше таймаута (120 с) — запусти агента заранее |
| «model is not loaded» / OOM | модель велика для RAM — возьми 1B-модель, уменьши max_tokens |
| APK не ставится | разрешни «Установка из этого источника»; если ARM-телефон — качай arm64-сборку (CI даёт универсальную) |
| `SECRET_KEY` error при старте | не заполнен `server/.env` — сгенерируй ключ (шаг 2) |
| Редактор без подсветки | CodeMirror грузится с CDN; без сети работает plain-text fallback, код сохраняется |
| Порт 8000 занят | `API_PORT`/`--port` на другой, Base URL в приложении поправь |

---

## Чек-лист безопасности

- [ ] `SECRET_KEY` — случайный 32+ символов, не коммитится в git
- [ ] `ADMIN_PASSWORD` — надёжный и не «change-me-strong-password»
- [ ] Порт `8000` не открыт в интернет напрямую (только туннель/VPN/TLS-proxy)
- [ ] Роли: операторы без прав admin не могут удалять плагины/провайдеров
- [ ] Токен бота не публикуется; при утечке — revoke у BotFather
- [ ] Регулярные бэкапы (`Backups → Create backup now` + копия `data/bot.db`)

Готово: бот работает, управление — с телефона, LLM — локально на устройстве.
