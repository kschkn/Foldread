'use strict';

// Android reports the bounds in window pixels. Scale against the current WebView
// viewport so split screen, rotation and a display switch use the same coordinates.
let readerCutout=null;
function layoutReaderCutout(bounds){
  readerCutout=bounds;
  const root=document.documentElement,style=root.style;
  const w=innerWidth,h=innerHeight;
  const [nativeW,nativeH,l,t,r,b,start,end]=bounds||[w,h,0,0,0,0,0,0];
  if(!nativeW||!nativeH)return;
  const left=l*w/nativeW,right=r*w/nativeW,top=t*h/nativeH,bottom=b*h/nativeH;
  const gapStart=Math.max(0,start*w/nativeW-6),gapEnd=Math.min(w,end*w/nativeW+6);
  for(const [key,value] of Object.entries({left,top,right,bottom})){
    if(bounds)style.setProperty('--cut-'+key,Math.ceil(value)+'px');
    else style.removeProperty('--cut-'+key);
  }
  const split=top>0&&start<end&&w<=690&&gapStart-left>=108&&w-gapEnd-right>=140;
  root.classList.toggle('cameraGap',split);
  style.setProperty('--camera-start',gapStart+'px');style.setProperty('--camera-width',(gapEnd-gapStart)+'px');
}
window.applyReaderCutout=bounds=>{
  layoutReaderCutout(bounds);
  if(typeof book!=='undefined'&&book)onReaderResize();
};
layoutReaderCutout(JSON.parse(window.Library?.displayCutout?.()||'null'));
window.addEventListener('resize',()=>layoutReaderCutout(readerCutout));
