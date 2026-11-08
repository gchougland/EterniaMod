// When a season's Premium pass and season items can be bought. Shared by the pages and the checkout server,
// so both judge a window the same way as the game: whole UTC days, from 00:00 UTC on the first day until the
// end of the last day. A season with no dates is always on sale. Owning something is never affected.
const DAY=/^\d{4}-\d{2}-\d{2}$/,MS_PER_DAY=86400000;
const MONTHS=['January','February','March','April','May','June','July','August','September','October','November','December'];
const midnight=day=>typeof day==='string'&&DAY.test(day)&&!Number.isNaN(Date.parse(day+'T00:00:00Z'))?Date.parse(day+'T00:00:00Z'):null;

/** {state:'unscheduled'|'upcoming'|'on_sale'|'ended', startsOn, endsOn, purchasable} for a pass with startsOn/endsOn. */
export function saleWindow(pass,now=Date.now()){
 const opens=midnight(pass?.startsOn),last=midnight(pass?.endsOn),closes=last===null?null:last+MS_PER_DAY;
 let state='unscheduled';
 if(opens!==null||closes!==null)state=opens!==null&&now<opens?'upcoming':closes!==null&&now>=closes?'ended':'on_sale';
 return {state,startsOn:opens===null?null:pass.startsOn,endsOn:closes===null?null:pass.endsOn,purchasable:state==='unscheduled'||state==='on_sale'};
}
const date=day=>new Date(midnight(day));
/** "1 December 2026". */
export function longDay(day){const d=date(day);return d.getUTCDate()+' '+MONTHS[d.getUTCMonth()]+' '+d.getUTCFullYear();}
/** "30 Jan", with the year only when it is not this year. */
export function shortDay(day,now=Date.now()){const d=date(day),label=d.getUTCDate()+' '+MONTHS[d.getUTCMonth()].slice(0,3);return d.getUTCFullYear()===new Date(now).getUTCFullYear()?label:label+' '+d.getUTCFullYear();}

/** A short badge: {text, tone} or null when there is nothing to say. Tones: open, upcoming, ended. */
export function saleBadge(sale,now=Date.now()){
 switch(sale?.state){
  case 'upcoming':return {text:'Coming soon',tone:'upcoming',title:'Opens '+longDay(sale.startsOn)};
  case 'on_sale':return sale.endsOn?{text:'Available until '+shortDay(sale.endsOn,now),tone:'open',title:'On sale until the end of '+longDay(sale.endsOn)}:null;
  case 'ended':return {text:'Season ended',tone:'ended',title:'No longer for sale'};
  default:return null;
 }
}
/** One friendly sentence about buying Premium for a pass. */
export function premiumNote(pass,sale){
 const name=pass?.name||'This season';
 switch(sale?.state){
  case 'upcoming':return 'Premium goes on sale in the Crown Store when '+name+' begins on '+longDay(sale.startsOn)+'.';
  case 'on_sale':return sale.endsOn?'Premium is in the Crown Store until the end of '+longDay(sale.endsOn)+'.':'Premium is in the Crown Store.';
  case 'ended':return name+' has ended, so Premium is no longer sold. Everyone who bought it keeps it forever.';
  default:return 'Premium is in the Crown Store.';
 }
}
/** Why a checkout outside its window is refused. */
export function closedReason(name,sale){
 const season=name||'its season';
 if(sale?.state==='upcoming')return 'This is not on sale yet. It opens when '+season+' begins on '+longDay(sale.startsOn)+'.';
 if(sale?.state==='ended')return 'Sorry, '+season+' has ended, so this is no longer for sale.';
 return 'This is not on sale right now.';
}
