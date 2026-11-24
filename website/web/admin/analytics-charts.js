// Graphs for the Analytics page, drawn as plain SVG from the same CSV reports the download buttons give.
const NS='http://www.w3.org/2000/svg';
const BLUE='var(--viz-1)',ORANGE='var(--viz-2)';
const H=220,M={top:12,right:12,bottom:28,left:48};

export async function fetchReport(id,from,to){
 const res=await fetch('/api/admin/analytics/'+id+'.csv?'+new URLSearchParams({from,to}),{cache:'no-store'});
 if(!res.ok){let why='Could not load the '+id+' report';try{why=(await res.json()).error||why;}catch{}throw new Error(why);}
 return parseCsv(await res.text());
}

export function parseCsv(text){
 const rows=[];let row=[],cell='',quoted=false;
 for(let i=0;i<text.length;i++){const c=text[i];
  if(quoted){if(c==='"'){if(text[i+1]==='"'){cell+='"';i++;}else quoted=false;}else cell+=c;continue;}
  if(c==='"')quoted=true;else if(c===','){row.push(cell);cell='';}
  else if(c==='\n'||c==='\r'){if(c==='\r'&&text[i+1]==='\n')i++;row.push(cell);rows.push(row);row=[];cell='';}
  else cell+=c;}
 if(cell||row.length){row.push(cell);rows.push(row);}
 const [head,...body]=rows;if(!head)return [];
 // The game puts an apostrophe before cells a spreadsheet would read as a formula; the page shows the plain text.
 const plain=v=>/^'[=+\-@]/.test(v)?v.slice(1):v;
 return body.filter(r=>r.length>1||r[0]).map(r=>Object.fromEntries(head.map((h,i)=>[h,plain(r[i]??'')])));
}

