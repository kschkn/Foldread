'use strict';

let hingeAngle=180,spineExtra=0,standMode=false,pagePin=null,holdTimer=0,unfoldHit=null,unfoldTimer=0;
let voiceHit=null,voiceSession=null,lastVoiceRevision=null,standAutomatic=false;
function innerDisplay(){return innerWidth>=560 && Math.min(screen.width,screen.height)>=520;}
function pageColumns(){
  if(!innerDisplay()||settings.spreadMode==='one')return 1;
  if(settings.spreadMode==='two')return 2;
  const axis=window.Library?.innerAxis?.()??-1;
  return (axis>=0?axis===0:innerWidth>innerHeight)?2:1;
}
window.foldAxisChanged=()=>{
  if(book && columns!==pageColumns() && !pendingTurn)onReaderResize();
};
const desiredSpine=()=>settings.liveSpine && hingeAngle<150 && hingeAngle>=55?Math.round((155-hingeAngle)/12)*3:0;
const spineChanged=()=>spineExtra!==desiredSpine();
function applySpine(){spineExtra=desiredSpine();document.documentElement.style.setProperty('--gutter',(settings.gutter+spineExtra)+'px');}
window.hingeChanged=angle=>{
  hingeAngle=angle;
  if(pair?.peek && !$('#sheet').open){
    if(angle>=163){revealTranslation(true);}
    else if(angle<145){revealTranslation(false);}
  }
};
window.tabletopChanged=(half,fraction)=>{
  document.documentElement.style.setProperty('--table-half',(Math.min(.72,Math.max(.28,fraction||.5))*100)+'dvh');
  if(half && settings.autoStand && book && !standMode && !pair && !fanSession && !voiceSession && !pickingText && !$('#sheet').open)startStand(true);
  if(!half && standAutomatic)endStand();
};
function foldSettings(){
  const area=panel('Настройки Fold');
  for(const [key,title,hint] of [
    ['liveSpine','Поле у шарнира','Увеличивать отступ на следующем развороте, когда устройство согнуто.'],
    ['thumbZones','Вперёд с двух сторон','Оба нижних внешних края перелистывают вперёд. Свайп вправо возвращает назад.'],
    ['autoStand','Подставка автоматически','В горизонтальном полусгибе текст занимает верхнюю половину.'],
    ['unfoldLight','Подсветка при раскрытии','Показать начало прежней страницы после перехода с внешнего экрана.']
  ]){
    const b=row(title,hint,el('span','switchIcon'),()=>{settings[key]=!enabled();write('settings',settings);foldSettings();syncFold();});
    const enabled=()=>key==='unfoldLight'?settings[key]!==false:!!settings[key];
    b.setAttribute('aria-pressed',enabled());area.append(b);
  }
  area.append(row('Страницы на внутреннем экране',spreadDescription(),icon('book'),()=>showTypography('Страница')));
  if(window.Library?.hasHinge?.())area.append(row('Направление сгиба',settings.foldDirection===-1?'Назад':'Вперёд',icon(settings.foldDirection===-1?'left':'right'),()=>{
    settings.foldDirection=settings.foldDirection===-1?1:-1;write('settings',settings);foldSettings();
  }));
  area.append(button('Как листать и удерживать место','toolRow',gestureGuide));
}
function clearReadingHold(){clearTimeout(holdTimer);}
function armReadingHold(e){
  clearReadingHold();if(e.clientY>innerHeight-48||pickingText||pagePin||noteOpen())return;
  const {pointerId,clientX}=e,para=e.target.closest('#leaves p');
  if(innerDisplay() && Math.min(clientX,innerWidth-clientX)<Math.min(42,innerWidth*.06)){
    holdTimer=setTimeout(()=>{
      if(touch?.id!==pointerId||touch.drag||touch.light)return;
      pagePin={id:pointerId,at:{...position}};touch=null;
      const hint=el('div','pinHint','Место удерживается · листайте второй рукой');hint.id='pinHint';hint.setAttribute('role','status');document.body.append(hint);
    },440);
  }else if(para){
    const word=wordAtPoint(e,para);
    holdTimer=setTimeout(()=>{if(touch?.id!==pointerId||touch.drag||!word.text)return;touch=null;wordMenu(word);},540);
  }
}
function releasePin(){
  if(!pagePin)return;
  const at=pagePin.at;pagePin=null;touch=null;$('#pinHint')?.remove();cancelTurn();chapter=at.chapter;layout(at);
}
function onReaderResize(){
  clearReadingHold();releasePin();if(fanSession)endFan(false);touch=null;clearFanTimer();clearTimeout(resizeTimer);
  const was=columns,at=book&&model?{...anchor(page)}:null;
  resizeTimer=setTimeout(()=>{
    if(!at || !book)return;
    layout(position);
    if(was===1 && columns===2 && settings.unfoldLight!==false && !$('#sheet').open){
      const line=book.chapters[at.chapter]?.paragraphs[at.p];
      if(line){unfoldHit={...at,end:Math.min(line.length,at.offset+55)};render();
        clearTimeout(unfoldTimer);unfoldTimer=setTimeout(()=>{unfoldHit=null;if(!pendingTurn)render();},1800);}
    }
    showPocketIfNarrow();
  },90);
}
function startStand(auto=false){
  if(standMode)return;
  const w=desk('Режим подставки',['Текст','Управление']);standMode=true;standAutomatic=auto===true;
  $('#sheet').classList.add('standSheet');w.body.classList.add('standDesk');
  const pane=readingPane(w,w.sides[0],book,position,true);
  const controls=w.sides[1];controls.append(el('h3','deskTitle',book.title));
  const nav=el('div','standNav');controls.append(nav);
  nav.append(button('‹ Назад','',()=>pane.step(-1)),button('Дальше ›','',()=>pane.step(1)));
  controls.append(button('Обычный разворот','textLink',endStand));
  w.cleanup=()=>{standMode=false;standAutomatic=false;$('#sheet').classList.remove('standSheet');};syncFold();
}
function endStand(){if(standMode){closeSheet();disposeWorkspace();}}
function pocketEditor(text){
  const area=panel('На внешний экран'),field=el('textarea','entryField');
  field.value=text;area.append(field,button('Показать при складывании','primary',()=>{
    if(!field.value.trim())return;
    write('pocket',{id:book.id,text:field.value.trim(),title:book.title});closeSheet();
    if(!innerDisplay())showPocketIfNarrow();else notify('Сложите телефон, чтобы увидеть цитату');
  }));
}
function dismissPocket(){localStorage.removeItem('pocket');$('#pocketCard')?.remove();}
function showPocketIfNarrow(){
  const card=read('pocket',null);
  if(innerDisplay()){if($('#pocketCard'))dismissPocket();return;}
  if(!card || card.id!==book?.id || $('#pocketCard'))return;
  const shell=el('section','pocketCard');shell.id='pocketCard';
  shell.append(el('small','settingLabel',card.title),el('blockquote','',card.text),button('К чтению','solidAction',dismissPocket));document.body.append(shell);
}
function wordAtPoint(e,para){
  const p=Number(para.dataset.paragraph),from=Number(para.dataset.from)||0;
  const range=document.caretRangeFromPoint?.(e.clientX,e.clientY);let offset=from;
  if(range && para.contains(range.startContainer)){
    const before=range.cloneRange();before.selectNodeContents(para);before.setEnd(range.startContainer,range.startOffset);offset+=before.toString().length;
  }
  const line=textOf().paragraphs[p];
  const words=[...line.matchAll(/[\p{L}\p{M}]+(?:[-’'][\p{L}\p{M}]+)*/gu)];
  const match=words.find(m=>m.index<=offset&&offset<=m.index+m[0].length)||words.find(m=>m.index>=offset);
  return {text:match?.[0]||'',chapter,p,offset:match?.index??offset};
}
function pickWord(mode){pickText(mode);}
function wordMenu(word){
  const area=panel(word.text);
  area.append(button('Кто это? Упоминания до этого места','toolRow',()=>wordDetails('name',word)),button('Значение и словарь','toolRow',()=>wordDetails('word',word)),button('Слушать с этой фразы','toolRow',()=>beginSpeech(word)));
}
function priorMentions(q,at){
  const first=[],last=[];if(!q.trim())return [];
  const needle=q.trim().toLocaleLowerCase('ru');
  for(let c=0;c<=at.chapter;c++){
    const lines=book.chapters[c].paragraphs;
    for(let p=0;p<lines.length&&(c<at.chapter||p<=at.p);p++){
      const text=c===at.chapter && p===at.p?lines[p].slice(0,at.offset):lines[p];
      const i=text.toLocaleLowerCase('ru').indexOf(needle);if(i<0)continue;
      const item={chapter:c,p,offset:i,snippet:text.slice(Math.max(0,i-48),i+needle.length+90)};
      if(!first.length)first.push(item);else{last.push(item);if(last.length>3)last.shift();}
    }
  }return first.concat(last);
}
const glossary={бриг:'Двухмачтовое парусное судно с прямым парусным вооружением.',брига:'Родительный падеж слова «бриг». Двухмачтовое парусное судно.',киль:'Продольная балка в основании корпуса судна.',киля:'Родительный падеж слова «киль». Основание корпуса судна.',рей:'Поперечный брус на мачте для крепления паруса.',шхуна:'Парусное судно с косыми парусами на двух или нескольких мачтах.',матрос:'Член судовой команды.',гавань:'Защищённая часть акватории для стоянки судов.',фрегат:'Парусный военный корабль с тремя мачтами.',крен:'Наклон судна на один борт.',румпель:'Рычаг для поворота судового руля.',лоцман:'Специалист, проводящий суда по знакомому ему участку пути.',бушприт:'Наклонный брус, выступающий вперёд с носа судна.',мачта:'Вертикальная конструкция для парусов и судового оборудования.',такелаж:'Совокупность снастей судна.'};
function wordDetails(mode,at){
  const area=panel(mode==='name'?'Упоминания до этого места':'Словарь книги'),input=el('input','bookSearch'),result=el('div');
  input.value=at.text;input.setAttribute('aria-label','Имя или слово');area.append(input,result);
  const context=book.chapters[at.chapter].paragraphs[at.p];
  function show(){result.replaceChildren();const q=input.value.trim();if(!q)return;
    if(mode==='name'){
      const list=priorMentions(q,at);if(!list.length)result.append(el('p','empty','Раньше в тексте такое написание не встречалось.'));
      list.forEach((v,i)=>result.append(row((i?'Недавнее упоминание':'Первое упоминание')+' · '+book.chapters[v.chapter].title,v.snippet,icon('book'),()=>jump(v))));
    }else{
      const lexicon=read('lexicon',{}),key=q.toLocaleLowerCase('ru'),meaning=el('textarea','entryField');
      const entry=JSON.parse(window.Library?.definition?.(q)||'{}'),custom=Object.hasOwn(lexicon,key),brief=Object.hasOwn(glossary,key);
      meaning.value=custom?lexicon[key]:entry.text||(brief?glossary[key]:'');
      result.append(el('p','dictionarySource',custom?'Ваш словарь':entry.text?'В. И. Даль · Толковый словарь':brief?'Морской словарь Bookfold':'Точной статьи нет. Попробуйте начальную форму слова.'));
      if(!meaning.value && entry.suggestions?.length){
        const choices=el('div','wordSuggestions');
        for(const word of entry.suggestions)choices.append(button(word,'',()=>{input.value=word;show();}));
        result.append(choices);
      }
      meaning.placeholder='Своё толкование';meaning.setAttribute('aria-label','Значение слова');
      result.append(meaning,el('blockquote','quoteExcerpt',context.slice(Math.max(0,at.offset-80),at.offset+160)),button('Сохранить с примером','primary',()=>{
      if(!meaning.value.trim()){meaning.focus();return;}
        const saved=read('words.'+book.id,[]);saved.push({...at,text:q,meaning:meaning.value.trim()});write('words.'+book.id,saved);wordShelf();
      }));
    }
  }input.oninput=show;show();
}
function wordShelf(){
  const area=panel('Словарь книги');
  area.append(el('p','settingHint','Толковый словарь Даля уже на устройстве. Удерживайте слово на странице, чтобы прочитать статью и сохранить пример из книги.'),
    button('Выбрать слово в тексте','primary',()=>pickWord('word')),button('Подключить свой словарь','toolRow',()=>Library.pickDictionary()),el('p','settingHint','Поддерживается TSV в UTF-8. Слово и значение разделяются табуляцией.'));
  for(const v of read('words.'+book.id,[]))area.append(row(v.text,v.meaning,icon('bookmark'),()=>jump(v)));
}
window.dictionaryLoaded=text=>{
  const entries=new Map(Object.entries(read('lexicon',{})));
  for(const line of text.split(/\r?\n/)){
    const tab=line.indexOf('\t');if(tab<1)continue;const word=line.slice(0,tab).trim().toLowerCase(),meaning=line.slice(tab+1).trim();if(word&&meaning)entries.set(word,meaning);
  }
  try{write('lexicon',Object.fromEntries(entries));notify('Слов в словаре: '+entries.size);}catch{notify('Словарь слишком большой');}
};

function speechMenu(){
  const area=panel('Слушать книгу');
  const box=el('input');box.type='checkbox';box.checked=settings.stopAtChapter===true;
  box.onchange=()=>{settings.stopAtChapter=box.checked;write('settings',settings);};
  const check=el('label','checkLine');check.append(box,document.createTextNode('Пауза в конце главы'));
  area.append(el('p','settingHint','Озвучивание продолжается при блокировке. Пауза и выбор главы доступны на экране блокировки и в Android Auto.'),
    check,button('Слушать с текущего места','primary',()=>beginSpeech(position)),button('Выбрать место в тексте','toolRow',()=>pickText('listen')));
}
function beginSpeech(at){
  closeSheet();Library.playBook(book.id,at.chapter,at.p,at.offset,settings.stopAtChapter===true);
}
function syncSpeech(){
  if(!book)return;
  const s=JSON.parse(window.Library?.audioState?.()||'{}');
  if(lastVoiceRevision===s.revision)return;
  lastVoiceRevision=s.revision;
  const same=s.book===book.id,playing=same&&s.state==='playing',visible=same&&['loading','playing','paused'].includes(s.state);
  voiceSession=playing?s:null;
  const wasHit=voiceHit;
  voiceHit=playing?{chapter:s.chapter,p:s.p,offset:s.offset,end:s.end}:null;
  if(playing && !document.hidden && !$('#sheet').open && !pagePin && !fanSession && !pair){
    cancelTurn();const at={chapter:s.chapter,p:s.p,offset:s.offset};
    if(chapter!==at.chapter){chapter=at.chapter;layout(at);}
    else{page=alignPage(pageOf(at));render();}
    position=at;write('pos.'+book.id,at);
  }else if(wasHit&&!pendingTurn)render();
  let bar=$('#voiceBar');
  if(!visible){bar?.remove();return;}
  if(!bar){bar=el('div','voiceBar');bar.id='voiceBar';bar.setAttribute('aria-label','Управление чтением вслух');document.body.append(bar);}
  const stateKey=s.state+'|'+s.message;
  if(bar.dataset.state===stateKey)return;
  bar.dataset.state=stateKey;bar.replaceChildren();
  const paused=s.state==='paused';
  bar.append(el('span','',s.message|| (paused?'Пауза':s.state==='loading'?'Открываем книгу…':'Чтение вслух')),
    button(paused?'Продолжить':'Пауза','',()=>Library.audioControl(paused?'play':'pause')),button('Стоп','',()=>Library.audioControl('stop')));
}
window.syncSpeech=syncSpeech;
window.audioOpened=()=>{
  if(!book)return;
  const id=Library.audioTarget();
  if(id&&id!==book.id){openBook(id);return;}
  if(id)closeSheet();
  lastVoiceRevision=null;syncSpeech();
};
document.addEventListener('visibilitychange',()=>{if(!document.hidden && book){lastVoiceRevision=null;syncSpeech();}});
