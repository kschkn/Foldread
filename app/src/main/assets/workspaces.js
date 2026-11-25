'use strict';

let workspace=null;
function disposeWorkspace(){
  if(!workspace)return;
  const old=workspace;workspace=null;
  old.observer.disconnect();old.cleanup?.();
  if(old.readAt && book===old.volume){
    chapter=old.readAt.chapter;layout(old.readAt);save(old.readAt);
  }
}
function desk(title,labels){
  const area=panel(title);$('#sheet').classList.add('workSheet');
  const tabs=el('nav','workTabs'),body=el('div','workBody');
  const sides=labels.map((label,i)=>{
    const side=el('section','workSide');side.setAttribute('aria-label',label);body.append(side);
    const tab=button(label,'',()=>show(i));tabs.append(tab);return side;
  });
  function show(i){body.dataset.side=i;[...tabs.children].forEach((t,j)=>t.setAttribute('aria-pressed',i===j));}
  area.append(tabs,body);show(0);
  const observers=[];
  const observer=new ResizeObserver(()=>observers.forEach(fn=>fn()));
  workspace={area,body,sides,show,observer,volume:book,watch(target,fn){observers.push(fn);observer.observe(target);}};
  return workspace;
}
function readingPane(w,side,volume=book,at=position,track=false,label='Текст'){
  const pane=new ParallelPane(volume,side,0,p=>{if(track)w.readAt=p.at;},label);
  pane.go(at);
  w.watch(pane.view,()=>{
    const r=pane.view.getBoundingClientRect();
    if(r.width && r.height && (r.width!==pane.w || r.height!==pane.h))pane.go(pane.at);
  });
  return pane;
}
function spreadSearch(){
  const w=desk('Поиск в книге',['Совпадения','Контекст']),[left,right]=w.sides;
  const pane=readingPane(w,right);let chosen;
  const open=button('Читать отсюда','solidAction',()=>{
    if(!chosen)return;const at={...chosen};closeSheet();disposeWorkspace();searchHit=at;jump(at);
  });open.disabled=true;right.append(open);
  searchBook(left,hit=>{
    chosen=hit;open.disabled=false;w.show(1);pane.go(hit,true);
    const node=pane.nodes[hit.p],text=node.textContent;
    // Keep the measurement text node intact while browsing this chapter.
    if(window.Highlight && CSS.highlights){
      const range=document.createRange();range.setStart(node.firstChild,hit.offset);range.setEnd(node.firstChild,hit.end);
      CSS.highlights.set('fold-result',new Highlight(range));
    }
  });
  w.cleanup=()=>window.CSS?.highlights?.delete('fold-result');
}

