/* Source: supplied trip-planner.html. Estimates are not vehicle measurements. */
(function(root){'use strict';
function val(x,lo,hi){if(x===null||x===undefined||x==='')return null;var n=Number(x);return Number.isFinite(n)&&n>=lo&&n<=hi?n:null}
function consumption(v,t){v=val(v,30,180);t=val(t,-35,55);return v===null||t===null?null:(11+7.4*(v/100)**2)*(1+Math.max(0,15-t)*.012+Math.max(0,t-28)*.006)/100}
function power(p,t){return (p<.5?100:p<.8?100-(p-.5)/.3*50:50-(p-.8)/.2*30)*Math.max(.6,1-Math.max(0,10-t)*.02)}
function chargeMinutes(a,b,cap,t){a=val(a,0,100);b=val(b,0,100);cap=val(cap,30,120);t=val(t,-35,55);if(a===null||b===null||cap===null||t===null||b<a)return null;
var m=0,end=b/100*cap;for(var e=a/100*cap;e<end-1e-9;e+=.2){var step=Math.min(.2,end-e);m+=step/power((e+step/2)/cap,t)*60}return m}
function plan(p){p=p||{};var km=val(p.distanceKm,.01,5000),cap=val(p.capacityKwh,30,120),speed=val(p.speedKmh,30,180),temp=val(p.tempC,-35,55),soc=val(p.startSoc,0,100),min=val(p.minArrivalPct,5,40),max=val(p.maxChargePct,50,100),cons=val(p.obdKwh100,5,80);
if(cons===null&&speed!==null&&temp!==null)cons=consumption(speed,temp)*100;
var out={valid:false,stops:[],arrivalSoc:null,complete:false,status:'Model verisi eksik'};
if(km===null||cap===null||cons===null||min===null||max===null||max<=min+5)return out;
out.valid=true;out.status='';out.energyKwh=km*cons/100;out.consumptionKwh100=cons;out.startSoc=soc;
if(soc===null){out.status='Güncel OBD veya açık seçilmiş başlangıç senaryosu gerekli';return out}
if(soc-km*cons/cap>=min){out.complete=true;out.arrivalSoc=soc-km*cons/cap;out.status='Şarjsız varış · model';return out}
var list=Array.isArray(p.stations)?p.stations:[],seen=new Set();
list=list.filter(function(s){var k=s&&val(s.routeProgressKm,0,km),off=s&&val(s.corridorKm,0,5);if(!s||s.ccs!==true||k===null||off===null)return false;
var id=String(s.id||s.operator+'|'+s.title+'|'+Math.round(k));if(seen.has(id))return false;seen.add(id);return true}).sort(function(a,b){return a.routeProgressKm-b.routeProgressKm});
var at=0,level=soc;for(var i=0;i<12;i++){if(level-(km-at)*cons/cap>=min){out.arrivalSoc=level-(km-at)*cons/cap;out.complete=true;break}
var reachable=at+(level-(min+5))*cap/cons;
var options=list.filter(function(s){return s.routeProgressKm>at+.1&&s.routeProgressKm<=reachable&&!out.stops.some(function(o){return o.routeProgressKm===s.routeProgressKm})});
if(!options.length){out.status='Bu bölümde erişilebilir DC istasyon bulunamadı';break}
options.sort(function(a,b){return b.routeProgressKm-a.routeProgressKm});
var st=options[0],position=st.routeProgressKm,incoming=level-(position-at)*cons/cap;
var next=list.find(function(s){return s.routeProgressKm>position+1});
var target=Math.min(max,Math.max(incoming+1,min+((next?next.routeProgressKm:km)-position)*cons/cap+5));
var m=chargeMinutes(incoming,target,cap,temp);
if(m===null||target<=incoming){out.status='Şarj süresi veya enerji hedefi belirsiz';break}
var kwh=(target-incoming)*cap/100,price=val(p.priceTlKwh,0,150);
out.stops.push({id:st.id||null,title:st.title||'CCS istasyonu',operator:st.operator||'',source:'EPDK',snapshotAt:st.snapshotAt||null,routeProgressKm:position,corridorKm:st.corridorKm,
arrivalSoc:incoming,departureSoc:target,chargeMin:m,energyKwh:kwh,costTl:price===null?null:kwh*price});
at=position;level=target}
if(!out.complete&&!out.status)out.status='DC planı tamamlanamadı';return out}
var api={consumptionKwhKm:consumption,chargeMinutes:chargeMinutes,plan:plan};
if(typeof module==='object'&&module.exports)module.exports=api;root.EyaChargingModel=api;
})(typeof window==='object'?window:globalThis);
