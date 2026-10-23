# Kotlin-харнесс (прогон импортёра без Android)

Позволяет компилировать и запускать `data/PySource.kt`,
`data/ScriptImporter.kt`, `data/SettingsExporter.kt`, `data/BotExtras.kt`
**без Android SDK/Gradle** — обычным kotlinc из PyPI. Нужно, чтобы за
минуты проверять, как импортёр разобрал тот или иной python-конфиг,
не дожидаясь сборки APK в GitHub Actions.

## 1. Окружение (один раз на новую песочницу)

```bash
python3 -m venv /tmp/ktenv
/tmp/ktenv/bin/pip install -q jdk4py==21.0.8.2 kotlin-jupyter-kernel
```

Внутри появляется JRE (`jdk4py`) и
`org.jetbrains.kotlin.cli.jvm.K2JVMCompiler` (в fat-jar
`kotlin-jupyter-kernel-*-all.jar`).

## 2. Компиляция и запуск

`kc.sh` / `run.sh` сами находят jar-ы, пути можно переопределить
переменными `J` (каталог с jar-ами) и `JAVA` (бинарь java).

```bash
cd tools/kotlin-harness

SRC=../../android/app/src/main/java/com/botcontrol/admin/data

./kc.sh out $SRC/PySource.kt $SRC/ScriptImporter.kt $SRC/BotExtras.kt \
            $SRC/SettingsExporter.kt stubs/*.kt stubs2/*.kt src/Dump.kt

./run.sh out DumpKt ../import-fixtures/perkur-bot-oracle.py
./run.sh out DumpKt ../../bot-config-ready.py
```

`Dump.kt` печатает: промпт, ИИ-параметры, уточняющие вопросы, наборы,
команды меню, клавиатуру чата, канал, все правила с inline-кнопками,
расписание и **warnings** — именно их надо смотреть в первую очередь
(пустой список warnings = конфиг разобран полностью).

`./isolation.sh` (main `IsolationKt`, v1.6.3) — изоляция ботов: экспорт
бота не содержит наборов другого бота, `SettingsExporter.packRefs` видит
наборы в правилах/анимациях/кнопках, метка журнала `BotLog.tagged`.
Ожидаемый вывод — `ISOLATION OK`.

`src/T1.kt` (main `T1Kt`) — юнит-тест сканера `PySource`: строки,
комментарии, f-строки, raw/тройные кавычки, логические строки, функции
с декораторами, вызовы и kwargs.

## 3. Заглушки

| Файл | Зачем |
|---|---|
| `stubs/Gson.kt`, `stubs/TypeToken.kt` | вместо `com.google.gson` |
| `stubs2/Stubs.kt` (`LocalBotStore`), `Stubs2.kt` (`BotRuleEntity`), `Stubs3.kt` (`BotRepository`) | вместо Android/DataStore/Room-слоя для `SettingsExporter` |

Заглушки нужны только чтобы слинковать `SettingsExporter`; для
`PySource` + `ScriptImporter` + `BotExtras` достаточно `stubs/`.

## 4. Что важно помнить

- Код в `.kt` из `android/` должен оставаться **Android-независимым** в
  части парсера — иначе харнесс перестанет компилироваться, а это самый
  быстрый способ ловить регрессии импорта.
- Версия компилятора из PyPI может отличаться от Kotlin 2.3.21 проекта;
  для проверки синтаксиса этого достаточно, но финальная проверка —
  только сборкой APK в CI.
