'use strict';
// The generated format is deliberately data-only. No JavaScript/Python is evaluated.
const GENERATED_PROMPT = `Ты проектировщик Telegram-ботов для локального приложения BotControl Windows v0.5. Составь настройки бота по описанию ниже.

Верни ТОЛЬКО один корректный JSON-объект без Markdown и пояснений. Никакого Python, JavaScript, токенов, паролей и персональных данных. Бот запускается локально, языковая модель после импорта не используется. Не обещай функции, отсутствующие в формате; если они запрошены, не выдумывай для них поля.

Формат:
- Верхний уровень: {"format":"botcontrol","version":1,"name":"Название","scenarios":[...],"buttons":[...],"schedule":[]}.
- Сценарий: {"id":"уникальный-id","name":"Название","text":"Ответ"}. Для каждого сценария укажи все три поля. Для текста ответ не пустой, не более 4096 символов.
- Необязательное условие запуска: "trigger":{"type":"command","value":"/help"} или {"type":"contains","value":"привет"}. Команда начинается с /, далее до 32 латинских букв, цифр и подчёркиваний. Условия должны быть непустыми, команды не повторяются. Для кнопочного сценария trigger не добавляй.
- Случайный ответ: добавь поле "replies":["Первый ответ","Второй ответ"] и оставь "text":"". Каждый ответ непустой, до 4096 символов; до 500 вариантов. Не добавляй одновременно replies и animation.
- Анимация: добавь "animation":{"preset":"spinner","text":"Загрузка","interval":700,"loops":1,"count":5,"final":"Готово","frames":""}, оставь "text":"". preset: spinner, progress, dots, countdown, typewriter, matrix, slot, moon, clock, heart или custom. interval — целое 500–3000 миллисекунд, loops — целое 1–10, count — целое 1–30. Для custom frames — непустые кадры, разделённые строкой ---. Другие типы анимации недоступны.
- Ссылки под ответом: необязательное поле "links":[{"label":"Сайт","url":"https://example.com"}], максимум 3. Только известные пользователю HTTP(S)-ссылки, без логина и пароля в URL. Это ссылки, а не кнопки действий.
- Кнопка клавиатуры: {"id":"уникальный-id","label":"Надпись","scenarioId":"id-сценария"}. scenarioId должен совпадать с id существующего сценария. Названия кнопок уникальны без учёта регистра и не пусты. Кнопка может запускать текст, набор или анимацию.
- Не создавай расписание без точного числового chat ID пользователя. По умолчанию schedule: []. Сценарии проверяются в порядке: команда, кнопка, первая подходящая фраза. Код, inline-кнопки с действиями, ИИ и другие функции Android не поддерживаются.
- Минимум одна кнопка с корректной привязкой или один сценарий с непустым trigger. Все id уникальны внутри своих списков. Строки экранируй по правилам JSON, без завершающих запятых. Не добавляй лишних полей.

Пример:
{"format":"botcontrol","version":1,"name":"Приветствие","scenarios":[{"id":"welcome","name":"Приветствие","text":"Здравствуйте!","trigger":{"type":"command","value":"/start"}},{"id":"help","name":"Помощь","text":"Выберите кнопку."}],"buttons":[{"id":"help-button","label":"Помощь","scenarioId":"help"}],"schedule":[]}

Описание желаемого бота (замени текст ниже своим описанием):
[Напиши, что должен делать бот, какие нужны команды, ответы, кнопки и анимации.]`;

function parseGenerated(text) {
  if (typeof text !== 'string' || new TextEncoder().encode(text).length > 900000) throw Error('Ответ слишком большой: максимум 900 КБ.');
  let input = text.replace(/^\uFEFF/, '').trim();
  const fenced = input.match(/^```(?:json)?[ \t]*\r?\n([\s\S]*?)\r?\n```[ \t]*$/i);
  if (fenced) input = fenced[1];
  let bot;
  try { bot = JSON.parse(input); } catch (_) { throw Error('Ожидается один корректный JSON-объект без пояснений.'); }
  const keys = (o, allowed, context) => {
    if (!o || typeof o !== 'object' || Array.isArray(o)) throw Error('Ожидается объект: ' + context);
    const extra = Object.keys(o).filter(k => !allowed.includes(k));
    if (extra.length) throw Error('Неподдерживаемые поля в ' + context + ': ' + extra.join(', '));
  };
  keys(bot, ['format','version','name','scenarios','buttons','schedule'], 'боте');
  if (!Array.isArray(bot.scenarios) || !Array.isArray(bot.buttons)) throw Error('Нужны массивы scenarios и buttons.');
  for (const s of bot.scenarios) {
    keys(s, ['id','name','text','trigger','replies','animation','links'], 'сценарии');
    if (s.animation != null && s.replies != null) throw Error('Сценарий не может быть одновременно анимацией и набором.');
    if (s.trigger != null) keys(s.trigger, ['type','value'], 'условии');
    if (s.animation != null) keys(s.animation, ['preset','text','interval','loops','count','final','frames'], 'анимации');
    if (s.links != null) { if (!Array.isArray(s.links)) throw Error('links должен быть массивом.'); for (const l of s.links) keys(l, ['label','url'], 'ссылке'); }
  }
  for (const b of bot.buttons) keys(b, ['id','label','scenarioId'], 'кнопке');
  if (bot.schedule != null) {
    if (!Array.isArray(bot.schedule)) throw Error('schedule должен быть массивом.');
    for (const e of bot.schedule) keys(e, ['id','time','chatId','days','scenarioId'], 'напоминании');
  }
  return bot;
}
if (typeof module !== 'undefined' && module.exports) module.exports = {parseGenerated, GENERATED_PROMPT};
