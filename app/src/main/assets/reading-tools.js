'use strict';

const fontNames=['Spectral','Literata','PT Serif','Roboto'];
let heldPlace=null, searchHit=null, paragraphOffsets=[], readingSince=0, pace=null;
let previewFrame=0;

function restoreReadingTools(id) {
  heldPlace=read('held.'+id,null);
  $('#returnPlace').hidden=!heldPlace;
  searchHit=null;
  pace=read('readingPace',null);
  paragraphOffsets=book.chapters.map(({paragraphs})=>{
    let total=0;
    return paragraphs.map(text=>{const start=total;total+=text.length;return start;});
  });
  restartReadingClock();
}

function holdPlace() {
  if(heldPlace) return;
  heldPlace={...position};
  write('held.'+book.id,heldPlace);
  $('#returnPlace').hidden=false;
}

function returnToPlace() {
  if(!heldPlace || animation) return;
  const at=heldPlace;
  heldPlace=null;searchHit=null;
  localStorage.removeItem('held.'+book.id);
  $('#returnPlace').hidden=true;
  jump(at,false);
}

function searchBook(area,onResult=null) {
  const input=el('input','bookSearch');
  input.type='search';input.placeholder='Найти в книге';input.setAttribute('aria-label','Найти в книге');
  const results=el('div');
  area.append(input,results);
  let timer;
  input.oninput=()=>{
    clearTimeout(timer);
    timer=setTimeout(()=>{
      results.replaceChildren();
      const query=input.value.trim();
      if(!query || !area.contains(input)) return;
      const escaped=Array.from(query,ch=>'\\^$.*+?()[]{}|'.includes(ch)?'\\'+ch:ch).join('');
      const pattern=new RegExp(escaped,'giu');
      let count=0;
      for(let c=0;c<book.chapters.length;c++) {
        const section=book.chapters[c];
        for(let p=0;p<section.paragraphs.length;p++) {
          const text=section.paragraphs[p];
          pattern.lastIndex=0;
          for(let found;(found=pattern.exec(text));) {
            const offset=found.index;
            const snippet=(offset>24?'…':'')+text.slice(Math.max(0,offset-24),offset+query.length+44);
            results.append(row(section.title,snippet,el('span','ordinal',numeral(c)),()=>{
              const hit={chapter:c,p,offset,end:offset+query.length};
              input.blur();
              if(onResult)onResult(hit);else{searchHit=hit;jump(hit);}
            }));
            if(++count===60) {
              results.append(el('p','settingHint','Показаны первые 60 совпадений.'));return;
            }
          }
        }
      }
      if(!count) results.append(el('p','empty','Совпадений нет.'));
    },160);
  };
  input.focus();
}

// Links and highlights use exactly the same glyphs and font metrics as the measurement flow.
function decorateExcerpt(paragraph,p,from,excerpt) {
  const ranges=(textOf().refs?.[p]||[]).filter(ref=>Object.hasOwn(book.notes||{},ref.key));
  for(const hit of [unfoldHit,voiceHit])
    if(hit?.chapter===chapter && hit.p===p)ranges.push({start:hit.offset,end:hit.end,highlight:true});
  if(searchHit?.chapter===chapter && searchHit.p===p)
    ranges.push({start:searchHit.offset,end:searchHit.end,highlight:true});
  const end=from+excerpt.length;
  const relevant=ranges.filter(r=>r.end>from && r.start<end).sort((a,b)=>a.start-b.start);
  if(!relevant.length) return;
  paragraph.replaceChildren();
  let pos=from;
  for(const ref of relevant) {
    const start=Math.max(from,ref.start),stop=Math.min(end,ref.end);
    if(start<pos) continue;
    paragraph.append(excerpt.slice(pos-from,start-from));
    const label=excerpt.slice(start-from,stop-from);
    const span=el(ref.highlight?'mark':'span',ref.highlight?'found':'note-ref',label);
    if(!ref.highlight) {
      span.tabIndex=0;span.setAttribute('role','button');
      span.setAttribute('aria-label','Сноска '+label);
      span.onclick=()=>showNote(span,ref.key,label);
      span.onkeydown=e=>{if(e.key==='Enter'||e.key===' '){e.preventDefault();span.click();}};
    }
    paragraph.append(span);pos=stop;
  }
  paragraph.append(excerpt.slice(pos-from));
}
function noteOpen() {return Boolean($('.note-card'));}
function closeNote() {
  const card=$('.note-card');
  if(!card) return false;
  card.remove();syncFold();restartReadingClock();return true;
}
function showNote(link,key,label) {
  if(animation) return;
  closeNote();
  const right=link.closest('.page').classList.contains('left');
  const card=el('aside','note-card '+(columns===1?'single':right?'on-right':'on-left'));
  card.setAttribute('role','dialog');card.setAttribute('aria-label','Сноска '+label);
  const header=el('div','note-heading');
  const close=button('','round',()=>{closeNote();if(link.isConnected)link.focus({preventScroll:true});});
  close.append(icon('close'));close.setAttribute('aria-label','Закрыть сноску');
  header.append(el('span','settingLabel','Сноска '+label),close);
  card.append(header,el('div','note-text',book.notes[key]));
  $('#spread').append(card);
  syncFold();restartReadingClock();close.focus({preventScroll:true});
}