function atlasPicker(){
  const area=panel('Атлас книги');
  const images=book.images||[];
  if(!images.length){area.append(el('p','empty','В этой книге нет растровых иллюстраций.'));return;}
  const grid=el('div','imageGrid');area.append(grid);
  images.forEach((art,i)=>{
    const b=button('','imageTile',()=>atlas(i)),img=el('img');img.src=art.src;img.alt=art.name;img.loading='lazy';b.append(img,el('span','',art.name));grid.append(b);
  });
}
function atlas(i,dual=false){
  const art=book.images[i],w=desk('Атлас',['Карта',dual?'Деталь':'Книга']),[left,right]=w.sides;
  const tools=el('div','deskActions');
  tools.append(button(dual?'Читать рядом':'Два масштаба','solidAction',()=>atlas(i,!dual)),button('Рисунки','',atlasPicker));
  left.append(tools);
  const overview=el('div','mapFrame'),detail=el('div','mapFrame mapDetail');
  left.append(dual?overview:detail);
  const original=el('img'),large=el('img'),marker=el('span','mapMarker');
  original.src=large.src=art.src;original.alt=large.alt=art.name;
  overview.append(original,marker);detail.append(large);
  if(dual)right.append(detail);else readingPane(w,right,book,position,true);
  let x=.5,y=.5,scale=dual?2.5:1;
  const points=new Map(),clamp=(v,min,max)=>Math.max(min,Math.min(max,v));
  function paint(){
    const nw=large.naturalWidth,nh=large.naturalHeight,dw=detail.clientWidth,dh=detail.clientHeight;
    if(!nw||!dw||!dh)return;
    const factor=Math.min(dw/nw,dh/nh)*scale,iw=nw*factor,ih=nh*factor;
    const hw=Math.min(.5,dw/(2*iw)),hh=Math.min(.5,dh/(2*ih));
    x=clamp(x,hw,1-hw);y=clamp(y,hh,1-hh);
    Object.assign(large.style,{width:iw+'px',height:ih+'px',left:(dw/2-x*iw)+'px',top:(dh/2-y*ih)+'px'});
    if(dual){
      const ow=overview.clientWidth,oh=overview.clientHeight,f=Math.min(ow/nw,oh/nh),cw=nw*f,ch=nh*f;
      Object.assign(marker.style,{left:((ow-cw)/2+(x-hw)*cw)+'px',top:((oh-ch)/2+(y-hh)*ch)+'px',width:2*hw*cw+'px',height:2*hh*ch+'px'});
    }
  }
  function aim(e){
    const r=original.getBoundingClientRect(),f=Math.min(r.width/large.naturalWidth,r.height/large.naturalHeight);
    if(!f)return;
    x=.5+(e.clientX-r.left-r.width/2)/(f*large.naturalWidth);
    y=.5+(e.clientY-r.top-r.height/2)/(f*large.naturalHeight);paint();
  }
  overview.onpointerdown=e=>{overview.setPointerCapture(e.pointerId);aim(e);};
  overview.onpointermove=e=>{if(overview.hasPointerCapture(e.pointerId))aim(e);};
  function geometry(){const [a,b]=points.values();return b?{x:(a.x+b.x)/2,y:(a.y+b.y)/2,d:Math.hypot(a.x-b.x,a.y-b.y)}:a;}
  detail.onpointerdown=e=>{detail.setPointerCapture(e.pointerId);points.set(e.pointerId,{x:e.clientX,y:e.clientY,d:0});};
  detail.onpointermove=e=>{
    if(!points.has(e.pointerId))return;
    const was=geometry();points.set(e.pointerId,{x:e.clientX,y:e.clientY,d:0});const now=geometry();
    if(was.d && now.d)scale=clamp(scale*now.d/was.d,1,10);
    x-=(now.x-was.x)/Math.max(1,large.offsetWidth);y-=(now.y-was.y)/Math.max(1,large.offsetHeight);paint();
  };
  detail.onpointerup=detail.onpointercancel=e=>points.delete(e.pointerId);
  for(const [label,m] of [['−',1/1.5],['+',1.5]])tools.append(button(label,'zoomButton',()=>{scale=clamp(scale*m,1,10);paint();}));
  large.onload=paint;w.watch(detail,paint);if(dual)w.watch(overview,paint);
}

