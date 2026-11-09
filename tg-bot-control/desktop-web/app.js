'use strict';
const KEY = 'botcontrol-editor-v1';
const $ = id => document.getElementById(id);
const fresh = () => ({format:'botcontrol',version:1,name:'Мой бот',scenarios:[],buttons:[]});
const id = () => crypto.randomUUID();
function validate(x) {
  if (!x || x.format !== 'botcontrol' || x.version !== 1 || typeof x.name !== 'string' || !Array.isArray(x.scenarios) || !Array.isArray(x.buttons)) throw Error('Неподдерживаемый или повреждённый файл .bc.');
  const ids = new Set();
  for (const s of x.scenarios) { if (!s || typeof s.id !== 'string' || !s.id || ids.has(s.id) || typeof s.name !== 'string' || typeof s.text !== 'string') throw Error('Некорректный список сценариев.'); ids.add(s.id); }
  const buttonIds = new Set();
  for (const b of x.buttons) { if (!b || typeof b.id !== 'string' || !b.id || buttonIds.has(b.id) || typeof b.label !== 'string' || typeof b.scenarioId !== 'string' || (b.scenarioId && !ids.has(b.scenarioId))) throw Error('Некорректный список кнопок или привязка сценария.'); buttonIds.add(b.id); }
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
 row.appendChild(field('Название сценария',s.name,'Например, Приветствие',v=>{s.name=v;save(); updateSelects();}));
 row.appendChild(element('div')); const del=element('button','delete','Удалить'); del.onclick=()=>{if(!confirm('Удалить сценарий? Привязанные кнопки останутся без сценария.'))return; bot.scenarios=bot.scenarios.filter(x=>x.id!==s.id); bot.buttons.forEach(b=>{if(b.scenarioId===s.id)b.scenarioId='';});save();render();}; row.appendChild(del); card.appendChild(row);
 const text=element('textarea'); text.value=s.text; text.placeholder='Текст ответа'; text.setAttribute('aria-label','Текст ответа сценария'); text.oninput=()=>{s.text=text.value;save();};card.appendChild(text);scenarios.appendChild(card); });
 const buttons=$('buttons');buttons.replaceChildren();
 if(!bot.buttons.length) buttons.appendChild(element('div','empty','Пока нет кнопок. Добавьте кнопку и привяжите сценарий.'));
 bot.buttons.forEach(b=>{const card=element('div','item'),row=element('div','row');row.appendChild(field('Надпись кнопки',b.label,'Например, Начать',v=>{b.label=v;save();}));const wrap=element('div'),label=element('label','','Сценарий'),select=element('select'); select.dataset.buttonId=b.id; select.onchange=()=>{b.scenarioId=select.value;save();};label.appendChild(select);wrap.appendChild(label);row.appendChild(wrap);const del=element('button','delete','Удалить');del.onclick=()=>{if(!confirm('Удалить кнопку?'))return;bot.buttons=bot.buttons.filter(x=>x.id!==b.id);save();render();};row.appendChild(del);card.appendChild(row);buttons.appendChild(card);}); updateSelects();
}
function updateSelects(){document.querySelectorAll('select[data-button-id]').forEach(sel=>{const b=bot.buttons.find(x=>x.id===sel.dataset.buttonId);sel.replaceChildren();const blank=element('option','','Выберите сценарий');blank.value='';sel.appendChild(blank);bot.scenarios.forEach(s=>{const opt=element('option','',s.name || 'Без названия');opt.value=s.id;sel.appendChild(opt);});sel.value=b.scenarioId;});}
$('botName').oninput=e=>{bot.name=e.target.value;save();};
$('addScenario').onclick=()=>{bot.scenarios.push({id:id(),name:'Новый сценарий',text:''});save();render();};
$('addButton').onclick=()=>{bot.buttons.push({id:id(),label:'',scenarioId:''});save();render();};
$('importBot').onclick=()=>$('botFile').click();$('importAnswers').onclick=()=>$('answersFile').click();
$('exportBot').onclick=()=>{const blob=new Blob([JSON.stringify(bot,null,2)],{type:'application/json'});const url=URL.createObjectURL(blob),a=element('a');a.href=url;a.download='botcontrol-bot.bc';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);};
$('botFile').onchange=async e=>{const f=e.target.files[0];e.target.value='';if(!f)return;try{if(f.size>5*1024*1024)throw Error('Файл слишком большой (максимум 5 МБ).');const data=validate(JSON.parse(await f.text()));if(!confirm('Импорт заменит текущего бота в редакторе. Продолжить?'))return;bot=data;save();render();$('notice').textContent='Бот импортирован.';}catch(err){$('notice').textContent='Ошибка импорта: '+err.message;}};
$('answersFile').onchange=async e=>{const f=e.target.files[0];e.target.value='';if(!f)return;try{if(f.size>5*1024*1024)throw Error('Файл слишком большой (максимум 5 МБ).');const text=(await f.text()).replace(/^\uFEFF/,'').replace(/\r\n?/g,'\n');const matches=[...text.matchAll(/^\s*(\d+)[.)]\s*([^\n]*(?:\n(?!\s*\d+[.)]\s*)[^\n]*)*)/gm)];if(!matches.length)throw Error('Не найдены заготовки. Каждая должна начинаться с номера и точки: 1. Текст');const entries=matches.map(m=>({number:m[1],text:m[2].trim()})).filter(x=>x.text);if(!entries.length)throw Error('Заготовки пусты.');entries.forEach(entry=>bot.scenarios.push({id:id(),name:'Заготовка '+entry.number,text:entry.text}));save();render();$('notice').textContent=`Импортировано заготовок: ${entries.length}.`; }catch(err){$('notice').textContent='Ошибка импорта: '+err.message;}};
render();

async function status() { try {const response=await fetch('/api/status');if(!response.ok)throw Error(); const state=await response.json();const labels={stopped:'Остановлен',starting:'Подключение…',running:'Работает',stopping:'Остановка…'};$('runtimeStatus').textContent=(labels[state.state] || state.state)+(state.error ? ' — '+state.error : '');$('startBot').disabled=state.state!=='stopped';$('stopBot').disabled=state.state==='stopped'||state.state==='stopping';}catch(e){$('runtimeStatus').textContent='Нет связи с локальным приложением. Запустите server.py.';} }
async function action(path, data) {try{const response=await fetch(path,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(data)});const result=await response.json();if(!response.ok)throw Error(result.error || 'Ошибка запроса');$('notice').textContent='';await status();}catch(e){$('notice').textContent=e.message;}}
$('startBot').onclick=()=>action('/api/start',{token:$('token').value.trim(),bot});
$('stopBot').onclick=()=>action('/api/stop',{});
status();setInterval(status,3000);
