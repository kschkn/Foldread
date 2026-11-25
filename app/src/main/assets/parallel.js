'use strict';

let pair=null;
function chooseParallel() {
  const area=panel('Оригинал и перевод'),others=catalog().filter(b=>b.id!==book.id);
  area.append(el('p','settingHint','Слева — «'+book.title+'». Выберите книгу для правой страницы.'));
  for(const item of others)area.append(row(item.title,item.author||item.format,coverArt(item),async event=>{
    try{
      const original=book,choice=event.currentTarget;
      const response=await fetch(item.id==='sails'?'sails.json':'books/'+item.id+'.json');
      if(!response.ok)throw Error();
      const other=await response.json();
      if(book===original && $('#sheet').open && choice.isConnected && !pair)openParallel(other);
    }catch{notify('Не удалось открыть вторую книгу');}
  }));
  if(!others.length)area.append(el('p','empty','Добавьте вторую книгу в библиотеку, затем откройте эту команду снова.'));
  area.append(button('Добавить книгу','primary',()=>window.Library.choose()));
}
function openParallel(other){
  closeSheet();closeNote();cancelTurn();
  const key='pair.'+book.id+'.'+other.id,stored=read(key,{});
  const shell=el('section','parallel');shell.id='parallel';shell.setAttribute('aria-label','Параллельное чтение');
  const bar=el('header','pairHeader'),panes=el('div','pairPanes'),tools=el('div','pairTools');
  bar.append(el('h2','','Оригинал и перевод'),button('Закрыть','',closeParallel));
  const toggle=button('Показать перевод','paneToggle',()=>{
    const right=shell.classList.toggle('showRight');toggle.textContent=right?'Показать оригинал':'Показать перевод';
  });
  const link=button('Связать абзацы','',()=>{
    const [a,b]=pair.panes.map(p=>p.selection);
    if(!a || !b){notify('Коснитесь нужного абзаца в каждой книге');return;}
    if(!pair.links.some(v=>v.every((pos,i)=>pos.chapter===[a,b][i].chapter && pos.p===[a,b][i].p)))pair.links.push([a,b]);
    pair.save();updateLinks();notify('Абзацы связаны');
  });
  const links=button('','',()=>{
    const area=panel('Связанные абзацы');
    if(!pair.links.length)area.append(el('p','empty','Коснитесь по абзацу в обеих книгах и нажмите «Связать абзацы».'));
    for(const entry of pair.links) {
      const [a,b]=entry,ca=book.chapters[a.chapter],cb=other.chapters[b.chapter];
      area.append(row(ca.title+' · '+cb.title,ca.paragraphs[a.p].slice(0,100),icon('book'),()=>{
        closeSheet();pair.panes.forEach((pane,i)=>pane.go(entry[i],true));pair.save();
      }));
    }
  });
  function updateLinks(){links.textContent='Связи · '+pair.links.length;}
  tools.append(toggle,link,links);shell.append(bar,panes,tools);document.body.append(shell);
  pair={key,links:stored.links||[],panes:[],save(){write(key,{links:this.links,places:this.panes.map(p=>p.at)});}};
  for(const [i,volume] of [book,other].entries()){
    const pane=new ParallelPane(volume,panes,i);
    pair.panes.push(pane);
    pane.go(stored.places?.[i] || (i===0?position:read('pos.'+other.id,{chapter:0,p:0,offset:0})));
  }
  updateLinks();syncFold();
  pair.observer=new ResizeObserver(()=>{
    if(!pair)return;
    for(const p of pair.panes){
      const bounds=p.view.getBoundingClientRect();
      if(p.w!==bounds.width || p.h!==bounds.height)p.go(p.at);
    }
  });
  for(const p of pair.panes)pair.observer.observe(p.view);
}
class ParallelPane {
  constructor(volume,host,index) {
    this.volume=volume;
    const node=el('article','pairPane'),header=el('div','pairBook');
    header.append(el('small','settingLabel',index?'Перевод':'Оригинал'),el('b','',volume.title));
    this.chapters=el('select');this.chapters.setAttribute('aria-label','Глава · '+volume.title);
    volume.chapters.forEach((c,i)=>this.chapters.add(new Option(c.title,i)));
    this.chapters.onchange=()=>{this.go({chapter:Number(this.chapters.value),p:0,offset:0});pair.save();};
    this.view=el('div','pairViewport');this.flow=el('div','pairFlow');this.view.append(this.flow);
    const nav=el('nav','pairNav');
    this.prev=button('‹','round',()=>this.step(-1));this.next=button('›','round',()=>this.step(1));this.label=el('small');
    this.prev.setAttribute('aria-label','Назад · '+volume.title);this.next.setAttribute('aria-label','Вперёд · '+volume.title);
    nav.append(this.prev,this.label,this.next);node.append(header,this.chapters,this.view,nav);host.append(node);
    let down=null;
    this.view.onpointerdown=e=>{
      if(!e.isPrimary)return;
      down={id:e.pointerId,x:e.clientX,y:e.clientY,p:e.target.closest('p')};this.view.setPointerCapture(e.pointerId);
    };
    this.view.onpointercancel=()=>{down=null;};
    this.view.onpointerup=e=>{
      if(down?.id!==e.pointerId)return;
      const dx=e.clientX-down.x,dy=e.clientY-down.y;
      if(Math.abs(dx)>32 && Math.abs(dx)>Math.abs(dy))this.step(dx<0?1:-1);
      else if(Math.abs(dx)+Math.abs(dy)<15 && down.p)this.select(Number(down.p.dataset.p));
      down=null;
    };
  }
  select(p){
    this.flow.querySelector('.pairSelection')?.classList.remove('pairSelection');
    this.selection={chapter:this.chapter,p,offset:0};this.nodes[p]?.classList.add('pairSelection');
  }
  go(at,select=false) {
    const c=Math.max(0,Math.min(this.volume.chapters.length-1,at.chapter));
    const bounds=this.view.getBoundingClientRect();this.w=bounds.width;this.h=bounds.height;
    this.flow.style.columnWidth=this.w+'px';
    if(this.chapter!==c){
      this.chapter=c;this.selection=null;this.chapters.value=c;
      const content=this.volume.chapters[c];
      this.nodes=content.paragraphs.map((s,i)=>{const n=el('p','',s);n.dataset.p=i;return n;});
      const fragment=document.createDocumentFragment();
      for(const paragraph of this.nodes)fragment.append(paragraph);
      this.flow.replaceChildren(fragment);
    }
    this.count=Math.max(1,Math.round(this.flow.scrollWidth/Math.max(1,this.w)));
    const p=Math.max(0,Math.min(this.nodes.length-1,at.p));
    this.display(at.end?this.count-1:this.column(p,at.offset||0));
    this.at=at.end?this.first():{chapter:c,p,offset:at.offset||0};
    if(select)this.select(p);
  }
  column(p,offset){
    const text=this.nodes[p]?.firstChild;if(!text?.length || !this.w)return 0;
    const i=Math.min(offset,text.length-1),range=document.createRange();range.setStart(text,i);range.setEnd(text,i+1);
    const rect=range.getClientRects()[0];
    return rect?Math.floor((rect.left-this.flow.getBoundingClientRect().left+.25)/this.w):0;
  }
  first(){
    if(!this.page || !this.nodes.length)return {chapter:this.chapter,p:0,offset:0};
    let p=0,upper=this.nodes.length-1;
    while(p<upper){const mid=(p+upper)>>1;if(this.column(mid,this.nodes[mid].textContent.length-1)<this.page)p=mid+1;else upper=mid;}
    let offset=0;upper=this.nodes[p].textContent.length-1;
    while(offset<upper){const mid=(offset+upper)>>1;if(this.column(p,mid)<this.page)offset=mid+1;else upper=mid;}
    return {chapter:this.chapter,p,offset};
  }
  display(n){
    this.page=Math.max(0,Math.min(this.count-1,n));this.view.scrollLeft=this.page*this.w;
    this.prev.disabled=this.page===0 && this.chapter===0;
    this.next.disabled=this.page===this.count-1 && this.chapter===this.volume.chapters.length-1;
    this.label.textContent=(this.page+1)+' / '+this.count;
  }
  step(dir){
    if((dir<0?this.prev:this.next).disabled)return;
    const n=this.page+dir;
    if(n<0 || n>=this.count)this.go({chapter:this.chapter+dir,p:0,offset:0,end:dir<0});
    else{this.display(n);this.at=this.first();}
    pair.save();
  }
}
function closeParallel(){
  if(!pair)return;
  pair.save();const at=pair.panes[0].at;pair.observer.disconnect();pair=null;$('#parallel').remove();
  jump(at);syncFold();
}
