'use strict';

const $ = selector => document.querySelector(selector);
const el = (tag, cls, value) => {
  const node = document.createElement(tag);
  if (cls) node.className = cls;
  if (value != null) node.textContent = value;
  return node;
};
const icons = {
  book:'M2 4.5c4-.8 7 .4 10 3 3-2.6 6-3.8 10-3v13c-4-.7-7 .7-10 3-3-2.3-6-3.7-10-3z M12 7.5v13 M5 8c1.7 0 3 .7 4.4 1.5 M15 9.5c1.5-.8 3-1.5 4.4-1.5',
  library:'M3 4h4v16H3z M10 4h3v16h-3z M15 5l3-.8 3.6 15-3 .8z',
  bookmark:'M6 3.5h12v17l-6-4-6 4z',
  contents:'M3 5h3m4 0h11M3 12h3m4 0h11M3 19h3m4 0h11',
  left:'M14 6l-6 6 6 6',right:'M10 6l6 6-6 6',close:'M6 6l12 12M18 6L6 18',
  plus:'M12 4v16M4 12h16',
  tools:'M4 4h6v6H4z M14 4h6v6h-6z M4 14h6v6H4z M14 14h6v6h-6z',
  stats:'M3 4v16h18 M7 16v-4 M12 16V7 M17 16V9'
};
function drawIcon(node) {
  node.setAttribute('aria-hidden','true');
  node.innerHTML = '<svg viewBox="0 0 24 24"><path d="'+icons[node.dataset.icon]+'"/></svg>';
}
function icon(name) {
  const node=el('i'); node.dataset.icon=name; drawIcon(node); return node;
}
document.querySelectorAll('[data-icon]').forEach(drawIcon);
const read = (key, fallback) => {
  try {return JSON.parse(localStorage.getItem(key)) ?? fallback;} catch {return fallback;}
};
const write = (key, value) => localStorage.setItem(key, JSON.stringify(value));
const sample = {id:'sails',title:'Алые паруса',author:'Александр Грин',format:'1923',cover:'sails-cover.jpg',accent:'#95543c'};
const settings = Object.assign({font:18,night:false,family:'Spectral',fold:true,
  outside:22,vertical:24,gutter:16,leading:1.5,brightness:-1,indent:1.1,hyphenate:true,justify:false,liveSpine:true,spreadMode:'auto'},read('settings',{}));
if(!Number.isFinite(settings.bottom))settings.bottom=Math.min(8,settings.vertical);
if(!['progress','numbers','none'].includes(settings.pageFooter)){
  settings.pageFooter=settings.pageNumbers?'numbers':settings.progressLine===false?'none':'progress';
}
if('pageNumbers' in settings || 'progressLine' in settings){
  delete settings.pageNumbers;
  delete settings.progressLine;
  write('settings',settings);
}
if(!fontNames.includes(settings.family)) settings.family=settings.family==='sans'?'Roboto':'Spectral';
const romanDigits=[[1000,'M'],[900,'CM'],[500,'D'],[400,'CD'],[100,'C'],[90,'XC'],[50,'L'],[40,'XL'],[10,'X'],[9,'IX'],[5,'V'],[4,'IV'],[1,'I']];
function numeral(chapterIndex){
  let value=chapterIndex+1,result='';
  for(const [weight,symbol] of romanDigits){
    result+=symbol.repeat(Math.floor(value/weight));
    value%=weight;
  }
  return result;
}
let book, chapter=0, page=0, columns=1, pages=1, width=0, model, paragraphs=[], boundaries=new Map();
let position={chapter:0,p:0,offset:0}, marks=[], animation=null, revision=0, resizeTimer;
let pendingTurn=null, touch=null;
let pagination=null, countingTimer=0,bookPercent=0;
let chapterLengths=[], chapterStarts=[], totalLength=1, busyImport=false, toastTimer;
let contentsEntries=[],sectionsByChapter=[],activeEntry=null;
function sectionEntries(section){
  const entries=(section.sections||[]).slice();
  if(!entries.length||entries[0].p!==0)entries.unshift({p:0,title:section.title||section.paragraphs[0].slice(0,64)});
  return entries;
}
function sectionAt(entries,p){
  for(let i=entries.length-1;i>0;i--)if(entries[i].p<=p)return entries[i];
  return entries[0];
}
const catalog = () => [sample,...Object.values(JSON.parse(window.Library?.catalog() || '{}'))];
const textOf = () => book.chapters[chapter];

