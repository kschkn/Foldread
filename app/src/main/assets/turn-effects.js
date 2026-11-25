'use strict';

const turnModes={curl:'Бумажный загиб',book:'Книжный разворот',slide:'Скольжение',instant:'Мгновенно'};
const turnTiming={duration:480,easing:'linear',fill:'both'};

// Both the real book and the draggable settings sample use these same surfaces and tracks.
function buildTurn(scene,base,old,dest,direction,mode,startY=.9) {
  const planes=[],tracks=[],two=old.length===2,forward=direction>0;
  const source=two&&forward?1:0,landing=two?1-source:0;
  const rect=scene.getBoundingClientRect(),W=rect.width/old.length,H=rect.height;
  function plane(node,idx,full=false) {
    const layer=el('div','turnPlane');layer.style.left=idx?'50%':'0';
    layer.style.width=two&&!full?'50%':'100%';layer.append(node);scene.append(layer);planes.push(layer);
    return layer;
  }
  function effect(node,frames) {
    // Hold the exact rest poses for a compositor frame at each end of the gesture.
    const moving=frames.map((frame,i)=>({...frame,offset:.025+.95*i/(frames.length-1)}));
    const a=node.animate([{...frames[0],offset:0},...moving,{...frames[frames.length-1],offset:1}],turnTiming);
    a.pause();a.currentTime=0;tracks.push(a);
  }
  function shade(layer) {const s=el('div','turnShade');layer.append(s);effect(s,[{opacity:0},{opacity:.72},{opacity:0}]);}
  function rotate(node,idx,pivot,angles) {
    const layer=plane(node,idx);layer.style.transformOrigin=pivot+' center';
    effect(layer,angles.map(a=>({transform:'perspective(1700px) rotateY('+a+'deg)',visibility:Math.abs(a)===90?'hidden':'visible'})));shade(layer);
  }
  if(mode==='instant') {
    base.replaceChildren(...old);
    const tick=el('i');scene.append(tick);planes.push(tick);
    effect(tick,[{opacity:0},{opacity:0}]);
  } else if(mode==='slide') {
    base.replaceChildren(...dest);
    const sheets=el('div','slideSpread');sheets.append(...old);
    const layer=plane(sheets,0,true);
    effect(layer,[{transform:'translateX(0)'},{transform:'translateX('+(-direction*100)+'%)'}]);
    effect(base,[{transform:'translateX('+(direction*100)+'%)'},{transform:'translateX(0)'}]);
  } else if(mode==='curl') {
    const reverse=!two&&!forward;
    const sign=two?direction:1;
    if(two) base.replaceChildren(forward?old[0]:dest[0],forward?dest[1]:old[1]);
    else base.replaceChildren(reverse?old[0]:dest[0]);
    const paper=plane(reverse?dest[0]:old[source],source);
    const verso=el('div','verso');
    const reverseText=two?dest[landing]:el('div','paperReverse');
    verso.append(reverseText);
    const back=plane(verso,landing);back.style.transformOrigin='0 0';
    const gleam=el('i','curlLight');back.append(gleam);
    const dark=el('i','curlDark');paper.append(dark);
    const frontFrames=[],backFrames=[],lightFrames=[],darkFrames=[];
    const cornerX=sign>0?W:0,cornerY=startY>.5?H:0;
    for(let step=0;step<=40;step++) {
      const p=reverse?1-step/40:step/40;
      const bend=p===1?0:Math.sin(Math.PI*p),dx=2*W*p*sign;
      const dy=(startY>.5?1:-1)*Math.min(H*.55,W*.76)*bend;
      const length=Math.hypot(dx,dy),nx=length?dx/length:sign,ny=length?dy/length:0;
      const midX=cornerX-dx/2,midY=cornerY-dy/2,k=nx*midX+ny*midY;
      const rim=[],oppositeRim=[];
      for(let j=0;j<=10;j++){
        const y=H*j/10;
        const x=(k-ny*y)/nx+sign*W*.035*bend*Math.sin(Math.PI*j/10);
        const percent=Math.min(100,Math.max(0,x*100/W));
        rim.push(percent.toFixed(3)+'% '+(j*10)+'%');
        oppositeRim.push((100-percent).toFixed(3)+'% '+(j*10)+'%');
      }
      const fixed=sign>0?'0%':'100%';
      const clip=points=>'polygon('+fixed+' 0%, '+points.join(', ')+', '+fixed+' 100%)';
      frontFrames.push({clipPath:clip(rim)});
      // Compose the two reflections into one rotation. The verso lands at its
      // native position and raster scale, including on fractional-density displays.
      const a=1-2*nx*nx,b=-2*nx*ny,d=1-2*ny*ny;
      const matrix=[-a,-b,b,d,a*W+2*k*nx+(source-landing)*W,b*W+2*k*ny];
      backFrames.push({clipPath:clip(oppositeRim),transform:'matrix('+matrix.join(',')+')'});
      const middle=(k-ny*H/2)/nx,angle=Math.atan(ny/nx);
      darkFrames.push({transform:'translateX('+middle+'px) skewX('+(-angle)+'rad)',opacity:bend});
      lightFrames.push({transform:'translateX('+(W-middle)+'px) skewX('+angle+'rad)',opacity:bend});
    }
    effect(paper,frontFrames);effect(back,backFrames);effect(gleam,lightFrames);effect(dark,darkFrames);
  } else if(two) {
    base.replaceChildren(forward?old[0]:dest[0],forward?dest[1]:old[1]);
    rotate(old[source],source,forward?'left':'right',[0,-90*direction,-90*direction]);
    rotate(dest[landing],landing,forward?'right':'left',[90*direction,90*direction,0]);
  } else {
    base.replaceChildren(forward?dest[0]:old[0]);
    rotate(forward?old[0]:dest[0],0,'left',forward?[0,-90]:[-90,0]);
  }
  return {planes,tracks};
}

