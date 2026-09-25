<p align="center">
  <img src="tg-bot-control/branding/logo-512.png" width="128" alt="BotControl">
</p>
<h1 align="center">BotControl</h1>
<p align="center"><b>Telegram-боты прямо с телефона</b>: без сервера и VPS, команды, кнопки,
расписание, анимации, ИИ на устройстве.</p>
<p align="center">
  <a href="https://github.com/visiontripin/oracle/releases"><b>⬇️ Скачать APK</b></a> ·
  <a href="https://t.me/AppBotcontrol_bot"><b>🤖 Демо-бот @AppBotcontrol_bot</b></a> ·
  <a href="docs/index.html"><b>🌐 Страница проекта</b></a>
</p>

<p align="center"><img src="tg-bot-control/branding/slides/01-cover.png" width="720" alt="Презентация BotControl"></p>

---

# oracle

Репозиторий — единственный источник истины по проекту **BotControl**
(Android-приложение: локальные Telegram-боты + LLM на устройстве +
конфиги ботов «кодом»).

## Куда смотреть

| Путь | Что там |
|---|---|
| [`tg-bot-control/ПЕРЕДАЧА-КОНТЕКСТА.md`](tg-bot-control/ПЕРЕДАЧА-КОНТЕКСТА.md) | **читать первым делом**: состояние, карта файлов, грамматика импорта, требования, ловушки, история версий |
| [`tg-bot-control/`](tg-bot-control/) | сам проект: `android/` (исходники), `server/`, готовые конфиги `*.py`, доки |
| [`tg-bot-control/tools/kotlin-harness/`](tg-bot-control/tools/kotlin-harness/) | прогон импортёра (`PySource` / `ScriptImporter`) на Kotlin без Android SDK |
| [`tg-bot-control/tools/import-fixtures/`](tg-bot-control/tools/import-fixtures/) | конфиги-фикстуры для регрессионных проверок импорта |
| [`.github/workflows/botcontrol-apk.yml`](.github/workflows/botcontrol-apk.yml) | сборка APK в GitHub Actions + тег/релиз при `[apk]` в сообщении коммита |
| [`tg-bot-control/branding/`](tg-bot-control/branding/) | логотип (`logo.svg` — исходник), аватар бота, слайды презентации; генераторы `make_assets.py`, `make_android_icons.py` |
| [`tg-bot-control/presentation-bot-ready.py`](tg-bot-control/presentation-bot-ready.py) | бот-презентация для @AppBotcontrol_bot (слайды кнопками, демо анимаций) |
| [`docs/`](docs/) | веб-страница проекта (GitHub Pages из `/docs`) |
| `botcontrol-handoff.zip` | архив для переноса проекта в чистый workspace |

> Файл `ПЕРЕДАЧА-КОНТЕКСТА.md` в корне репозитория — это исходная версия
> из первой выгрузки, она **устарела**. Актуальная — в
> `tg-bot-control/ПЕРЕДАЧА-КОНТЕКСТА.md`.

## Сборка и доставка

Собрать локально нельзя (в песочнице закрыты Google Maven и Maven
Central), поэтому APK собирается в GitHub Actions. Рецепт, проверка aapt и
получение APK из тега — раздел 4 файла
`tg-bot-control/ПЕРЕДАЧА-КОНТЕКСТА.md`.

Текущая доставка: **v1.6.2 (versionCode 29)**, тег
`botcontrol-v1.6.2-code29`. Следующий versionCode — **30**.

## Безопасность

Токены ботов и любые живые ключи в git не попадают (в ораклах из
`uploads/` они есть — перед коммитом вырезаются). В репозитории лежит
только debug-keystore для воспроизводимой подписи debug-APK.
