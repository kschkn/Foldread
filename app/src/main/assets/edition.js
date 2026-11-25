'use strict';

const cloth=['#386358','#814839','#35576a','#725570','#74643c','#495777'];
const noteCache=new Map();
let pickingText='',fanTimer=0,fanSession=null;
function colour(item) {
  if(/^#[0-9a-f]{6}$/i.test(item.accent||'')) return item.accent;
  let n=0;for(const ch of item.title) n=(n*31+ch.charCodeAt(0))>>>0;
  return cloth[n%cloth.length];
}
function coverArt(item) {
  const cover=el('span','jacket');cover.style.setProperty('--cloth',colour(item));
  const clothTitle=el('strong','',item.title);
  cover.append(el('small','',item.author),clothTitle,el('i','coverRule','✦'));
  if(item.cover) {
    const image=el('img');image.src=item.cover;image.alt='';image.loading='lazy';image.onerror=()=>image.remove();
    cover.append(image);
  }
  return cover;
}
async function openFromShelf(item,source) {
  const rect=source.getBoundingClientRect(),copy=source.cloneNode(true);
  await openBook(item.id);
  if(book?.id!==item.id || matchMedia('(prefers-reduced-motion: reduce)').matches) return;
  copy.classList.add('flyingCover');Object.assign(copy.style,{left:rect.left+'px',top:rect.top+'px',width:rect.width+'px',height:rect.height+'px'});
  document.body.append(copy);
  const dest=$('#leaves .page').getBoundingClientRect();
  const motion=copy.animate([{transform:'none',opacity:1},{transform:'translate('+(dest.left-rect.left)+'px,'+(dest.top-rect.top)+'px) perspective(800px) rotateY(-82deg) scale('+dest.width/rect.width+','+dest.height/rect.height+')',opacity:0}],{duration:380,easing:'ease-out'});
  motion.onfinish=()=>copy.remove();
}
function showShelf(series=null) {
  const area=panel(series||'Библиотека');$('#sheet').classList.add('shelfsheet');
  const all=catalog();
  if(series) area.append(button('‹ Все книги','backlink',()=>showShelf()));
  else {
    const current=all.find(x=>x.id===book?.id)||sample;
    const hero=el('section','shelfHero'),art=coverArt(current),copy=el('div');
    copy.append(el('small','settingLabel','Сейчас читаю'),el('h3','',current.title),el('p','',current.author));
    if(book)copy.append(el('small','heroChapter','Глава '+numeral(chapter)+' · '+textOf().title));
    copy.append(button('Продолжить чтение','heroButton',()=>openFromShelf(current,art)));
    hero.append(art,copy);area.append(hero);
  }
  const tools=el('div','shelfTools');
  tools.append(el('h3','',series?'Тома серии':'На полке'));
  const add=button(busyImport?'Открываем…':'Добавить книгу','primary',()=>window.Library.choose());
  add.disabled=busyImport;add.prepend(icon('plus'));tools.append(add);area.append(tools);
  const grid=el('div','shelfGrid');area.append(grid);
  const seriesMap=new Map();
  for(const item of all) if(item.series){if(!seriesMap.has(item.series))seriesMap.set(item.series,[]);seriesMap.get(item.series).push(item);}
  for(const volumes of seriesMap.values())volumes.sort((a,b)=>(parseFloat(a.volume)||0)-(parseFloat(b.volume)||0)||a.title.localeCompare(b.title,'ru'));
  const emitted=new Set();
  const list=series?seriesMap.get(series)||[]:all;
  for(const item of list) {
    const volumes=seriesMap.get(item.series);
    const stack=!series && volumes?.length>1;
    if(stack && emitted.has(item.series))continue;
    if(stack)emitted.add(item.series);
    const next=volumes?.find(v=>!read('completed.'+v.id,false));
    const face=stack?(next||volumes[0]):item;
    const tile=button('','shelfTile'+(stack?' stacked':''),()=>stack?showShelf(item.series):openFromShelf(item,art));
    const art=coverArt(face),caption=el('span','tileName',stack?item.series:item.title);
    const rest=el('span','bookRest');rest.append(art);
    tile.append(rest,caption,el('small','',stack?volumes.length+' · серия':series?('Том '+(item.volume||'—')+(next===item?' · следующий':'')):item.author||item.format));
    grid.append(tile);
  }
}

function editionMenu() {
  const area=panel('Инструменты чтения');
  for(const [label,action] of [
    ['Веер страниц',()=>{closeSheet();startFan();}],
    ['Цитата-открытка',()=>pickText('quote')],
    ['Пометка на полях',()=>pickText('note')],
    ['Мои пометки',listNotes],
    ['Иллюстрации · '+(book.images?.length||0),gallery],
    ['Оригинал и перевод',chooseParallel],
    ['Анимация перелистывания',animationSettings],
    ['Профили чтения',profileSettings]
  ]) area.append(button(label,'toolRow',action));
}
function notesForBook() {
  if(!noteCache.has(book.id))noteCache.set(book.id,read('annotations.'+book.id,[]));
  return noteCache.get(book.id);
}
function saveNotes(){write('annotations.'+book.id,notesForBook());render();}
function editNote(p,existing) {
  const area=panel('Пометка на полях'),input=el('textarea','entryField');
  input.value=existing?.text||'';input.placeholder='Ваша мысль об этом абзаце';input.maxLength=4000;
  area.append(el('blockquote','quoteExcerpt',textOf().paragraphs[p].slice(0,170)),input);
  area.append(button('Сохранить','primary',()=>{
    const value=input.value.trim();if(!value){input.focus();return;}
    if(existing)existing.text=value;else notesForBook().push({chapter,p,offset:0,text:value});
    saveNotes();closeSheet();notify('Пометка сохранена');
  }));
  if(existing)area.append(button('Удалить пометку','toolRow',()=>{
    notesForBook().splice(notesForBook().indexOf(existing),1);saveNotes();closeSheet();
  }));
}
function listNotes() {
  const area=panel('Мои пометки');
  const notes=[...notesForBook()].sort((a,b)=>a.chapter-b.chapter||a.p-b.p);
  if(!notes.length)area.append(el('p','empty','Выберите абзац и запишите первую пометку.'));
  area.append(button('Добавить у абзаца','toolRow',()=>pickText('note')));
  for(const n of notes) area.append(row(book.chapters[n.chapter].title,n.text,el('i','ordinal',numeral(n.chapter)),()=>{
    jump(n);editNote(n.p,n);
  }));
}
function pickText(mode) {
  closeSheet();document.body.classList.add('reading','pickText');pickingText=mode;
  const bar=el('div','pickBar');bar.id='pickBar';
  if(mode==='quote'){
    document.body.classList.add('selectQuote');
    bar.append(el('span','','Выделите фразу или коснитесь абзаца'));
    const take=button('Открытка','',()=>{
      const value=window.getSelection().toString().trim();
      if(value){clearPick();composeQuote(value);}else notify('Удерживайте слово и растяните выделение');
    });
    take.onpointerdown=e=>e.preventDefault();bar.append(take);
  }else bar.append(el('span','','Коснитесь абзаца для пометки'));
  bar.append(button('Отмена','',clearPick));
  document.body.append(bar);syncFold();
}
function clearPick(){pickingText='';document.body.classList.remove('pickText','selectQuote');getSelection().removeAllRanges();$('#pickBar')?.remove();syncFold();}
function handleEditionPress(e) {
  const para=e.target.closest('#leaves p[data-paragraph]');
  if(!pickingText)return false;
  if(pickingText==='quote')return true;
  if(para){
    const p=Number(para.dataset.paragraph);clearPick();
    editNote(p,notesForBook().find(n=>n.chapter===chapter && n.p===p));
  }
  return true;
}
document.addEventListener('click',e=>{
  if(pickingText!=='quote' || !getSelection().isCollapsed)return;
  const para=e.target.closest('#leaves p[data-paragraph]');
  if(para){const value=textOf().paragraphs[Number(para.dataset.paragraph)];clearPick();composeQuote(value);}
});
function composeQuote(text) {
  const area=panel('Цитата-открытка'),input=el('textarea','entryField');
  input.value=text;input.setAttribute('aria-label','Текст цитаты');
  const card=el('canvas','quoteCanvas');
  const draw=()=>{
    const value=input.value.trim();if(!value||value.length>3000){card.hidden=true;return false;}card.hidden=false;
    const ctx=card.getContext('2d');card.width=900;ctx.font='36px "'+settings.family+'"';
    const lines=[];let line='';
    for(const word of value.split(/\s+/)) {
      if(ctx.measureText(line+' '+word).width>740 && line){lines.push(line);line='';}
      for(const letter of word){if(ctx.measureText(line+letter).width>740){lines.push(line);line='';}line+=letter;}
      line+=' ';
    }
    if(line.trim())lines.push(line.trim());
    card.height=300+lines.length*53;ctx.fillStyle='#f8f1df';ctx.fillRect(0,0,900,card.height);
    ctx.fillStyle=colour(book);ctx.fillRect(0,0,18,card.height);ctx.font='94px Spectral';ctx.fillText('«',69,90);
    ctx.fillStyle='#2c322c';ctx.font='36px "'+settings.family+'"';
    lines.forEach((l,i)=>ctx.fillText(l.trim(),76,136+53*i));
    const foot=card.height-106;ctx.fillStyle=colour(book);ctx.fillRect(76,foot-23,70,3);
    ctx.font='25px Roboto';ctx.fillText(book.author||'Foldread',76,foot+16,748);
    ctx.font='26px Spectral';ctx.fillText(book.title,76,foot+61,748);return true;
  };
  input.oninput=draw;
  const trim=button('Взять выделенный фрагмент','toolRow',()=>{
    if(input.selectionEnd<=input.selectionStart){input.focus();notify('Выделите фразу в поле текста');return;}
    input.value=input.value.slice(input.selectionStart,input.selectionEnd);draw();
  });
  trim.onpointerdown=e=>e.preventDefault();
  const actions=el('div','choices');
  for(const [label,share] of [['Сохранить PNG',false],['Поделиться',true]])
    actions.append(button(label,'',()=>{
      if(!draw()){notify('Выберите от 1 до 3000 символов');return;}
      window.Library.exportCard(card.toDataURL('image/png').split(',')[1],share);
    }));
  area.append(input,trim,card,actions);draw();
}

const profileKeys=['font','family','outside','vertical','gutter','leading','night','brightness'];
function profileSettings() {
  const area=panel('Профили чтения'),profiles=read('profiles',{});
  const value=()=>Object.fromEntries(profileKeys.map(k=>[k,settings[k]]));
  for(const name of ['День','Ночь']) {
    const values=profiles[name]||{...value(),night:name==='Ночь',brightness:name==='Ночь'?18:-1};
    area.append(el('h3','profileName',name),el('p','settingHint',values.family+' · '+values.font+' · '+(values.brightness<0?'системная яркость':values.brightness+' % яркости')));
    const actions=el('div','choices');
    actions.append(button('Применить','',async()=>{
      await document.fonts.load('18px "'+values.family+'"');reflowSettings(values);setBrightness(values.brightness);closeSheet();notify('Профиль «'+name+'» применён');
    }),button('Запомнить текущие','',()=>{profiles[name]=value();write('profiles',profiles);profileSettings();notify('Настройки сохранены в профиль');}));
    area.append(actions);
  }
}

function gallery() {
  const area=panel('Иллюстрации'),grid=el('div','imageGrid');area.append(grid);
  const images=book.images||[];
  if(!images.length)area.append(el('p','empty',book.schema<3||!book.schema?'Выберите исходный файл заново, чтобы загрузить иллюстрации.':'В этой книге нет растровых иллюстраций.'));
  images.forEach((item,i)=>{
    const tile=button('','imageTile',()=>viewArtwork(i)),img=el('img');img.src=item.src;img.loading='lazy';img.alt=item.name;
    tile.append(img,el('span','',item.name));grid.append(tile);
  });
}
function viewArtwork(i) {
  const area=panel('Иллюстрация '+(i+1)+' из '+book.images.length);$('#sheet').classList.add('gallerysheet');
  const frame=el('div','zoomFrame'),img=el('img');img.src=book.images[i].src;img.alt=book.images[i].name;frame.append(img);area.append(frame);
  let zoom=1,x=0,y=0,previous=null;const points=new Map();
  const clamp=(v,max)=>Math.max(-max,Math.min(max,v));
  function transform(){
    x=clamp(x,Math.max(0,(img.offsetWidth*zoom-frame.clientWidth)/2));y=clamp(y,Math.max(0,(img.offsetHeight*zoom-frame.clientHeight)/2));
    img.style.transform='translate('+x+'px,'+y+'px) scale('+zoom+')';
  }
  function geometry(){
    const [a,b]=points.values();if(!a)return null;
    return b?{x:(a.x+b.x)/2,y:(a.y+b.y)/2,d:Math.hypot(a.x-b.x,a.y-b.y)}:{...a,d:0};
  }
  frame.onpointerdown=e=>{points.set(e.pointerId,{x:e.clientX,y:e.clientY});frame.setPointerCapture(e.pointerId);previous=geometry();};
  frame.onpointermove=e=>{
    if(!points.has(e.pointerId))return;points.set(e.pointerId,{x:e.clientX,y:e.clientY});const next=geometry();
    const oldZoom=zoom;
    if(previous.d && next.d)zoom=Math.max(1,Math.min(8,zoom*next.d/previous.d));
    const rect=frame.getBoundingClientRect(),factor=zoom/oldZoom;
    x=x*factor+(previous.x-rect.left-rect.width/2)*(1-factor)+next.x-previous.x;
    y=y*factor+(previous.y-rect.top-rect.height/2)*(1-factor)+next.y-previous.y;
    transform();previous=next;
  };
  frame.onpointerup=frame.onpointercancel=e=>{points.delete(e.pointerId);previous=geometry();};
  const tools=el('div','choices');
  for(const [label,action] of [['‹',()=>viewArtwork(i-1)],['−',()=>{zoom=Math.max(1,zoom/1.5);transform();}],['+',()=>{zoom=Math.min(8,zoom*1.5);transform();}],['›',()=>viewArtwork(i+1)]]) {
    const b=button(label,'',action);b.disabled=label==='‹'&&i===0||label==='›'&&i===book.images.length-1;tools.append(b);
  }
  area.append(tools);
}

function exitEditionOverlay(){
  if($('#sheet').open) return false;
  if(fanSession){endFan(false);return true;}
  if(pickingText){clearPick();return true;}
  if($('#parallel')){closeParallel();return true;}
  return false;
}
