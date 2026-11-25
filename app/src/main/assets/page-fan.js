'use strict';

function clearFanTimer(){clearTimeout(fanTimer);fanTimer=0;}
function armFan(event) {
  clearFanTimer();
  if(event.clientY<innerHeight-44 || !document.body.classList.contains('reading'))return;
  const id=event.pointerId,x=event.clientX;
  fanTimer=setTimeout(()=>{if(touch?.id===id && !touch.drag && !touch.light)startFan(id,x);},400);
}
function charNumber(at) {
  return chapterStarts[at.chapter]+(paragraphOffsets[at.chapter][at.p]??chapterLengths[at.chapter])+at.offset;
}
function lastStart(values,value) {
  let lo=0,hi=values.length;
  while(lo+1<hi){const mid=(lo+hi)>>1;if(values[mid]<=value)lo=mid;else hi=mid;}
  return lo;
}
function fanSeek(value) {
  const n=Math.max(0,Math.min(totalLength-1,Math.round(value)));
  const c=lastStart(chapterStarts,n),local=n-chapterStarts[c],p=lastStart(paragraphOffsets[c],local);
  const at={chapter:c,p,offset:local-paragraphOffsets[c][p]};
  if(c!==chapter){chapter=c;layout(at);}
  else {const next=alignPage(pageOf(at));if(next===page)return;page=next;render();}
  fanPaint();
}
function fanStep(direction) {
  const next=page+direction*columns;
  if(next<0 || next>=pages){
    if(!book.chapters[chapter+direction])return;
    chapter+=direction;layout(direction>0?null:'end');
  }else{page=next;render();}
  fanPaint();
}
function fanPaint() {
  const {cards,caption,slider}=fanSession,scene=$('#spread');
  cards.replaceChildren();
  for(const delta of [-1,0,1]) {
    const index=page+columns*delta;
    if(index<0 || index>=pages)continue;
    const card=button('','fanCard',()=>delta?fanStep(delta):endFan(true));
    card.dataset.side=delta;card.setAttribute('aria-label','Открыть страницу '+(index+1));
    const inner=el('span','fanPaper'+(columns===2?' two':''));
    inner.append(leafAt('left',index));if(columns===2)inner.append(leafAt('right',index+1));
    card.append(inner);cards.append(card);
    const scale=card.clientWidth/scene.clientWidth;
    inner.style.cssText='width:'+scene.clientWidth+'px;height:'+scene.clientHeight+'px;transform:scale('+scale+')';
    card.style.height=(scene.clientHeight*scale)+'px';
  }
  slider.value=charNumber(anchor(page));
  caption.textContent=textOf().title+' · '+(page+1)+(columns===2&&page+1<pages?'–'+(page+2):'')+' / '+pages;
  fanSession.prev.disabled=$('#prev').disabled;fanSession.next.disabled=$('#next').disabled;
}
function startFan(id=null,x=0) {
  if(!book || pendingTurn || fanSession)return;
  touch=null;clearFanTimer();closeSheet();
  const overlay=el('section','fanOverlay');overlay.id='fan';overlay.setAttribute('aria-label','Веер страниц');
  const panel=el('div','fanPanel'),caption=el('strong','fanCaption'),cards=el('div','fanCards');
  const slider=el('input');slider.type='range';slider.min=0;slider.max=totalLength-1;slider.setAttribute('aria-label','Место в книге');
  const nav=el('div','fanTools'),prev=button('‹','round',()=>fanStep(-1)),next=button('›','round',()=>fanStep(1));
  prev.setAttribute('aria-label','Предыдущий разворот');next.setAttribute('aria-label','Следующий разворот');
  nav.append(prev,button('Отмена','',()=>endFan(false)),button('Читать отсюда','heroButton',()=>endFan(true)),next);
  panel.append(caption,cards,slider,nav);overlay.append(panel);document.body.append(overlay);
  fanSession={id,x,original:{...position},start:charNumber(anchor(page)),cards,caption,slider,prev,next};
  slider.oninput=()=>fanSeek(Number(slider.value));syncFold();fanPaint();
}
function endFan(commit) {
  if(!fanSession)return;
  const original=fanSession.original,at=anchor(page),changed=charNumber(at)!==fanSession.start;
  $('#fan').remove();fanSession=null;
  if(commit && changed){holdPlace();jump(at,false);}
  else{chapter=original.chapter;layout(original);}
  syncFold();restartReadingClock();
}
document.addEventListener('pointermove',e=>{
  if(fanSession?.id!==e.pointerId)return;
  e.stopPropagation();
  const delta=e.clientX-fanSession.x;
  const travel=delta<0?fanSession.x:innerWidth-fanSession.x;
  const available=delta<0?fanSession.start:totalLength-fanSession.start;
  fanSeek(fanSession.start+delta/Math.max(1,travel)*available);
},true);
for(const type of ['pointerup','pointercancel'])document.addEventListener(type,e=>{
  if(fanSession?.id!==e.pointerId)return;
  e.stopPropagation();endFan(type==='pointerup');
},true);