const num=v=>{const n=Number(v);return Number.isFinite(n)?n:0;};
const fmt=n=>Math.round(n).toLocaleString();
const MONTHS=['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
const short=d=>{const [,m,day]=d.split('-');return MONTHS[Number(m)-1]+' '+Number(day);};

export async function drawAll(root,from,to,current=()=>true){
 root.replaceChildren(note('Building graphs…'));
 // The first report reads the game data; the game keeps it for 30 seconds, so the other three are quick and do not queue on the bridge.
 const daily=await fetchReport('daily',from,to);
 const [weekly,players,items]=await Promise.all(['weekly','players','shop_items'].map(id=>fetchReport(id,from,to)));
 if(!current())return;
 root.replaceChildren();
 const active=players.filter(p=>num(p.active_days_in_range)>0);
 const samples=daily.reduce((s,d)=>s+num(d.samples),0);
 const avg=samples?daily.reduce((s,d)=>s+num(d.average_concurrent)*num(d.samples),0)/samples:null;
 const crowns=daily.reduce((s,d)=>s+num(d.crowns_spent),0);
 const tiles=el('div','viz-tiles');
 for(const [label,value] of [
  ['Players',fmt(active.length)],['New players',fmt(daily.reduce((s,d)=>s+num(d.new_players),0))],
  ['Most online at once',fmt(Math.max(0,...daily.map(d=>num(d.peak_concurrent))))],['Average online',avg===null?'None yet':avg.toFixed(1)],
  ['Crown Store buyers',fmt(players.filter(p=>num(p.crown_store_orders)>0).length)],['Crowns spent',fmt(crowns)],
  ['Crowns per player',active.length?fmt(crowns/active.length):'None yet'],['Tebex buyers',fmt(players.filter(p=>num(p.tebex_purchases)>0).length)]]){
  const t=el('div','viz-tile');t.append(el('div','viz-tile-value',value),el('div','viz-tile-label',label));tiles.append(t);}
 root.append(tiles);
 const days=daily.map(d=>d.period_start);
 card(root,'Players each day','New players are playing for the first time. Returning players played before.',c=>bars(c,days.map(short),[
  {name:'Returning',color:BLUE,values:daily.map(d=>num(d.returning_players))},{name:'New',color:ORANGE,values:daily.map(d=>num(d.new_players))}]));
 card(root,'Players online','Most online at once and the average, from a count every five minutes.',c=>lines(c,days.map(short),[
  {name:'Most online',color:BLUE,fmt,values:daily.map(d=>d.samples==='0'?null:num(d.peak_concurrent))},{name:'Average',color:ORANGE,values:daily.map(d=>d.average_concurrent===''?null:num(d.average_concurrent))}],v=>v.toFixed(1)));
 const weeks=weekly.filter(w=>w.retention_next_pct!=='');
 card(root,'New players who came back','Of each week\'s new players, the share who played again the next week.',c=>bars(c,weeks.map(w=>'Week of '+short(w.period_start)),[
  {name:'Came back',color:BLUE,values:weeks.map(w=>num(w.retention_next_pct))}],v=>v.toFixed(1)+'%',v=>v+'%',100));
 card(root,'Crowns spent in the Crown Store','Crowns spent each day.',c=>bars(c,days.map(short),[{name:'Crowns',color:BLUE,values:daily.map(d=>num(d.crowns_spent))}]));
 const top=[...items].sort((a,b)=>num(b.crowns)-num(a.crowns)).slice(0,10);
 card(root,'Best selling items','The ten items that earned the most Crowns in these dates.',c=>hbars(c,top.map(i=>i.name||i.item_id),top.map(i=>num(i.crowns)),top.map(i=>fmt(num(i.orders))+' orders, '+fmt(num(i.unique_buyers))+' buyers')));
}

function note(text){return el('p','muted',text);}
function el(tag,cls,text){const n=document.createElement(tag);if(cls)n.className=cls;if(text!==undefined)n.textContent=text;return n;}
function svg(tag,attrs={}){const n=document.createElementNS(NS,tag);for(const [k,v] of Object.entries(attrs))n.setAttribute(k,v);return n;}

function card(root,title,about,draw){
 const box=el('section','panel viz-card');box.append(el('h2','',title),el('p','muted',about));
 const plot=el('div','viz-plot');box.append(plot);root.append(box);
 draw(plot);
}

function niceMax(max){if(max<=4)return 4;const step=10**Math.floor(Math.log10(max/4));for(const m of [1,2,2.5,5,10])if(m*step*4>=max)return m*step*4;return max;}

function frame(plot,labels,max,yfmt){
 const W=Math.max(280,plot.clientWidth||600),iw=W-M.left-M.right,ih=H-M.top-M.bottom;
 const s=svg('svg',{viewBox:`0 0 ${W} ${H}`,width:W,height:H,role:'img'});
 const top=niceMax(max),y=v=>M.top+ih-(v/top)*ih;
 for(let i=0;i<=4;i++){const v=top*i/4,yy=y(v);
  s.append(svg('line',{x1:M.left,x2:W-M.right,y1:yy,y2:yy,class:i?'viz-grid':'viz-axis'}));
  const t=svg('text',{x:M.left-8,y:yy+4,'text-anchor':'end',class:'viz-tick'});t.textContent=yfmt(v);s.append(t);}
 const band=iw/Math.max(1,labels.length),x=i=>M.left+band*i+band/2;
 const every=Math.max(1,Math.ceil(labels.length/Math.max(2,Math.floor(iw/80))));
 labels.forEach((l,i)=>{if(i%every)return;const t=svg('text',{x:x(i),y:H-8,'text-anchor':'middle',class:'viz-tick'});t.textContent=l;s.append(t);});
 return {s,W,iw,ih,band,x,y};
}

function legend(plot,series){if(series.length<2)return;const row=el('div','viz-legend');
 for(const se of series){const item=el('span','viz-key');const sw=el('i','viz-swatch');sw.style.background=se.color;item.append(sw,document.createTextNode(se.name));row.append(item);}plot.append(row);}

function hover(plot,f,labels,series,vfmt,stacked){
 const tip=el('div','viz-tip');tip.hidden=true;plot.append(tip);
 const band=svg('rect',{class:'viz-hover',y:M.top,height:f.ih,width:f.band,opacity:0});f.s.insertBefore(band,f.s.firstChild);
 const pad=svg('rect',{x:M.left,y:M.top,width:f.iw,height:f.ih,fill:'transparent'});f.s.append(pad);
 const show=e=>{const r=f.s.getBoundingClientRect(),px=(e.clientX-r.left)*(f.W/r.width);
  const i=Math.min(labels.length-1,Math.max(0,Math.floor((px-M.left)/f.band)));
  band.setAttribute('x',M.left+f.band*i);band.setAttribute('opacity',1);
  tip.replaceChildren(el('strong','',labels[i]));
  for(const se of stacked?[...series].reverse():series){const vf=se.fmt||vfmt;const line=el('div','viz-tip-row');const sw=el('i','viz-swatch');sw.style.background=se.color;
   line.append(sw,document.createTextNode(se.name+': '+(se.values[i]===null?'no data':vf(se.values[i]))));tip.append(line);}
  tip.hidden=false;const left=(f.x(i)/f.W)*r.width;tip.style.left=Math.min(r.width-tip.offsetWidth-4,Math.max(4,left+12))+'px';};
 pad.addEventListener('pointermove',show);pad.addEventListener('pointerleave',()=>{tip.hidden=true;band.setAttribute('opacity',0);});
}

function table(plot,labels,series,vfmt){
 const d=el('details','viz-table');d.append(el('summary','','Show the numbers'));const t=el('table');const head=el('tr');head.append(el('th','',''));
 for(const se of series)head.append(el('th','',se.name));t.append(head);
 labels.forEach((l,i)=>{const r=el('tr');r.append(el('td','',l));for(const se of series)r.append(el('td','',se.values[i]===null?'':(se.fmt||vfmt)(se.values[i])));t.append(r);});
 d.append(t);plot.append(d);
}

function empty(plot,series){if(series.every(se=>se.values.every(v=>!v))){plot.append(note('Nothing recorded for these dates yet.'));return true;}return false;}

// A path for a bar with its top corners rounded, so the data end reads clearly and the base sits on the axis.
function barPath(x,y,w,h,r){if(h<=0)return '';r=Math.min(r,w/2,h);return `M${x},${y+h}V${y+r}Q${x},${y} ${x+r},${y}H${x+w-r}Q${x+w},${y} ${x+w},${y+r}V${y+h}Z`;}

function bars(plot,labels,series,vfmt=fmt,axis=fmt,cap=Infinity){
 legend(plot,series);if(empty(plot,series))return;
 const totals=labels.map((_,i)=>series.reduce((s,se)=>s+(se.values[i]||0),0));
 const f=frame(plot,labels,Math.min(cap,Math.max(...totals)),axis);const w=Math.min(28,Math.max(2,f.band*0.7));
 labels.forEach((_,i)=>{let base=0;const last=series.map(se=>se.values[i]||0).findLastIndex(v=>v>0);
  series.forEach((se,k)=>{const v=se.values[i]||0;if(!v)return;
   const y0=f.y(base),y1=f.y(base+v),gap=base>0?2:0,h=y0-y1-gap;
   if(h>0)f.s.append(svg('path',{d:k===last?barPath(f.x(i)-w/2,y1,w,h,4):`M${f.x(i)-w/2},${y1}h${w}v${h}h${-w}Z`,fill:se.color}));base+=v;});});
 plot.append(f.s);hover(plot,f,labels,series,vfmt,series.length>1);table(plot,labels,series,vfmt);
}

function lines(plot,labels,series,vfmt=fmt){
 legend(plot,series);if(empty(plot,series))return;
 const f=frame(plot,labels,Math.max(0,...series.flatMap(se=>se.values.filter(v=>v!==null))),fmt);
 for(const se of series){let d='';se.values.forEach((v,i)=>{if(v===null){d+=' ';return;}d+=(d===''||d.endsWith(' ')?'M':'L')+f.x(i)+','+f.y(v);});
  f.s.append(svg('path',{d:d.trim(),fill:'none',stroke:se.color,'stroke-width':2,'stroke-linejoin':'round','stroke-linecap':'round'}));
  se.values.forEach((v,i)=>{const lone=v!==null&&se.values[i-1]==null&&se.values[i+1]==null;if(lone)f.s.append(svg('circle',{cx:f.x(i),cy:f.y(v),r:4,fill:se.color}));});}
 plot.append(f.s);hover(plot,f,labels,series,vfmt);table(plot,labels,series,vfmt);
}

function hbars(plot,names,values,details){
 if(!values.some(v=>v>0)){plot.append(note('No Crown Store sales in these dates yet.'));return;}
 const list=el('div','viz-hbars');const max=Math.max(...values);
 names.forEach((n,i)=>{const row=el('div','viz-hbar');const bar=el('div','viz-hbar-track');const fill=el('div','viz-hbar-fill');
  fill.style.width=(values[i]/max*100)+'%';bar.append(fill);
  row.append(el('div','viz-hbar-name',n),bar,el('div','viz-hbar-value',fmt(values[i])+' Crowns'),el('div','viz-hbar-detail muted',details[i]));list.append(row);});
 plot.append(list);
}
