'use strict';

function readingDate(date){
  return [date.getFullYear(),String(date.getMonth()+1).padStart(2,'0'),String(date.getDate()).padStart(2,'0')].join('-');
}
function readingDuration(ms){
  const minutes=Math.floor(ms/60000),hours=Math.floor(minutes/60);
  if(hours)return hours+' ч '+minutes%60+' мин';
  return ms>0&&minutes===0?'< 1 мин':minutes+' мин';
}
function openWidgetStats(){
  if(window.Library?.takeWidgetStats?.())showStatistics();
}
function showStatistics(){
  const area=panel('Статистика чтения');$('#sheet').classList.add('statsSheet');
  const days=JSON.parse(window.Library?.readingDays?.()||'{}'),books=catalog();
  const finished=books.filter(b=>read('completed.'+b.id,false));
  const dates=finished.map(b=>({date:read('finishedDate.'+b.id,''),title:b.title})).filter(b=>b.date);
  const today=new Date(),todayKey=readingDate(today);
  let year=today.getFullYear(),month=today.getMonth(),selected=today.getDate(),annual=false;
  const summary=el('div','statsSummary');
  for(const [title,value] of [
    ['Время чтения',readingDuration(Number(window.Library?.readingMillis?.()||0))],
    ['Дней чтения',Object.values(days).filter(ms=>ms>0).length],['Дочитано',finished.length],['На полке',books.length]
  ]){
    const tile=el('div');tile.append(el('strong','',value),el('span','',title));summary.append(tile);
  }
  const tabs=el('div','choices statsTabs'),period=el('div','statPeriod'),chart=el('div');
  const daily=button('По дням','',()=>{annual=false;if(year===today.getFullYear())month=Math.min(month,today.getMonth());draw();}),monthly=button('По месяцам','',()=>{annual=true;draw();});
  tabs.append(daily,monthly);area.append(summary,tabs,period,chart);
  function totals(prefix){
    return Object.entries(days).reduce((sum,[day,ms])=>day.startsWith(prefix)?sum+ms:sum,0);
  }
  function move(step){
    const date=new Date(year+(annual?step:0),month+(annual?0:step),1);
    year=date.getFullYear();month=date.getMonth();selected=1;draw();
  }
  function draw(){
    const first=new Date(year,month,1),prefix=readingDate(first).slice(0,annual?4:7);
    const title=annual?String(year):first.toLocaleDateString('ru',{month:'long',year:'numeric'}).replace(/\s*г\.$/,'');
    for(const [tab,active] of [[daily,!annual],[monthly,annual]]){tab.classList.toggle('selected',active);tab.setAttribute('aria-pressed',active);}
    const prev=button('‹','round',()=>move(-1)),next=button('›','round',()=>move(1));
    prev.setAttribute('aria-label',annual?'Предыдущий год':'Предыдущий месяц');next.setAttribute('aria-label',annual?'Следующий год':'Следующий месяц');
    next.disabled=year===today.getFullYear()&&(annual||month>=today.getMonth());
    const heading=el('div');heading.append(el('h3','',title),el('span','',readingDuration(totals(prefix))+' · Книг дочитано '+dates.filter(b=>b.date.startsWith(prefix)).length));
    period.replaceChildren(prev,heading,next);chart.replaceChildren();
    if(annual){
      const list=el('div','monthList'),values=Array.from({length:12},(_,m)=>totals(readingDate(new Date(year,m,1)).slice(0,7)));
      const peak=Math.max(1,...values);
      values.forEach((ms,m)=>{
        const name=new Date(year,m,1).toLocaleDateString('ru',{month:'long'});
        const row=button('','monthRow',()=>{month=m;selected=1;annual=false;draw();});
        row.disabled=year===today.getFullYear()&&m>today.getMonth();
        const track=el('span','monthTrack'),fill=el('i');fill.style.width=(100*ms/peak)+'%';track.append(fill);
        row.append(el('span','',name),track,el('b','',readingDuration(ms)));list.append(row);
      });chart.append(list);return;
    }
    const grid=el('div','statCalendar'),detail=el('p','statDetail');
    chart.append(el('p','statUnit','Минуты чтения по дням'),grid,detail);
    for(const name of ['Пн','Вт','Ср','Чт','Пт','Сб','Вс'])grid.append(el('small','',name));
    const length=new Date(year,month+1,0).getDate(),peak=Math.max(1,...Object.entries(days).filter(([day])=>day.startsWith(prefix)).map(([,ms])=>ms));
    selected=Math.min(selected,length);
    function select(day,cell){
      selected=day;
      grid.querySelector('[aria-pressed=true]')?.setAttribute('aria-pressed','false');cell.setAttribute('aria-pressed','true');
      const date=new Date(year,month,day),key=readingDate(date),titles=dates.filter(b=>b.date===key).map(b=>'«'+b.title+'»');
      detail.textContent=date.toLocaleDateString('ru',{day:'numeric',month:'long'})+' · '+readingDuration(days[key]||0)+(titles.length?' · '+titles.join(', '):'');
    }
    for(let d=1;d<=length;d++){
      const date=new Date(year,month,d),key=readingDate(date),ms=days[key]||0;
      const cell=button('','statDay',()=>select(d,cell));
      if(d===1)cell.style.gridColumnStart=String((first.getDay()+6)%7+1);
      if(ms)cell.dataset.level=String(Math.ceil(3*ms/peak));
      cell.setAttribute('aria-label',date.toLocaleDateString('ru',{day:'numeric',month:'long'})+', '+readingDuration(ms));
      cell.setAttribute('aria-pressed','false');cell.disabled=key>todayKey;
      cell.append(el('span','',d),el('small','',ms?(ms<60000?'<1':Math.floor(ms/60000)):'—'));grid.append(cell);
      if(d===selected)select(d,cell);
    }
  }
  draw();
}
