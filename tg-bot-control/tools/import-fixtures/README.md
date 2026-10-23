# Фикстуры для импорта (регрессионные тесты)

Эти конфиги прогоняются через `ScriptImporter` при каждой правке
парсера. Критерий: **ни одного warning** и те же цифры, что ниже.

Прогон (см. `../kotlin-harness/README.md`):

```bash
cd ../kotlin-harness
./kc.sh out ../../../android/app/src/main/java/com/botcontrol/admin/data/{PySource,ScriptImporter,BotExtras}.kt stubs/*.kt src/Dump.kt
./run.sh out DumpKt ../import-fixtures/<файл>.py
```

## `perkur-bot-oracle.py`

Оригинал — `uploads/oracle code.txt` из `botcontrol-handoff.zip`
(рабочий python-бот перекура на Termux, функциональный эталон).
**Токен бота вырезан и заменён на `СЮДА_СВОЙ_ТОКЕН_BOTFATHER`** — файл с
настоящим токеном в git не попадает никогда.

Ожидаемо: 13 событий расписания (у 8 из них по 3 кнопки «перекур»),
`/start` с кнопками включения/выключения напоминаний и тостами
«Запущено» / «Уже работает», 3 набора (100/10/10), 18 уточняющих
вопросов, temperature 0.7, cooldown 10, typing 4.

## `bot-config-ready.py` (в корне `tg-bot-control/`)

Конфиг «Агент Смит / перекур», приведённый к полной совместимости:
добавлены состояние `bot_running`, `callback_query_handler` со
start/stop/smoke-ветками и 4-м элементом кортежа (меню) у всех событий.
Ожидаемо: тот же разбор, что у оракла, **ноль warnings**.

## `demo-bot-coffee.py` / `kotlin-harness/demo_bot.py`

Ручной конфиг кофейни «Утро»: клавиатура чата из 2 кнопок, URL-кнопка,
событие с условием `wd in (5,6)` (выходные), правило на кнопку и
правило «содержит», 5 событий расписания.

## `oracle-llm-aiogram.py`

Оригинал — `uploads/oracle llm.txt`: aiogram + внешний LLM.
Используется как проверка, что парсер не спотыкается о чужой стиль
(async/await, aiogram-фильтры, F-объекты).

## `правки-пользователя.txt`

Формулировки правок от пользователя (что было сломано: команды, правила,
кнопки, имитация набора). Хранить как контекст, чтобы не возвращать
исправленные баги.

## `export-bot2-smith.py` (v1.6.0)

Экспорт бота №2 из приложения в том виде, как его прислал пользователь
(с `&lt;`, порванными строками и без обработчика нажатий). Ожидаемо:
start/stop → вкл/выкл напоминаний (с «ℹ️»-замечанием), ПЕРЕКУР —
будни, Пт 15:55 отдельно, Пн–Чт 15:55/16:55.

## `anim-demo-bot.py` (v1.6.0)

Анимации и кубики: `/start` → шаблон matrix + 4 кнопки (слот с итогом
из набора `pack=PREDICTIONS` и `message_id=` → правка на месте, кубик,
прогресс ×2, квест моноширинный), `/rocket` — свои кадры с ведущими
переносами, `/countdown` — цикл правок прямо в обработчике, `/dart` — 🎯.
Наборов — 1 (списки-кадры в наборы не попадают).

## Круговой тест «код → импорт → экспорт → импорт» (v1.6.0)

```bash
cd ../kotlin-harness
SRC=../../android/app/src/main/java/com/botcontrol/admin/data
./kc.sh out $SRC/PySource.kt $SRC/ScriptImporter.kt $SRC/BotExtras.kt $SRC/Anim.kt \
  $SRC/SettingsExporter.kt stubs/*.kt stubs2/*.kt src/Dump.kt src/RoundTrip.kt src/AnimTest.kt
for f in ../import-fixtures/*.py ../../smith-bot-ready.py; do
  ./run.sh out RoundTripKt $f /tmp/rt.py 2>&1 | tail -1   # ждём ROUNDTRIP OK
  python3 -c "import ast; ast.parse(open('/tmp/rt.py').read())"
done
./run.sh out AnimTestKt | tail -1                          # ждём ALL OK
```

`anim-demo-bot.py` с v1.6.1 содержит ещё `/tour` — фото-квест (4 кадра:
3 картинки по ссылке + кадр «только подпись»), 5 команд.