function notebook(){
  const w=desk('Блокнот цитат',['Книга','Блокнот']),[left,right]=w.sides;
  const pane=readingPane(w,left,book,position,true),key='clippings.'+book.id;
  const list=el('div','clipList'),handle=button('Перенести цитату →','solidAction',add);
  left.append(handle);right.append(el('h3','deskTitle','Блокнот'),list);
  let chosen=null,drag=null;
  const entries=read(key,[]);
  function pick(p){
    const section=book.chapters[pane.chapter];if(!section?.paragraphs[p])return;
    chosen={chapter:pane.chapter,p,offset:0,text:section.paragraphs[p]};pane.select(p);handle.disabled=false;
  }
  function selection(){
    const sel=getSelection();if(!sel.rangeCount || sel.isCollapsed)return;
    const range=sel.getRangeAt(0),a=range.startContainer.parentElement?.closest('.pairFlow p'),b=range.endContainer.parentElement?.closest('.pairFlow p');
    if(!a || !b || !pane.flow.contains(a) || !pane.flow.contains(b))return;
    const text=sel.toString().trim();if(!text)return;
    const before=range.cloneRange();before.selectNodeContents(a);before.setEnd(range.startContainer,range.startOffset);
    chosen={chapter:pane.chapter,p:Number(a.dataset.p),offset:before.toString().length,text};handle.disabled=false;
  }
  function add(){
    selection();if(!chosen)return;
    entries.push({...chosen});write(key,entries);chosen=null;handle.disabled=true;getSelection().removeAllRanges();paint();w.show(1);notify('Цитата в блокноте');
  }
  function paint(){list.replaceChildren();
    if(!entries.length)list.append(el('p','empty','Коснитесь абзаца или выделите фразу. Перетащите её в блокнот либо нажмите «Перенести цитату».'));
    entries.forEach((v,i)=>{
      const card=el('article','clipping'),head=el('div','deskActions');
      head.append(button(book.chapters[v.chapter].title,'textLink',()=>{pane.go(v,true);w.readAt=v;w.show(0);}),button('Удалить','smallText',()=>{entries.splice(i,1);write(key,entries);paint();}));
      card.append(el('blockquote','',v.text),head);list.append(card);
    });
  }
  pane.view.classList.add('quoteSource');handle.disabled=true;
  pane.view.onpointerdown=pane.view.onpointerup=null;
  pane.flow.onclick=e=>{const p=e.target.closest('p');if(!getSelection().isCollapsed)selection();else if(p)pick(Number(p.dataset.p));};
  function start(e){
    if(!e.isPrimary)return;
    const p=e.target.closest('.pairFlow p');
    drag={x:e.clientX,y:e.clientY,id:e.pointerId,p:p?Number(p.dataset.p):null,active:false};
  }
  function move(e){if(!drag||drag.id!==e.pointerId)return;
    if(!drag.active && Math.abs(e.clientX-drag.x)>20 && Math.abs(e.clientX-drag.x)>Math.abs(e.clientY-drag.y)){
      if(!getSelection().isCollapsed)selection();else if(drag.p!==null)pick(drag.p);
      if(!chosen)return;
      drag.active=true;w.body.classList.add('draggingQuote');
    }
  }
  function end(e){if(!drag||drag.id!==e.pointerId)return;
    const target=document.elementFromPoint(e.clientX,e.clientY);
    if(e.type==='pointerup' && drag.active && right.contains(target))add();
    drag=null;w.body.classList.remove('draggingQuote');
  }
  handle.onpointerdown=pane.view.onpointerdown=start;
  document.addEventListener('pointermove',move);document.addEventListener('pointerup',end);document.addEventListener('pointercancel',end);
  document.addEventListener('selectionchange',selection);
  w.cleanup=()=>{document.removeEventListener('pointermove',move);document.removeEventListener('pointerup',end);document.removeEventListener('pointercancel',end);document.removeEventListener('selectionchange',selection);};
  paint();
}
function poetryDesk(){
  const w=desk('Репетиция стихов',['Строфа','По памяти']),[left,right]=w.sides;
  const select=el('select','bookSearch'),lines=el('div','verseLines'),hints=el('div','verseLines');
  const groups=[];
  book.chapters.forEach((c,chapter)=>{
    const stanzas=c.stanzas?.length?c.stanzas:Array.from({length:Math.ceil(c.paragraphs.length/4)},(_,i)=>[i*4,Math.min(c.paragraphs.length,i*4+4)]);
    for(const [start,end] of stanzas)groups.push({chapter,start,end});
  });
  groups.forEach((g,i)=>select.add(new Option(book.chapters[g.chapter].title+' · '+(g.start+1),i)));
  const initial=groups.findIndex(g=>g.chapter===chapter && g.start<=position.p && position.p<g.end);
  select.value=Math.max(0,initial);left.append(select,lines);right.append(el('h3','deskTitle','Первые слова'),hints);
  function display(){const g=groups[Number(select.value)],text=book.chapters[g.chapter].paragraphs.slice(g.start,g.end);
    lines.replaceChildren();hints.replaceChildren();
    for(const line of text){
      lines.append(el('p','',line));
      const cue=line.split(/\s+/)[0]+' …',b=button(cue,'verseHint',()=>{const open=b.getAttribute('aria-expanded')!=='true';b.setAttribute('aria-expanded',open);b.textContent=open?line:cue;});
      b.setAttribute('aria-expanded','false');hints.append(b);
    }
  }
  const nav=el('div','deskActions');for(const [s,d] of [['‹ Назад',-1],['Дальше ›',1]])nav.append(button(s,'',()=>{select.selectedIndex=Math.max(0,Math.min(groups.length-1,select.selectedIndex+d));display();}));
  right.append(nav);select.onchange=display;display();
}
function tableReading(){
  const w=desk('Чтение вдвоём',['Первая сторона','Вторая сторона']),[left,right]=w.sides;
  w.body.classList.add('tableDesk');
  const first=readingPane(w,left,book,position,true,'Первая сторона');
  const second=readingPane(w,right,book,position,false,'Вторая сторона');
  const choose=el('select','bookSearch'),all=catalog();choose.setAttribute('aria-label','Книга второй стороны');
  for(const b of all)choose.add(new Option(b.title,b.id));choose.value=book.id;right.prepend(choose);
  choose.onchange=async()=>{const id=choose.value;try{const b=await loadVolume(id);if(workspace!==w||id!==choose.value)return;
    second.volume=b;second.chapter=null;second.chapters.replaceChildren();b.chapters.forEach((c,i)=>second.chapters.add(new Option(c.title,i)));
    right.querySelector('.pairBook b').textContent=b.title;second.go(id===book.id?first.at:read('pos.'+id,{chapter:0,p:0,offset:0}));
  }catch{notify('Не удалось открыть книгу');}};
}