function animationSettings() {
  const area=panel('Перелистывание');
  const mode=settings.turnMode||'book';
  const demo=el('div','turnDemo'),base=el('div','demoBase');
  demo.setAttribute('aria-label','Потяните страницу для предпросмотра');demo.append(base);area.append(demo);
  let motion=null,serial=0,x=0,progress=0,number=1;
  function sheets(n){return [n,n+1].map(k=>{const s=el('div','demoPage');s.append(el('b','','Глава '+numeral(k-1)),el('em','','Ветер шевелил страницы, и море дышало вдали.'),el('small','',k));return s;});}
  function reset(){if(motion){motion.tracks.forEach(a=>a.cancel());motion.planes.forEach(l=>l.remove());motion=null;}base.replaceChildren(...sheets(number));}
  reset();
  demo.onpointerdown=e=>{reset();serial++;x=e.clientX;progress=0;demo.setPointerCapture(e.pointerId);motion=buildTurn(demo,base,[...base.children],sheets(number+2),1,mode,(e.clientY-demo.getBoundingClientRect().top)/demo.clientHeight);};
  demo.onpointermove=e=>{if(motion&&demo.hasPointerCapture(e.pointerId)){progress=Math.min(.999,Math.abs(e.clientX-x)/demo.clientWidth);motion.tracks.forEach(a=>a.currentTime=480*progress);}};
  demo.onpointerup=()=>{if(!motion)return;const id=serial;number+=2;if(mode==='instant'){reset();return;}
    motion.tracks[0].onfinish=()=>{if(id===serial)reset();};motion.tracks.forEach(a=>a.play());};
  demo.onpointercancel=reset;
  area.append(el('p','settingHint','Потяните миниатюру или коснитесь её.'));
  for(const [key,name] of Object.entries(turnModes)){
    const b=button(name,'toolRow',()=>{settings.turnMode=key;write('settings',settings);animationSettings();});
    b.setAttribute('aria-pressed',key===mode);area.append(b);
  }
}