function applySettings() {
  document.body.classList.toggle('night',settings.night);
  document.body.dataset.footer=settings.pageFooter;
  document.documentElement.style.setProperty('--type',settings.font+'px');
  const style=document.documentElement.style;
  style.setProperty('--reading','"'+settings.family+'", '+(settings.family==='Roboto'?'sans-serif':'serif'));
  style.setProperty('--outside',settings.outside+'px');
  style.setProperty('--vertical',settings.vertical+'px');
  style.setProperty('--bottom',settings.bottom+'px');
  style.setProperty('--gutter',settings.gutter+'px');
  style.setProperty('--leading',settings.leading);
  style.setProperty('--indent',settings.indent+'em');
  style.setProperty('--align',settings.justify?'justify':'start');
  style.setProperty('--hyphen',settings.hyphenate?'auto':'manual');
}
async function openBook(id,requestedMark=null) {
  const request=++revision;
  try {
    const response=await fetch(id==='sails'?'sails.json':'books/'+id+'.json');
    if(!response.ok) throw new Error();
    const loaded=await response.json();
    if(request!==revision) return;
    cancelTurn();
    releasePin();book=loaded;
    if(id==='sails') Object.assign(book,{cover:sample.cover,accent:sample.accent,images:[{src:sample.cover,name:'Обложка издания 1923 года'}]});
    totalLength=0; chapterStarts=[];contentsEntries=[];
    sectionsByChapter=book.chapters.map((section,chapter)=>sectionEntries(section).map(s=>{
      const entry={...s,chapter,offset:0,index:contentsEntries.length};
      contentsEntries.push(entry);return entry;
    }));
    chapterLengths=book.chapters.map(c=>{
      chapterStarts.push(totalLength);
      const size=c.paragraphs.reduce((n,t)=>n+t.length,0);
      totalLength+=size;
      return size;
    });
    const clampAnchor=at=>{
      const chapter=Math.max(0,Math.min(Math.trunc(Number(at?.chapter))||0,book.chapters.length-1));
      const lines=book.chapters[chapter].paragraphs;
      const p=Math.max(0,Math.min(Math.trunc(Number(at?.p))||0,lines.length-1));
      return {chapter,p,offset:Math.max(0,Math.min(Number(at?.offset)||0,lines[p].length-1))};
    };
    const native=JSON.parse(window.Library?.nativePlace?.(id)||'{}');
    marks=read('marks.'+id,[]).map(m=>({...clampAnchor(m),addedAt:m.addedAt||0}));
    const bookmark=requestedMark&&marks.find(m=>m.chapter===requestedMark.chapter&&m.p===requestedMark.p&&m.offset===requestedMark.offset);
    position=clampAnchor(bookmark || (Number.isInteger(native.chapter)?native:read('pos.'+id,null)));
    chapter=position.chapter;
    document.body.classList.add('reading');
    restoreReadingTools(id);
    closeSheet();
    layout(position);
    write('active',id);
    publishLastBookmark();
    if(bookmark || !Number.isInteger(native.chapter) || !['playing','loading'].includes(JSON.parse(window.Library?.audioState?.()||'{}').state))save(position);
    lastVoiceRevision=null;syncSpeech();
    showPocketIfNarrow();
    window.Library?.refreshPosture?.();
    openWidgetStats();
    if(!read('menuHintSeen',false)) {
      notify('Коснитесь центра экрана, чтобы открыть меню');
      write('menuHintSeen',true);
    }
  } catch {
    notify('Не удалось открыть книгу.');
    if(!book && id!=='sails') await openBook('sails');
  }
}
let bookmarkRevision=0;
async function publishLastBookmark(){
  if(!window.Library?.widgetBookmark)return;
  const request=++bookmarkRevision;
  let latest=null;
  for(const item of catalog()){
    const current=item.id===book.id;
    const list=current?marks:read('marks.'+item.id,[]);
    for(const mark of list){
      const time=Number(mark.addedAt)||0;
      if(!latest||time>latest.time||(time===latest.time&&(current||latest.id===item.id))){
        latest={id:item.id,title:item.title,chapter:mark.chapter,p:mark.p,offset:mark.offset,time};
      }
    }
  }
  if(latest){
    try{
      const volume=latest.id===book.id?book:await loadVolume(latest.id);
      latest.label=sectionAt(sectionEntries(volume.chapters[latest.chapter]),latest.p).title;
    }catch{latest.label='';}
  }
  if(request===bookmarkRevision)Library.widgetBookmark(latest?JSON.stringify(latest):'');
}
function openWidgetMark(){
  const raw=window.Library?.takeWidgetBookmark?.();
  if(!raw)return false;
  const mark=JSON.parse(raw);openBook(mark.id,mark);return true;
}
function makeHeading(title) {
  const head=el('div','chapter');
  head.append(el('h1','',title),el('div','orn','✦'));
  return head;
}
function leafFrame(side, index) {
  const leaf=el('article','page '+side);
  leaf.dataset.page=index;
  const running=el('div','running');
  running.append(el('span','',columns===2 && side==='left' ? (book.author || book.title) : book.title));
  const viewport=el('div','window');
  const folio=el('div','folio',folioNumber(index));
  leaf.append(running,viewport,folio);
  return leaf;
}
function folioNumber(index){
  return pagination?.book===book && pagination.starts && index<pages?String(pagination.starts[chapter]+index+1):'';
}
function chapterFlow(index){
  const flow=el('div','flow'),section=book.chapters[index];
  if(section.title)flow.append(makeHeading(section.title));
  for(const [i,text] of section.paragraphs.entries()){
    if(section.inlineHeading?.p===i)flow.append(makeHeading(section.inlineHeading.title));
    flow.append(el('p',i===0?'opening':'',typeset(text)));
  }
  return flow;
}
function indexChapter(height){
  const key=JSON.stringify([width,height,innerWidth,innerHeight,settings.font,settings.family,settings.leading,
    settings.indent,settings.hyphenate,settings.justify,devicePixelRatio]);
  if(!pagination||pagination.book!==book||pagination.key!==key){
    clearTimeout(countingTimer);countingTimer=0;
    pagination={book,key,index:0,counts:new Array(book.chapters.length),starts:null,total:0};
    const meter=$('#bookMeasure');meter.style.width=width+'px';meter.style.height=height+'px';meter.replaceChildren();
  }
  pagination.counts[chapter]=pages;
  scheduleCount();
}
function scheduleCount(){
  if(!pagination||pagination.starts||countingTimer||document.hidden)return;
  countingTimer=setTimeout(countChapters,45);
}
function countChapters(){
  countingTimer=0;
  if(document.hidden)return;
  if(touch||animation||$('.controls').getAnimations().length){scheduleCount();return;}
  const meter=$('#bookMeasure'),work=pagination,deadline=performance.now()+7;
  while(work.index<work.counts.length){
    const index=work.index++;
    if(work.counts[index]===undefined){
      const flow=chapterFlow(index);meter.replaceChildren(flow);
      work.counts[index]=Math.max(1,Math.round(flow.scrollWidth/width));
    }
    if(performance.now()>=deadline)break;
  }
  meter.replaceChildren();
  if(work.index<work.counts.length){scheduleCount();return;}
  work.total=0;
  work.starts=work.counts.map(count=>{const start=work.total;work.total+=count;return start;});
  updateBookProgress();
}
function updateBookProgress(){
  const work=pagination;
  if(!work||!book)return;
  let range='';
  if(work.starts){
    const first=work.starts[chapter]+page+1,last=work.starts[chapter]+Math.min(page+columns,work.counts[chapter]);
    range=(last>first?first+'–'+last:String(first))+' из '+work.total;
  }
  const location=range||'Подсчёт страниц…';
  $('#bookProgress').textContent=location+' · '+bookPercent+'%';
  $('#menuBookProgress').textContent=location;
  queueMicrotask(publishWidgetProgress);
  if(settings.pageFooter==='numbers')for(const leaf of $('#leaves').children){
    const folio=leaf.querySelector('.folio');if(folio)folio.textContent=folioNumber(Number(leaf.dataset.page));
  }
  const preview=$('#sheet[open] .typePreview');
  if(preview)paintTypePreview(preview);
}
let lastWidgetProgress='';
function publishWidgetProgress(){
  if(!book||pendingTurn||fanSession||pagePin||!window.Library?.widgetProgress)return;
  const range=pagination?.starts?$('#menuBookProgress').textContent:'';
  const snapshot=JSON.stringify([book.id,book.title,book.cover||'',range,bookPercent]);
  if(snapshot===lastWidgetProgress)return;
  lastWidgetProgress=snapshot;
  Library.widgetProgress(book.id,book.title,book.cover||'',range,bookPercent);
}
function layout(at) {
  if(!book) return;
  applySpine();
  closeNote();
  cancelTurn();
  columns=pageColumns();
  const inner=innerDisplay();
  $('#desk').classList.toggle('innerScreen',inner);
  const count=$('#menuBookProgress'),host=inner?$('#meterGroup'):$('#contents > span');
  if(count.parentElement!==host)host.append(count);
  $('#spread').classList.toggle('two',columns===2);
  const frame=leafFrame('left',0);
  $('#leaves').replaceChildren(frame);
  const rect=frame.querySelector('.window').getBoundingClientRect();
  width=rect.width;
  if(width<1 || rect.height<1) return;
  const measure=$('#measure');
  measure.style.width=width+'px'; measure.style.height=rect.height+'px';
  document.documentElement.style.setProperty('--column',width+'px');
  model=chapterFlow(chapter);
  paragraphs=Array.from(model.querySelectorAll('p'));
  measure.replaceChildren(model);
  pages=Math.max(1,Math.round(model.scrollWidth/width));
  boundaries=new Map();
  page=alignPage(at==='end' ? pages-1 : pageOf(at));
  indexChapter(rect.height);
  render(at);
  syncFold();
}
const alignPage = n => Math.floor(Math.max(0,Math.min(n,pages-1))/columns)*columns;
function characterPage(p, offset) {
  const node=paragraphs[p].firstChild;
  const i=Math.min(Math.max(0,offset),node.length-1);
  const range=document.createRange();
  range.setStart(node,i); range.setEnd(node,i+1);
  const rect=range.getClientRects()[0];
  if(!rect) return 0;
  return Math.max(0,Math.floor((rect.left-model.getBoundingClientRect().left+0.25)/width));
}
function pageOf(at) {
  if(!at || at.p<0 || at.p>=paragraphs.length || (!at.p && !at.offset)) return 0;
  return characterPage(at.p,at.offset);
}
// Locate the first character in a column. Text anchors survive a change of type or window size.
function anchor(index) {
  if(boundaries.has(index)) return boundaries.get(index);
  let p=0, offset=0;
  if(index>=pages) p=paragraphs.length;
  else if(index>0) {
    let end=paragraphs.length;
    while(p<end) {
      const mid=(p+end)>>1;
      if(characterPage(mid,paragraphs[mid].textContent.length-1)<index) p=mid+1; else end=mid;
    }
    if(p<paragraphs.length) {
      end=paragraphs[p].textContent.length;
      while(offset<end) {
        const mid=(offset+end)>>1;
        if(characterPage(p,mid)<index) offset=mid+1; else end=mid;
      }
    }
  }
  const value={chapter,p,offset};
  boundaries.set(index,value); return value;
}
function leafAt(side,index) {
  const leaf=leafFrame(side,index);
  if(index>=pages) return leaf;
  const start=anchor(index), end=anchor(index+1);
  const visible=el('div','flow');
  if(index===0 && textOf().title)visible.append(makeHeading(textOf().title));
  for(let p=start.p;p<=end.p && p<paragraphs.length;p++) {
    const from=p===start.p?start.offset:0;
    const until=p===end.p?end.offset:undefined;
    const excerpt=paragraphs[p].textContent.slice(from,until);
    if(!excerpt.length) continue;
    if(from===0&&textOf().inlineHeading?.p===p)visible.append(makeHeading(textOf().inlineHeading.title));
    const paragraph=el('p',index===0 && p===0?'opening':'',excerpt);
    paragraph.dataset.paragraph=p;
    paragraph.dataset.from=from;
    if(notesForBook().some(n=>n.chapter===chapter && n.p===p)) paragraph.classList.add('annotated');
    decorateExcerpt(paragraph,p,from,excerpt);
    if(from>0) paragraph.style.textIndent='0';
    visible.append(paragraph);
  }
  leaf.querySelector('.window').append(visible);
  if(marks.some(m=>m.chapter===chapter && pageOf(m)===index)) leaf.append(el('div','ribbon'));
  return leaf;
}
function render(at) {
  const leaves=[leafAt('left',page)];
  if(columns===2) leaves.push(leafAt('right',page+1));
  $('#leaves').replaceChildren(...leaves);
  updateControls(at);
}
function updateControls(at) {
  $('#prev').disabled=page===0 && chapter===0;
  $('#next').disabled=page+columns>=pages && chapter===book.chapters.length-1;
  const pos=anchor(page);
  activeEntry=sectionAt(sectionsByChapter[chapter],at?.p??pos.p);
  $('#chapterName').textContent=activeEntry.title;
  $('#chapterNumber').textContent=numeral(activeEntry.index)+' / '+numeral(contentsEntries.length-1);
  let chars=chapterStarts[chapter]+pos.offset;
  for(let i=0;i<pos.p;i++) chars+=textOf().paragraphs[i].length;
  const percent=$('#next').disabled?100:Math.floor(100*chars/Math.max(1,totalLength));
  $('#bar').style.width=percent+'%';
  $('#bookMeter').setAttribute('aria-valuenow',percent);
  $('#menuPercent').textContent=percent+'%';
  bookPercent=percent;updateBookProgress();
  updateEstimate();
  const marked=visibleMark()!==-1;
  $('#mark').setAttribute('aria-pressed',marked);
  $('#mark').setAttribute('aria-label',marked?'Снять закладку':'Поставить закладку');
}
function visibleMark() {
  return marks.findIndex(m=>m.chapter===chapter && alignPage(pageOf(m))===page);
}
function save(at) {
  if(pagePin)return;
  position=at;write('pos.'+book.id,position);
  window.Library?.savePlace?.(book.id,at.chapter,at.p,at.offset);
  if(page+columns>=pages && chapter===book.chapters.length-1 && !read('completed.'+book.id,false)){
    write('finishedDate.'+book.id,readingDate(new Date()));
    write('completed.'+book.id,true);
  }
}
function cancelTurn() {
  if(pendingTurn) {
    $('#leaves').replaceChildren(...pendingTurn.original);
    chapter=pendingTurn.originalChapter;
    page=pendingTurn.originalPage;
    const previous=pendingTurn.previousLayout;
    if(previous) {
      model=previous.model;paragraphs=previous.paragraphs;
      pages=previous.pages;boundaries=previous.boundaries;
      $('#measure').replaceChildren(model);
    }
    updateControls();
  }
  removeTurnLayers();
}
function removeTurnLayers() {
  if(pendingTurn) {
    animation.onfinish=null;
    for(const track of pendingTurn.tracks) track.cancel();
    for(const plane of pendingTurn.planes) plane.remove();
  }
  animation=null;pendingTurn=null;
  document.body.classList.remove('turning');
}
function prepareTurn(direction) {
  if(!book || animation || noteOpen() || $(direction>0?'#next':'#prev').disabled) return false;
  if(spineChanged())layout(anchor(page));
  const originalChapter=chapter, originalPage=page;
  const timing=readingSample(direction);
  const old=[...$('#leaves').children];
  const target=page+direction*columns;
  let previousLayout=null;
  if(target<0 || target>=pages) {
    previousLayout={model,paragraphs,pages,boundaries};
    chapter+=direction;
    layout(direction<0?'end':{p:0,offset:0});
  } else {page=target; render();}
  const destination=[...$('#leaves').children];
  pendingTurn={originalChapter,originalPage,direction,timing,original:old,destination,previousLayout,tracks:[],planes:[]};
  Object.assign(pendingTurn,buildTurn($('#spread'),$('#leaves'),old,destination,direction,
    settings.turnMode||'book',touch ? touch.y/innerHeight : .94));
  document.body.classList.add('turning');
  animation=pendingTurn.tracks[0];
  return true;
}
function settleTurn(commit) {
  if(!animation) return;
  const moving=animation;
  const finish=()=>{
    if(animation!==moving) return;
    if(commit) {
      acceptReadingSample(pendingTurn.timing);
      save(anchor(page));
      $('#leaves').replaceChildren(...pendingTurn.destination);
      removeTurnLayers();
      publishWidgetProgress();
    } else cancelTurn();
    restartReadingClock();
  };
  if(settings.turnMode==='instant' || !commit && moving.currentTime<=0) {finish();return;}
  moving.onfinish=finish;
  const rate=(commit?1:-1)*(matchMedia('(prefers-reduced-motion: reduce)').matches?4:1);
  for(const track of pendingTurn.tracks) {track.playbackRate=rate;track.play();}
}
function turn(direction) {
  if(prepareTurn(direction)) settleTurn(true);
}
function jump(at, remember=true) {
  if(remember && (at.chapter!==chapter || alignPage(pageOf(at))!==page)) holdPlace();
  cancelTurn(); closeSheet(); document.body.classList.add('reading'); chapter=at.chapter; layout(at); save(at);
  restartReadingClock();
}
function notify(message) {
  const toast=$('#toast'),host=$('#sheet').open?$('#sheet'):document.body;
  if(toast.parentElement!==host)host.append(toast);
  toast.textContent=message; toast.classList.add('visible');
  clearTimeout(toastTimer); toastTimer=setTimeout(()=>toast.classList.remove('visible'),2300);
}
function toggleMark(index=null) {
  const found=index===null?visibleMark():marks.findIndex(m=>m.chapter===chapter && pageOf(m)===index);
  if(found!==-1) marks.splice(found,1); else marks.push({...anchor(index??page),addedAt:Date.now()});
  write('marks.'+book.id,marks);publishLastBookmark();render();notify(found===-1?'Закладка добавлена':'Закладка снята');
}
function closeSheet() {
  if(!$('#sheet').open) return false;
  $('#sheet').close(); syncFold(); return true;
}
function syncFold() {
  window.Library?.enableFold?.(Boolean(book && innerDisplay() && (pair?.peek || settings.liveSpine || settings.fold)));
  syncReadingTime();
  syncBookOrientation();
}
let lastOrientation;
function syncBookOrientation(){
  const mode=book&&!$('#sheet[open].shelfsheet')?read('orientation.'+book.id,'system'):'system';
  if(mode===lastOrientation)return;
  lastOrientation=mode;window.Library?.bookOrientation?.(mode);
}
function syncReadingTime(){
  const sheet=$('#sheet');
  const reading=sheet.open?sheet.classList.contains('workSheet'):(document.body.classList.contains('reading')||pair);
  window.Library?.setReadingVisible?.(Boolean(book&&!document.hidden&&!fanSession&&reading));
}
function toggleMenu() {
  document.body.classList.toggle('reading');
  if(!document.body.classList.contains('reading') && !settings.markHintSeen){
    notify('Потяните вниз от верха страницы, чтобы поставить закладку');
    settings.markHintSeen=true;write('settings',settings);
  }
  syncFold();restartReadingClock();
}
window.handleBack=()=>{
  if(exitEditionOverlay()) return true;
  if(closeSheet()) return true;
  if(closeNote()) return true;
  if(!document.body.classList.contains('reading')) {toggleMenu();return true;}
  return false;
};
window.foldTurn=()=>{
  if(!touch && !pagePin && !voiceSession && !standMode && book && !fanSession && !pickingText && !pair && innerDisplay() && settings.fold && !noteOpen() && !$('#sheet').open && document.body.classList.contains('reading')) turn(settings.foldDirection===-1?-1:1);
};
function panel(title,sheetClass) {
  disposeWorkspace();
  closeNote();
  $('#sheet').classList.remove('typesheet','shelfsheet','widesheet','gallerysheet','workSheet','toolsSheet','statsSheet');
  if(sheetClass)$('#sheet').classList.add(sheetClass);
  restartReadingClock();
  $('#sheet h2').textContent=title;
  const area=$('#panel'); area.replaceChildren();
  if(!$('#sheet').open) $('#sheet').showModal();
  syncFold();
  return area;
}
function button(label, cls, action) {
  const node=el('button',cls,label);node.onclick=action;return node;
}
function row(title,description,lead,action,current=false) {
  const node=button('', 'row'+(current?' current':''),action);
  const copy=el('span');copy.append(el('b','',title));
  if(description) copy.append(el('small','',description));
  node.append(lead,copy);return node;
}
function library() {
  showShelf();
}
window.importStarted=()=>{busyImport=true;library();};
window.importCancelled=()=>{busyImport=false;library();};
window.bookImported=id=>{busyImport=false;openBook(id);};
window.importFailed=message=>{busyImport=false;library();$('#panel').append(el('p','empty',message));};
function contents(tab='chapters') {
  const area=panel(book.title);
  const tabs=el('div','tabs');
  tabs.append(button('Оглавление',tab==='chapters'?'selected':'',()=>contents()),button('Закладки · '+marks.length,tab==='marks'?'selected':'',()=>contents('marks')),button('Поиск',tab==='search'?'selected':'',()=>contents('search')));
  area.append(tabs);
  if(tab==='search') {spreadSearch();return;}
  if(tab==='chapters'){
    for(const entry of contentsEntries)area.append(row(entry.title,'',el('i','ordinal',numeral(entry.index)),()=>jump({chapter:entry.chapter,p:entry.p,offset:0}),entry===activeEntry));
  }
  else {
    const marked=visibleMark()!==-1;
    area.append(button(marked?'Снять закладку с этой страницы':'Поставить закладку здесь','primary bookmarkAction',()=>{toggleMark();contents('marks');}));
    if(!marks.length) area.append(el('p','empty','Потяните вниз от верха страницы. Сохранённое место появится здесь.'));
    for(const m of marks.slice().sort((a,b)=>a.chapter-b.chapter||a.p-b.p||a.offset-b.offset)) {
      const c=book.chapters[m.chapter];
      area.append(row(sectionAt(sectionsByChapter[m.chapter],m.p).title,(c.paragraphs[m.p]||'').slice(m.offset,m.offset+80),icon('bookmark'),()=>jump(m)));
    }
  }
}
$('#library').onclick=library;$('#contents').onclick=()=>contents();$('#settings').onclick=()=>showTypography();
$('#stats').onclick=()=>showStatistics();
$('#extras').onclick=editionMenu;
$('#timeLeft').onclick=showTimeLeft;$('#returnPlace').onclick=returnToPlace;
$('#close').onclick=closeSheet;$('#sheet').onclick=e=>{if(e.target===$('#sheet')){const r=e.target.getBoundingClientRect();if(e.clientX<r.left||e.clientY<r.top||e.clientX>r.right||e.clientY>r.bottom)closeSheet();}};
$('#prev').onclick=()=>turn(-1);$('#next').onclick=()=>turn(1);$('#mark').onclick=()=>toggleMark();
$('#spread').oncontextmenu=e=>{if(pickingText!=='quote')e.preventDefault();};
$('#spread').onpointerdown=e=>{
  if((!e.isPrimary && !pagePin) || animation || !book) return;
  if(voiceSession){toggleMenu();return;}
  if(pagePin?.id===e.pointerId)return;
  if(handleEditionPress(e)) return;
  if(e.target.closest('.note-ref,.note-card')) return;
  if(closeNote()) return;
  touch={x:e.clientX,y:e.clientY,id:e.pointerId,started:e.timeStamp,drag:false,annotated:e.target.closest('p.annotated')};
  e.currentTarget.setPointerCapture(e.pointerId);
  armFan(e);
  armReadingHold(e);
};
function trackDrag(e) {
  if(!touch || touch.id!==e.pointerId) return;
  const dx=e.clientX-touch.x,dy=e.clientY-touch.y;
  if(Math.abs(dx)+Math.abs(dy)>10){clearFanTimer();clearReadingHold();}
  if(!pagePin && !touch.drag && !touch.light && touch.y<=Math.min(110,innerHeight*.17) &&
     (touch.bookmark || dy>10 && dy>1.4*Math.abs(dx))){
    if(!touch.bookmark){
      const index=page+(columns===2 && touch.x>=innerWidth/2?1:0);
      if(index>=pages)return;
      const leaf=$('#leaves').children[index-page],ribbon=el('div','ribbon pullRibbon');
      leaf.append(ribbon);touch.bookmark={index,ribbon};
    }
    touch.bookmark.ready=dy>=54;
    touch.bookmark.ribbon.style.height=Math.max(0,Math.min(62,dy))+'px';
    touch.bookmark.ribbon.classList.toggle('ready',touch.bookmark.ready);return;
  }
  if(!touch.drag && touch.x<=Math.min(50,innerWidth*.12) && (touch.light || Math.abs(dy)>10 && Math.abs(dy)>1.35*Math.abs(dx))) {
    adjustBrightness(touch,dy);return;
  }
  if(!touch.drag && Math.abs(dx)>8 && Math.abs(dx)>Math.abs(dy)*1.2) {
    touch.drag=prepareTurn(dx<0?1:-1);
  }
  if(touch.drag && pendingTurn && Math.abs(dx)>8 && pendingTurn.direction*dx>0){
    cancelTurn();touch.progress=0;touch.drag=prepareTurn(dx<0?1:-1);
  }
  if(touch.drag && pendingTurn) {
    const travel=$('#spread').clientWidth;
    touch.progress=Math.max(0,Math.min(.999,-pendingTurn.direction*dx/travel));
    for(const track of pendingTurn.tracks) track.currentTime=480*touch.progress;
  }
}
$('#spread').onpointermove=trackDrag;
function abortTouch() {
  clearFanTimer();
  touch?.bookmark?.ribbon.remove();
  if(touch?.drag && pendingTurn) settleTurn(false);
  touch=null;
}
$('#spread').onpointercancel=$('#spread').onlostpointercapture=e=>{
  if(pagePin?.id===e.pointerId)releasePin();
  if(touch?.id===e.pointerId){clearReadingHold();abortTouch();}
};
$('#spread').onpointerup=e=>{
  clearReadingHold();
  if(pagePin?.id===e.pointerId){releasePin();return;}
  clearFanTimer();
  if(!touch || touch.id!==e.pointerId)return;
  trackDrag(e);
  const gesture=touch;touch=null;
  const dx=e.clientX-gesture.x,dy=e.clientY-gesture.y;
  if(gesture.bookmark){gesture.bookmark.ribbon.remove();if(gesture.bookmark.ready)toggleMark(gesture.bookmark.index);return;}
  if(gesture.light) {write('settings',settings);return;}
  if(gesture.drag) {
    const flick=e.timeStamp-gesture.started<260 && Math.abs(dx)>32 && gesture.progress>.04;
    settleTurn(gesture.progress>=.24 || flick);
  } else if(Math.abs(dx)<12&&Math.abs(dy)<12) {
    if(gesture.annotated){
      const p=Number(gesture.annotated.dataset.paragraph);
      editNote(p,notesForBook().find(n=>n.chapter===chapter&&n.p===p));return;
    }
    const r=$('#spread').getBoundingClientRect(), x=(e.clientX-r.left)/r.width;
    if(x<.27 || x>.73) turn(x>.73 || columns===2 && settings.thumbZones && e.clientY>innerHeight*.62?1:-1);
    else toggleMenu();
  }
};
document.onkeydown=e=>{
  if($('#sheet').open || !book || pair || voiceSession || fanSession || pickingText)return;
  if(['ArrowRight','PageDown','ArrowLeft','PageUp'].includes(e.key)) {
    e.preventDefault();turn(['ArrowRight','PageDown'].includes(e.key)?1:-1);
  }
};
window.onresize=onReaderResize;
$('#sheet').addEventListener('close',()=>{if(!$('#sheet').open)disposeWorkspace();syncFold();restartReadingClock();});
document.addEventListener('visibilitychange',()=>{if(document.hidden) abortTouch();else scheduleCount();restartReadingClock();syncReadingTime();});
document.fonts.addEventListener('loadingdone',()=>{
  if(!book)return;
  pagination=null;clearTimeout(countingTimer);countingTimer=0;onReaderResize();
});
applySettings();
window.Library?.setReaderBrightness?.(settings.brightness);
document.fonts.load('18px "'+settings.family+'"').then(()=>{
  if(!resumeRestore()&&!openWidgetMark())openBook(window.Library?.audioTarget?.()||read('active','sails'));
});
