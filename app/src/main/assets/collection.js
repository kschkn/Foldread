'use strict';

async function loadVolume(id){
  const response=await fetch(id==='sails'?'sails.json':'books/'+id+'.json');
  if(!response.ok)throw Error();return response.json();
}
function typeset(text){
  // A nonbreaking space uses one UTF-16 unit, preserving stored logical offsets.
  return text.replace(/([А-ЯЁA-Z]\.) (?=[А-ЯЁA-Z])/gu,'$1\u00a0').replace(/(^|\s)([ВвКкСсУуОоЯяИиАа]) (?=\S)/gu,'$1$2\u00a0');
}
function resumeRestore(){
  const state=window.Library?.pendingRestore?.();
  if(!state)return false;
  try{
    if(!window.applyBackupState(state))throw Error();
    if(!Library.finishRestore(true))throw Error();
    location.reload();return true;
  }catch{Library.finishRestore(false);notify('Не удалось восстановить настройки чтения');return false;}
}
function appendShelfActions(area,tools){
  const actions=el('div','shelfActions');actions.append(button('Собрать пару','shelfAction',()=>pairBuilder()),button('Перенос полки','shelfAction',backupMenu));
  area.append(actions);
  const pairs=read('shelfPairs',[]),all=catalog();
  if(!pairs.length)return;
  const group=el('section','savedPairs');group.append(el('h3','deskTitle','Книжные пары'));
  pairs.forEach(([a,b])=>{
    const left=all.find(v=>v.id===a),right=all.find(v=>v.id===b);if(!left||!right)return;
    const choice=button('','savedPair',()=>launchPair(a,b));choice.append(coverArt(left),coverArt(right),el('span','',left.title+' / '+right.title));group.append(choice);
  });area.append(group);
}
async function launchPair(a,b){
  try{
    const other=await loadVolume(b);await openBook(a);if(book.id===a)openParallel(other);
  }catch{notify('Не удалось открыть пару');}
}
function savePair(a,b){
  const pairs=read('shelfPairs',[]);
  if(!pairs.some(([l,r])=>l===a && r===b)){pairs.push([a,b]);write('shelfPairs',pairs);}
}
function pairBuilder(){
  const area=panel('Собрать книжную пару');$('#sheet').classList.add('shelfsheet');
  const all=catalog(),slots=el('div','pairSlots'),tray=el('div','pairTray'),selected=[null,null];
  let active=0;
  const action=button('Сохранить и открыть пару','primary',()=>{savePair(...selected);launchPair(...selected);});
  function paint(){
    slots.replaceChildren();selected.forEach((id,i)=>{
      const item=all.find(v=>v.id===id),slot=button('','pairSlot',()=>{active=i;paint();});
      slot.dataset.slot=i;slot.setAttribute('aria-pressed',i===active);slot.append(el('small','settingLabel',i?'Справа':'Слева'));
      if(item)slot.append(coverArt(item));slot.append(el('strong','',item?.title||'Выберите книгу'));slots.append(slot);
    });action.disabled=!selected.every(Boolean);
  }
  function assign(id,index){
    if(selected[1-index]===id){notify('Выберите другую книгу для второй стороны');return;}
    selected[index]=id;active=1-index;paint();
  }
  for(const item of all){
    let start=null,suppress=false,ghost;
    const tile=button('','trayTile',()=>{if(!suppress)assign(item.id,active);});tile.append(coverArt(item),el('span','',item.title));
    tile.onpointerdown=e=>{suppress=false;start={x:e.clientX,y:e.clientY};tile.setPointerCapture(e.pointerId);};
    tile.onpointermove=e=>{
      if(!start)return;
      if(!ghost && Math.hypot(e.clientX-start.x,e.clientY-start.y)>9){suppress=true;ghost=coverArt(item);ghost.classList.add('dragCover');area.append(ghost);}
      if(ghost){ghost.style.left=e.clientX+'px';ghost.style.top=e.clientY+'px';}
    };
    tile.onpointerup=tile.onpointercancel=e=>{
      if(ghost){ghost.remove();ghost=null;const slot=document.elementFromPoint(e.clientX,e.clientY)?.closest('.pairSlot');if(slot && e.type==='pointerup')assign(item.id,Number(slot.dataset.slot));}
      start=null;
    };tray.append(tile);
  }
  area.append(slots,el('p','settingHint','Перетащите обложки на стороны или выберите сторону и коснитесь книги.'),tray,action);
  if(all.length<2)area.append(button('Добавить вторую книгу','toolRow',()=>Library.choose()));
  paint();
}
function extendParallel(other,tools,shell){
  const right=pair.panes[1].view.parentElement,veil=el('div','translationVeil');
  const hint=el('p','');veil.append(hint,button('Показать перевод','solidAction',()=>revealTranslation(true)));right.append(veil);
  pair.veil=veil;pair.hint=hint;pair.other=other;
  const toggle=button('Подглядывать перевод','',()=>{
    pair.peek=!pair.peek;toggle.setAttribute('aria-pressed',!!pair.peek);shell.classList.toggle('peekMode',!!pair.peek);
    revealTranslation(false);syncFold();
  });
  pair.panes[0].onSelect=()=>{if(pair?.peek)revealTranslation(hingeAngle>=163);};
  const originalChange=pair.panes[0].onChange;
  pair.panes[0].onChange=p=>{originalChange(p);p.selection=null;if(pair.peek)revealTranslation(hingeAngle>=163);};
  tools.append(toggle,button('На полку','',()=>{savePair(book.id,other.id);notify('Книжная пара сохранена');}));
}
function revealTranslation(open){
  if(!pair?.veil)return;
  if(!pair.peek){pair.veil.hidden=true;return;}
  const p=pair.panes[0],at=p.selection||p.at;
  const link=pair.links.find(([a])=>a.chapter===at.chapter && a.p===at.p);
  pair.veil.hidden=!!(link&&open);
  pair.hint.textContent=link?'Раскройте Fold, чтобы увидеть перевод.':'У этого абзаца ещё нет связи с переводом.';
  pair.veil.querySelector('button').disabled=!link;
  if(link&&open)pair.panes[1].go(link[1],true);
}
function backupMenu(){
  const area=panel('Перенос библиотеки');
  area.append(el('p','settingHint','Книги, обложки, место чтения, словари, пометки и пары сохраняются в одном файле Bookfold. При восстановлении новые книги добавляются на полку. Для совпадающих записей применяются настройки и позиции из копии.'));
  area.append(button('Сохранить копию','primary',()=>Library.chooseBackup(true)),button('Восстановить из файла','toolRow',()=>Library.chooseBackup(false)));
}
window.backupState=()=>{
  const data=Object.create(null);
  for(let i=0;i<localStorage.length;i++){const k=localStorage.key(i);data[k]=localStorage.getItem(k);}
  for(const item of catalog()){
    const native=window.Library?.nativePlace?.(item.id);
    if(native && Number.isInteger(JSON.parse(native).chapter))data['pos.'+item.id]=native;
  }
  return JSON.stringify(data);
};
window.applyBackupState=json=>{
  const values=JSON.parse(json),old=new Map();
  try{
    for(const [k,v] of Object.entries(values)){
      if(!/^(?:active|settings|profiles|menuHintSeen|readingPace|pocket|lexicon|shelfPairs|(?:pos|marks|words|annotations|clippings|completed|held|pair)\.(?:sails|[a-f0-9]{64})(?:\.(?:sails|[a-f0-9]{64}))?)$/.test(k))continue;
      const value=JSON.parse(v),kind=k.split('.')[0];
      const anchor=a=>a && ['chapter','p','offset'].every(p=>Number.isSafeInteger(a[p])&&a[p]>=0);
      const record=a=>a!==null && typeof a==='object' && !Array.isArray(a);
      const id=a=>typeof a==='string' && /^(sails|[a-f0-9]{64})$/.test(a);
      const every=(list,test)=>Array.isArray(list)&&list.every(test);
      let valid=true;
      if(kind==='active')valid=id(value);
      if(kind==='pos'||kind==='held')valid=anchor(value);
      if(kind==='marks')valid=every(value,anchor);
      if(['words','annotations','clippings'].includes(kind))valid=every(value,a=>anchor(a)&&typeof a.text==='string');
      if(kind==='pair')valid=record(value)&&every(value.places,anchor)&&every(value.links,a=>Array.isArray(a)&&a.length===2&&a.every(anchor));
      if(kind==='shelfPairs')valid=every(value,a=>Array.isArray(a)&&a.length===2&&a.every(id));
      if(kind==='lexicon')valid=record(value)&&Object.values(value).every(v=>typeof v==='string');
      if(kind==='settings'||kind==='profiles')valid=record(value)&&!Object.hasOwn(value,'__proto__');
      if(kind==='pocket')valid=record(value)&&id(value.id)&&typeof value.text==='string';
      if(!valid)throw Error('Некорректная запись чтения');
      old.set(k,localStorage.getItem(k));localStorage.setItem(k,v);
    }
    return true;
  }catch{
    for(const [k,v] of old){if(v===null)localStorage.removeItem(k);else localStorage.setItem(k,v);}
    return false;
  }
};
