# BotControl — приложение и конфиги ботов

Исходники Android-приложения **BotControl** (`com.botcontrol.admin`) и
готовые конфиги ботов. Бот работает прямо на телефоне: long polling Bot
API, несколько ботов на разных токенах, правила и кнопки, анимации,
расписание, публикация объявлений в канал, ИИ на устройстве.

## Структура

```
tg-bot-control/
├── android/                 # Kotlin + Jetpack Compose (AGP 8.12, Gradle 8.13, Kotlin 2.3.21)
│   └── app/src/main/java/com/botcontrol/admin/
│       ├── data/            # LocalBotStore, ScriptImporter, SettingsExporter, TelegramApi
│       ├── service/         # LocalBotService, BotBrain, AnimPlayer, ListingEngine, FileServer
│       ├── llm/             # MediaPipe / LiteRT движки, ModelStore
│       └── ui/              # экраны, компоненты, тема (палитра Telegram)
├── scripts/                 # build_apk.sh, build_in_sandbox.sh
├── branding/                # logo.svg, avatar-640.png, slides/, генераторы ассетов
├── tools/                   # kotlin-harness, import-fixtures, check-bot-isolation.py
├── smith-bot-ready.py       # «Агент Смит / перекур»: ИИ-характер, шутки, анимация, расписание
├── daromvpl-channel-ready.py# канал «Даром в ПЛ»: команды, правила, дайджесты, реакции
├── presentation-bot-ready.py# бот-презентация @AppBotcontrol_bot
├── Инструкция-код-бота.md   # как писать конфиги и импортировать их
├── Промт-создание-ботов.md  # промт для генерации конфигов
└── ПЕРЕДАЧА-КОНТЕКСТА.md    # состояние проекта, грамматика импорта, ловушки
```

## Импорт конфига

Приложение → бот → **Скрипты → Импорт настроек из кода** → вставить текст
или выбрать `.py` → **Разобрать** → **Применить к боту**. Python **не
исполняется**: `PySource` разбирает текст, `ScriptImporter` раскладывает его
по настройкам (команды, правила, меню, клавиатура, наборы, расписание,
канал). Всё непонятое показывается предупреждениями.

Обратный путь — **Экспорт настроек в код**: получается рабочий `.py`-конфиг
этого бота.

## Сборка

APK собирается в GitHub Actions (`.github/workflows/botcontrol-apk.yml`):
пуш в ветку, `[apk]` в сообщении коммита → тег + релиз с APK и конфигами.
Локально нужен JDK 17 и Android SDK 35:

```bash
./scripts/build_apk.sh
# APK: android/app/build/outputs/apk/debug/app-debug.apk
```

## Тесты и проверки

```bash
python3 tools/check-bot-isolation.py     # сервис не читает настройки без botId (шаг CI)
cd tools/kotlin-harness && ./isolation.sh # изоляция наборов в экспорте
```

Прогон импортёра на фикстурах — `tools/kotlin-harness/README.md`.

## Ссылки

- Презентация: [docs/presentation.html](../docs/presentation.html) · [PDF](../docs/presentation.pdf)
- Релизы и APK: <https://github.com/visiontripin/oracle/releases>
- Демо-бот: <https://t.me/AppBotcontrol_bot>