function restartReadingClock() {readingSince=performance.now();}
function offsetInChapter(at) {
  return (paragraphOffsets[at.chapter][at.p] ?? chapterLengths[at.chapter])+at.offset;
}
function readingSample(direction) {
  if(direction<0 || document.hidden || !document.body.classList.contains('reading') || $('#sheet').open || noteOpen()) return null;
  const seconds=(performance.now()-readingSince)/1000;
  const chars=offsetInChapter(anchor(page+columns))-offsetInChapter(anchor(page));
  const perMinute=chars*60/seconds;
  if(seconds<4 || seconds>420 || perMinute<120 || perMinute>4200) return null;
  return perMinute;
}
function acceptReadingSample(value) {
  if(!value) return;
  pace=pace ? {rate:pace.rate*.75+value*.25,count:pace.count+1} : {rate:value,count:1};
  write('readingPace',pace);
  updateEstimate();
}
function minutesLeft() {
  const remaining=chapterLengths[chapter]-offsetInChapter(anchor(page));
  return Math.max(1,Math.ceil(remaining/(pace?.rate || 1050)));
}
function updateEstimate() {
  $('#estimate').textContent='≈ '+minutesLeft()+' мин';
}
function showTimeLeft() {
  const area=panel('До конца главы');
  area.append(el('p','minutes','≈ '+minutesLeft()+' мин'),el('p','settingHint',pace
    ? 'Оценка по вашему темпу чтения разворотов.'
    : 'Предварительная оценка. После нескольких прочитанных страниц она подстроится под ваш темп.'));
}

function setBrightness(value) {
  settings.brightness=value;
  window.Library?.setReaderBrightness?.(value);
}
function adjustBrightness(gesture,dy) {
  if(!gesture.light) {
    gesture.light=true;
    gesture.initialBrightness=settings.brightness<0?(window.Library?.systemBrightness?.() ?? 50):settings.brightness;
  }
  const value=Math.max(2,Math.min(100,Math.round(gesture.initialBrightness-dy*130/innerHeight)));
  if(value!==settings.brightness) setBrightness(value);
  notify('Яркость · '+value+' %');
}

function reflowSettings(values) {
  Object.assign(settings,values);
  write('settings',settings);
  applySettings();
  cancelAnimationFrame(previewFrame);
  previewFrame=requestAnimationFrame(()=>layout(position));
}

