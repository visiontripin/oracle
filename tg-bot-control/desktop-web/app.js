'use strict';
const KEY = 'botcontrol-editor-v1';
const PRESETS = {spinner:'⠋ Спиннер',progress:'▰ Прогресс-бар',dots:'… Точки',countdown:'⏳ Отсчёт',typewriter:'⌨ Печатная машинка',matrix:'🟩 Матрица',slot:'🎰 Слот-машина',moon:'🌕 Фазы луны',clock:'🕐 Часы',heart:'❤️ Сердце',custom:'🎞 Свои кадры'};
const newAnim = () => ({preset:'spinner',text:'Загрузка',interval:700,loops:1,count:5,final:'',frames:''});
function validAnim(a) { return a && typeof a === 'object' && Object.hasOwn(PRESETS,a.preset) && ['text','final','frames'].every(k=>typeof a[k] === 'string' && a[k].length <= 20000) && [['interval',500,3000],['loops',1,10],['count',1,30]].every(([k,min,max])=>Number.isInteger(a[k]) && a[k]>=min && a[k]<=max); }
function download(data,name){const blob=new Blob([JSON.stringify(data,null,2)],{type:'application/json'});const url=URL.createObjectURL(blob),a=element('a');a.href=url;a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}

const $ = id => document.getElementById(id);
const fresh = () => ({format:'botcontrol',version:1,name:'Мой бот',scenarios:[],buttons:[]});
const id = () => { const bytes = new Uint8Array(16); crypto.getRandomValues(bytes); return Array.from(bytes, b => b.toString(16).padStart(2, '0')).join(''); };
function validate(x) {
  if (!x || x.format !== 'botcontrol' || x.version !== 1 || typeof x.name !== 'string' || !Array.isArray(x.scenarios) || !Array.isArray(x.buttons)) throw Error('Неподдерживаемый или повреждённый файл .bc.');
  const ids = new Set();
  for (const s of x.scenarios) { if (!s || typeof s.id !== 'string' || !s.id || ids.has(s.id) || typeof s.name !== 'string' || typeof s.text !== 'string' || (s.animation != null && !validAnim(s.animation)) || (s.links != null && (!Array.isArray(s.links) || s.links.length > 3 || s.links.some(x=>!x || typeof x.label !== 'string' || typeof x.url !== 'string'))) || (s.replies != null && (!Array.isArray(s.replies) || s.replies.length > 500 || s.replies.some(x=>typeof x !== 'string' || x.length > 4096))) || (s.trigger != null && (typeof s.trigger !== 'object' || !['command','contains'].includes(s.trigger.type) || typeof s.trigger.value !== 'string' || s.trigger.value.length > 200))) throw Error('Некорректный список сценариев.'); ids.add(s.id); }
  const buttonIds = new Set();
  for (const b of x.buttons) { if (!b || typeof b.id !== 'string' || !b.id || buttonIds.has(b.id) || typeof b.label !== 'string' || typeof b.scenarioId !== 'string' || (b.scenarioId && !ids.has(b.scenarioId))) throw Error('Некорректный список кнопок или привязка сценария.'); buttonIds.add(b.id); }
  if (x.schedule != null && (!Array.isArray(x.schedule) || x.schedule.length > 100 || x.schedule.some(e=>!e || typeof e.id !== 'string' || typeof e.time !== 'string' || typeof e.chatId !== 'string' || !Array.isArray(e.days) || e.days.some(d=>!Number.isInteger(d)||d<0||d>6) || typeof e.scenarioId !== 'string' || (e.scenarioId && !ids.has(e.scenarioId))))) throw Error('Некорректное расписание.');
  return x;
}
let bot = fresh();
try { const stored = localStorage.getItem(KEY); if (stored) bot = validate(JSON.parse(stored)); } catch(e) { $('notice').textContent = 'Не удалось прочитать сохранённые данные. Импортируйте резервную копию .bc.'; }
function save() { try { localStorage.setItem(KEY, JSON.stringify(bot)); } catch(e) { $('notice').textContent = 'Не удалось сохранить изменения в браузере. Экспортируйте резервную копию.'; } }
function element(tag, cls, text) { const e = document.createElement(tag); if(cls)e.className=cls; if(text !== undefined)e.textContent=text; return e; }
function field(label, value, placeholder, callback) { const wrap=element('div'); const l=element('label','',label); const input=element('input'); input.value=value; input.placeholder=placeholder; input.addEventListener('input',()=>callback(input.value)); l.appendChild(input); wrap.appendChild(l); return wrap; }
function render() {
 $('botName').value=bot.name;
 const scenarios=$('scenarios'); scenarios.replaceChildren();
 if(!bot.scenarios.length) scenarios.appendChild(element('div','empty','Пока нет сценариев. Добавьте первый или импортируйте заготовки.'));
 bot.scenarios.forEach(s=>{ const card=element('div','item'), row=element('div','row');
 row.appendChild(field('Название сценария',s.name,'Например, Приветствие',v=>{s.name=v;save(); updateSelects(); updateScheduleSelects();}));
 row.appendChild(element('div')); const del=element('button','delete','Удалить'); del.onclick=()=>{if(!confirm('Удалить сценарий? Привязанные кнопки останутся без сценария.'))return; bot.scenarios=bot.scenarios.filter(x=>x.id!==s.id); bot.buttons.forEach(b=>{if(b.scenarioId===s.id)b.scenarioId='';});(bot.schedule||[]).forEach(e=>{if(e.scenarioId===s.id)e.scenarioId='';});save();render();}; row.appendChild(del); card.appendChild(row);
 const triggerLabel=element('label','','Когда срабатывает сценарий'),triggerSelect=element('select');[['none','Только по кнопке'],['command','Команда Telegram'],['contains','Сообщение содержит фразу']].forEach(([value,title])=>{const opt=element('option','',title);opt.value=value;triggerSelect.appendChild(opt);});triggerSelect.value=s.trigger?.type || 'none';triggerSelect.onchange=()=>{if(triggerSelect.value==='none')delete s.trigger;else s.trigger={type:triggerSelect.value,value:''};save();render();};triggerLabel.appendChild(triggerSelect);card.appendChild(triggerLabel);
 if(s.trigger){card.appendChild(field(s.trigger.type==='command'?'Команда, например /help':'Слово или фраза',s.trigger.value,s.trigger.type==='command'?'/help':'например, привет',v=>{s.trigger.value=v;save();}));card.appendChild(element('p','hint','Сценарий также можно привязать к кнопке.'));}
 const kindWrap=element('label','','Действие сценария'),kind=element('select'); [['text','Текстовый ответ'],['pack','Случайный ответ из набора'],['animation','Анимация']].forEach(([value,title])=>{const opt=element('option','',title);opt.value=value;kind.appendChild(opt);});kind.value=s.animation?'animation':s.replies?'pack':'text';kind.onchange=()=>{delete s.animation;delete s.replies;if(kind.value==='animation')s.animation=newAnim();if(kind.value==='pack')s.replies=[];save();render();};kindWrap.appendChild(kind);card.appendChild(kindWrap);
 if(s.replies){const label=element('label','','Варианты ответа — каждый с новой строки'),text=element('textarea');text.value=s.replies.join('\n');text.placeholder='Первый ответ\nВторой ответ';text.oninput=()=>{s.replies=text.value.split('\n').filter(x=>x.trim());save();};label.appendChild(text);card.appendChild(label);}else if(!s.animation){const text=element('textarea'); text.value=s.text; text.placeholder='Текст ответа'; text.setAttribute('aria-label','Текст ответа сценария'); text.oninput=()=>{s.text=text.value;save();};card.appendChild(text);}else{
 const a=s.animation, presetLabel=element('label','','Шаблон из Android'),preset=element('select'); Object.entries(PRESETS).forEach(([value,title])=>{const opt=element('option','',title);opt.value=value;preset.appendChild(opt);});preset.value=a.preset;preset.onchange=()=>{a.preset=preset.value;save();render();};presetLabel.appendChild(preset);card.appendChild(presetLabel);
 card.appendChild(field('Подпись ({user} — имя пользователя)',a.text,'Текст',v=>{a.text=v;save();}));
 if(a.preset==='custom'){const label=element('label','','Кадры: разделяйте строкой ---'),frames=element('textarea');frames.value=a.frames;frames.placeholder='Первый кадр\n---\nВторой кадр';frames.oninput=()=>{a.frames=frames.value;save();};label.appendChild(frames);card.appendChild(label);}
 const numbers=element('div','row');[['interval','Пауза, мс (500–3000)',500,3000],['loops','Повторы (1–10)',1,10],['count','Отсчёт (1–30)',1,30]].forEach(([key,title,min,max])=>{const l=element('label','',title),input=element('input');input.type='number';input.min=min;input.max=max;input.value=a[key];input.onchange=()=>{a[key]=Math.min(max,Math.max(min,Math.round(Number(input.value)||min)));input.value=a[key];save();};l.appendChild(input);numbers.appendChild(l);});card.appendChild(numbers);
 card.appendChild(field('Итог после анимации',a.final,'Необязательно',v=>{a.final=v;save();}));const exp=element('button','','Экспорт анимации .bca');exp.onclick=()=>download({format:'botcontrol-animation',version:1,animation:a},'animation.bca');card.appendChild(exp);
 }
 const linkHeader=element('h3','','Ссылки под сообщением (до 3)');card.appendChild(linkHeader);(s.links||[]).forEach((link,index)=>{const wrap=element('div','item');wrap.appendChild(field('Надпись ссылки',link.label,'Открыть сайт',v=>{link.label=v;save();}));wrap.appendChild(field('Адрес http или https',link.url,'https://example.com',v=>{link.url=v;save();}));const delLink=element('button','delete','Удалить ссылку');delLink.onclick=()=>{s.links.splice(index,1);save();render();};wrap.appendChild(delLink);card.appendChild(wrap);});if((s.links||[]).length<3){const add=element('button','','＋ Ссылка под сообщением');add.onclick=()=>{if(!s.links)s.links=[];s.links.push({label:'',url:''});save();render();};card.appendChild(add);}scenarios.appendChild(card); });
 const buttons=$('buttons');buttons.replaceChildren();
 if(!bot.buttons.length) buttons.appendChild(element('div','empty','Пока нет кнопок. Добавьте кнопку и привяжите сценарий.'));
 bot.buttons.forEach(b=>{const card=element('div','item'),row=element('div','row');row.appendChild(field('Надпись кнопки',b.label,'Например, Начать',v=>{b.label=v;save();}));const wrap=element('div'),label=element('label','','Сценарий'),select=element('select'); select.dataset.buttonId=b.id; select.onchange=()=>{b.scenarioId=select.value;save();};label.appendChild(select);wrap.appendChild(label);row.appendChild(wrap);const del=element('button','delete','Удалить');del.onclick=()=>{if(!confirm('Удалить кнопку?'))return;bot.buttons=bot.buttons.filter(x=>x.id!==b.id);save();render();};row.appendChild(del);card.appendChild(row);buttons.appendChild(card);}); updateSelects();
 const schedule=$('schedule');schedule.replaceChildren();if(!(bot.schedule||[]).length)schedule.appendChild(element('div','empty','Напоминаний пока нет.'));
 (bot.schedule||[]).forEach(ev=>{const card=element('div','item');card.appendChild(field('Время ЧЧ:ММ',ev.time,'08:55',v=>{ev.time=v;save();}));card.appendChild(field('Числовой chat ID',ev.chatId,'Например, 123456789',v=>{ev.chatId=v;save();}));const l=element('label','','Сценарий'),select=element('select');select.dataset.scheduleId=ev.id;select.onchange=()=>{ev.scenarioId=select.value;save();};l.appendChild(select);card.appendChild(l);const days=element('div','actions');['Пн','Вт','Ср','Чт','Пт','Сб','Вс'].forEach((day,i)=>{const label=element('label'),checkbox=element('input');checkbox.type='checkbox';checkbox.style.width='auto';checkbox.checked=ev.days.includes(i);checkbox.onchange=()=>{ev.days=checkbox.checked?[...ev.days,i]:ev.days.filter(d=>d!==i);save();};label.append(checkbox,document.createTextNode(' '+day));days.appendChild(label);});card.appendChild(days);const del=element('button','delete','Удалить напоминание');del.onclick=()=>{if(!confirm('Удалить напоминание?'))return;bot.schedule=bot.schedule.filter(x=>x.id!==ev.id);save();render();};card.appendChild(del);schedule.appendChild(card);}); updateScheduleSelects();
}
function updateSelects(){document.querySelectorAll('select[data-button-id]').forEach(sel=>{const b=bot.buttons.find(x=>x.id===sel.dataset.buttonId);sel.replaceChildren();const blank=element('option','','Выберите сценарий');blank.value='';sel.appendChild(blank);bot.scenarios.forEach(s=>{const opt=element('option','',s.name || 'Без названия');opt.value=s.id;sel.appendChild(opt);});sel.value=b.scenarioId;});}
function updateScheduleSelects(){document.querySelectorAll('select[data-schedule-id]').forEach(sel=>{const ev=(bot.schedule||[]).find(x=>x.id===sel.dataset.scheduleId);sel.replaceChildren();const blank=element('option','','Выберите сценарий');blank.value='';sel.appendChild(blank);bot.scenarios.forEach(s=>{const opt=element('option','',s.name || 'Без названия');opt.value=s.id;sel.appendChild(opt);});sel.value=ev.scenarioId;});}
$('addSchedule').onclick=()=>{if(!bot.schedule)bot.schedule=[];bot.schedule.push({id:id(),time:'09:00',chatId:'',days:[0,1,2,3,4],scenarioId:''});save();render();};
$('botName').oninput=e=>{bot.name=e.target.value;save();};
$('addScenario').onclick=()=>{bot.scenarios.push({id:id(),name:'Новый сценарий',text:''});save();render();};
$('addButton').onclick=()=>{bot.buttons.push({id:id(),label:'',scenarioId:''});save();render();};
$('importBot').onclick=()=>$('botFile').click();$('importAnswers').onclick=()=>$('answersFile').click();
$('exportBot').onclick=()=>download(bot,'botcontrol-bot.bc');
$('importAnimation').onclick=()=>$('animationFile').click();
$('animationFile').onchange=async e=>{const f=e.target.files[0];e.target.value='';if(!f)return;try{if(f.size>1024*1024)throw Error('Файл слишком большой.');const x=JSON.parse(await f.text());if(x.format!=='botcontrol-animation'||x.version!==1||!validAnim(x.animation))throw Error('Неподдерживаемый файл анимации.');bot.scenarios.push({id:id(),name:'Импортированная анимация',text:'',animation:x.animation});save();render();$('notice').textContent='Анимация добавлена в сценарии.';}catch(err){$('notice').textContent='Ошибка импорта: '+err.message;}};
$('botFile').onchange=async e=>{const f=e.target.files[0];e.target.value='';if(!f)return;try{if(f.size>5*1024*1024)throw Error('Файл слишком большой (максимум 5 МБ).');const data=validate(JSON.parse(await f.text()));if(!confirm('Импорт заменит текущего бота в редакторе. Продолжить?'))return;bot=data;save();render();$('notice').textContent='Бот импортирован.';}catch(err){$('notice').textContent='Ошибка импорта: '+err.message;}};
$('answersFile').onchange=async e=>{const f=e.target.files[0];e.target.value='';if(!f)return;try{if(f.size>5*1024*1024)throw Error('Файл слишком большой (максимум 5 МБ).');const text=(await f.text()).replace(/^\uFEFF/,'').replace(/\r\n?/g,'\n');const matches=[...text.matchAll(/^\s*(\d+)[.)]\s*([^\n]*(?:\n(?!\s*\d+[.)]\s*)[^\n]*)*)/gm)];if(!matches.length)throw Error('Не найдены заготовки. Каждая должна начинаться с номера и точки: 1. Текст');const entries=matches.map(m=>({number:m[1],text:m[2].trim()})).filter(x=>x.text);if(!entries.length)throw Error('Заготовки пусты.');entries.forEach(entry=>bot.scenarios.push({id:id(),name:'Заготовка '+entry.number,text:entry.text}));save();render();$('notice').textContent=`Импортировано заготовок: ${entries.length}.`; }catch(err){$('notice').textContent='Ошибка импорта: '+err.message;}};
render();

