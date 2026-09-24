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
| `botcontrol-handoff.zip` | архив для переноса проекта в чистый workspace |

> Файл `ПЕРЕДАЧА-КОНТЕКСТА.md` в корне репозитория — это исходная версия
> из первой выгрузки, она **устарела**. Актуальная — в
> `tg-bot-control/ПЕРЕДАЧА-КОНТЕКСТА.md`.

## Сборка и доставка

Собрать локально нельзя (в песочнице закрыты Google Maven и Maven
Central), поэтому APK собирается в GitHub Actions. Рецепт, проверка aapt и
получение APK из тега — раздел 4 файла
`tg-bot-control/ПЕРЕДАЧА-КОНТЕКСТА.md`.

Текущая доставка: **v1.5.5 (versionCode 25)**, тег
`botcontrol-v1.5.5-code25`. Следующий versionCode — **26**.

## Безопасность

Токены ботов и любые живые ключи в git не попадают (в ораклах из
`uploads/` они есть — перед коммитом вырезаются). В репозитории лежит
только debug-keystore для воспроизводимой подписи debug-APK.