function spreadDescription(){
  return settings.spreadMode==='one'?'Одна страница в обеих ориентациях.':settings.spreadMode==='two'?'Две страницы в обеих ориентациях.':'Горизонтальный сгиб — одна страница, вертикальный — две.';
}
function paintTypePreview(preview){
  const count=pageColumns();
  preview.dataset.columns=count;
  const lines=textOf().paragraphs,from=position.p;
  preview.replaceChildren();
  for(let i=0;i<count;i++){
    const leaf=el('div','previewLeaf');
    leaf.append(el('small','',textOf().title),el('p','',lines[(from+i)%lines.length]));
    preview.append(leaf);
  }
}
function showTypography(tab='Страница'){
  const area=panel('Настройки чтения');
  $('#sheet').classList.add('typesheet');
  const aside=el('section','typeSample'), preview=el('div','typePreview');
  aside.append(el('span','settingLabel','Так выглядит страница'),preview);area.append(aside);
  paintTypePreview(preview);
  const controls=el('section','typeControls'),tabs=el('div','tabs settingTabs');
  for(const name of ['Страница','Шрифт','Экран']){
    const t=button(name,name===tab?'selected':'',()=>showTypography(name));t.setAttribute('aria-pressed',name===tab);tabs.append(t);
  }
  controls.append(tabs);area.append(controls);
  function slider(title,key,min,max,step,suffix='',target=controls){
    const label=el('label','sliderRow'),caption=el('span','sliderTitle',title),value=el('output','',String(settings[key])+suffix);
    const input=el('input');input.type='range';input.min=min;input.max=max;input.step=step;input.value=settings[key];input.setAttribute('aria-label',title);
    input.oninput=()=>{value.textContent=input.value+suffix;reflowSettings({[key]:Number(input.value)});};
    caption.append(value);label.append(caption,input);target.append(label);
  }
  function choice(title,key,items,target=controls){
    const label=el('span','settingLabel',title),group=el('div','choices');
    group.setAttribute('role','group');group.setAttribute('aria-label',title);
    for(const [name,val] of items){
      const option=button(name,settings[key]===val?'selected':'',()=>{
        reflowSettings({[key]:val});paintTypePreview(preview);
        for(const child of group.children){child.classList.toggle('selected',child===option);child.setAttribute('aria-pressed',child===option);}
        if(key==='spreadMode')guide.textContent=spreadDescription()+' На внешнем экране всегда одна.';
      });
      option.setAttribute('aria-pressed',settings[key]===val);group.append(option);
    }
    target.append(label,group);
  }
  const guide=el('p','settingHint');
  if(tab==='Страница'){
    choice('Страницы на внутреннем экране','spreadMode',[['Авто','auto'],['Одна','one'],['Две','two']]);
    guide.textContent=spreadDescription()+' На внешнем экране всегда одна.';controls.append(guide);
    for(const [key,title,description] of [
      ['progressLine','Строка прогресса','Под книгой · страницы и процент'],
      ['pageNumbers','Номера на страницах','Отдельная цифра внизу каждого листа']
    ]){
      const toggle=button('','displayToggle',()=>{
        reflowSettings({[key]:!settings[key]});toggle.setAttribute('aria-checked',settings[key]);
      });
      const copy=el('span');copy.append(el('b','',title),el('small','',description));
      toggle.setAttribute('role','switch');toggle.setAttribute('aria-checked',settings[key]);
      toggle.append(copy,el('i','toggleTrack'));controls.append(toggle);
    }
    slider('Боковые поля','outside',0,60,1,' dp');
    slider('Сверху и снизу','vertical',0,72,1,' dp');
    slider('Поле у сгиба','gutter',0,60,1,' dp');
    const details=el('details','typeDetails');details.append(el('summary','','Вёрстка абзаца'));controls.append(details);
    slider('Межстрочный интервал','leading',1.15,2,.05,' ×',details);
    slider('Отступ первой строки','indent',0,2,.1,' em',details);
    choice('Выравнивание','justify',[['По краю',false],['По ширине',true]],details);
    choice('Переносы слов','hyphenate',[['Выключены',false],['Включены',true]],details);
  }else if(tab==='Шрифт'){
    slider('Размер текста','font',14,32,1);
    for(const name of fontNames){
      const face=button('','fontOption',()=>{
        reflowSettings({family:name});
        document.fonts.load('18px "'+name+'"').then(()=>{if(settings.family===name)layout(position);});
        for(const other of controls.querySelectorAll('.fontOption'))other.setAttribute('aria-pressed',other===face);
      });
      face.setAttribute('aria-pressed',settings.family===name);
      face.append(el('small','',name),el('span','','Ветер шевелил страницы.'));face.lastChild.style.fontFamily='"'+name+'"';controls.append(face);
    }
  }else{
    choice('Оформление','night',[['Бумага',false],['Ночь',true]]);
    if(window.Library?.hasHinge?.())choice('Листать сгибанием','fold',[['Да',true],['Нет',false]]);
    for(const [name,action] of [['Анимация перелистывания',animationSettings],['Жесты и настройки Fold',foldSettings],['Профили чтения',profileSettings]])
      controls.append(button(name,'toolRow settingsLink',action));
    controls.append(el('p','settingHint','Чтобы изменить яркость, проведите вверх или вниз вдоль левого края страницы.'));
    const reset=button(settings.brightness<0?'Яркость как в системе':'Вернуть системную яркость','toolRow',()=>{setBrightness(-1);write('settings',settings);showTypography(tab);});
    reset.disabled=settings.brightness<0;controls.append(reset);
  }
  controls.append(button('О приложении','aboutLink settingsLink',()=>aboutBookfold(tab)));
}
function aboutBookfold(previous='Страница'){
  const area=panel('О приложении'),hero=el('div','aboutBook'),image=el('img');
  image.src='bookfold-icon.png';image.alt='';
  const version=JSON.parse(window.Library?.appVersion?.()||'{}'),copy=el('div');
  copy.append(el('h3','','Bookfold'),el('p','',version.name?'Версия '+version.name+' · сборка '+version.build:'Читалка для Android'));
  hero.append(image,copy);area.append(hero,el('p','aboutDescription','Читалка FB2 и EPUB для складных экранов. Книги, закладки и заметки хранятся на устройстве.'));
  for(const [key,title,caption] of [
    ['author','Автор','@kschkn на GitHub'],['source','Исходный код','Репозиторий проекта'],['release','Версии и обновления','Скачать APK и прочитать об изменениях'],
    ['issues','Сообщить об ошибке','Открыть задачи проекта на GitHub'],['credits','Материалы и лицензии','Шрифты, словарь и библиотеки']
  ]){
    const link=button('','toolChoice aboutItem',()=>Library.openProjectLink(key));
    link.append(el('b','',title),el('small','',caption));area.append(link);
  }
  area.append(button('‹ Настройки чтения','backlink',()=>showTypography(previous)));
}