async function status() { try {const response=await fetch('/api/status');if(!response.ok)throw Error(); const state=await response.json();const labels={stopped:'Остановлен',starting:'Подключение…',running:'Работает',stopping:'Остановка…'};$('runtimeLog').textContent=(state.logs || []).join('\n') || 'Событий пока нет.';$('runtimeStatus').textContent=(labels[state.state] || state.state)+(state.error ? ' — '+state.error : '');$('startBot').disabled=state.state!=='stopped';$('stopBot').disabled=state.state==='stopped'||state.state==='stopping';}catch(e){$('runtimeStatus').textContent='Нет связи с локальным приложением. Запустите server.py.';} }
async function action(path, data) {try{const response=await fetch(path,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(data)});const result=await response.json();if(!response.ok)throw Error(result.error || 'Ошибка запроса');$('notice').textContent='';await status();}catch(e){$('notice').textContent=e.message;}}
$('startBot').onclick=()=>action('/api/start',{token:$('token').value.trim(),bot});
$('stopBot').onclick=()=>action('/api/stop',{});
status();setInterval(status,3000);

$('simulate').onclick=async()=>{try{const response=await fetch('/api/simulate',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({bot,text:$('simulateInput').value})});const data=await response.json();$('simulateResult').textContent=response.ok ? data.result : 'Ошибка: '+data.error;}catch(e){$('simulateResult').textContent='Нет связи с локальным приложением.';}};

$('importPython').onclick=()=>$('pythonFile').click();
$('pythonFile').onchange=async e=>{const f=e.target.files[0];e.target.value='';if(!f)return;try{if(f.size>512000)throw Error('Файл больше 512 КБ.');const source=await f.text();const response=await fetch('/api/import-python',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({source})});const data=await response.json();if(!response.ok)throw Error(data.error);if(!data.packs.length)throw Error('Буквальные списки ответов не найдены.');if(!confirm(`Найдено списков: ${data.packs.length}. Добавить их как наборы ответов? Команды, кнопки, сценарии и расписание НЕ переносятся.`))return;data.packs.forEach(p=>bot.scenarios.push({id:id(),name:p.name,text:'',replies:p.items}));save();render();$('notice').textContent=`Добавлено наборов: ${data.packs.length}. Остальные настройки .py не импортированы.`;}catch(err){$('notice').textContent='Ошибка импорта: '+err.message;}};

$('generationPrompt').value=GENERATED_PROMPT;
$('copyPrompt').onclick=async()=>{
  const prompt=$('generationPrompt');
  try {
    if (!navigator.clipboard || !window.isSecureContext) throw Error();
    await navigator.clipboard.writeText(prompt.value);
    $('generatedStatus').textContent='Промпт скопирован. Замените описание в его конце.';
  } catch (_) {
    prompt.focus(); prompt.select();
    $('generatedStatus').textContent='Промпт выделен: скопируйте его вручную (Ctrl+C).';
  }
};
async function importGenerated(text) {
  const status=$('generatedStatus');
  try {
    const candidate=validate(parseGenerated(text));
    const response=await fetch('/api/validate-bot', {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({bot:candidate})});
    const result=await response.json();
    if(!response.ok)throw Error(result.error || 'Не удалось проверить настройки.');
    if(!confirm(`Найден бот «${candidate.name}»: сценариев ${candidate.scenarios.length}, кнопок ${candidate.buttons.length}, напоминаний ${(candidate.schedule||[]).length}. Импорт заменит текущего бота в редакторе. Продолжить?`)) {status.textContent='Импорт отменён; текущий бот не изменён.';return;}
    bot=candidate;save();render();
    status.textContent='Бот импортирован. Проверьте сценарии и перезапустите бота, если он работает.';
  } catch(err){status.textContent='Ошибка импорта: '+err.message;}
}
$('importGenerated').onclick=()=>importGenerated($('generatedInput').value);
$('importGeneratedFile').onclick=()=>$('generatedFile').click();
$('generatedFile').onchange=async e=>{const f=e.target.files[0];e.target.value='';if(!f)return;try{if(f.size>900000)throw Error('Файл слишком большой: максимум 900 КБ.');await importGenerated(await f.text());}catch(err){$('generatedStatus').textContent='Ошибка импорта: '+err.message;}};
