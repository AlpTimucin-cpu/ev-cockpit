/* EV Cockpit – real-route-aware trip planner.
   Independent of the Leaflet and native navigation owners. No fabricated stations,
   telemetry, traffic, pricing or location. The energy curve is a MODEL only. */
(function(root) {
'use strict';
function value(x,low,high) {
  if(x===null||x===undefined||x==='')return null;
  var n=Number(String(x).replace(',','.'));
  return Number.isFinite(n)&&n>=low&&n<=high?n:null;
}
function consumption(speed,temp) {
  var s=value(speed,30,180),t=value(temp,-35,55);
  if(s===null||t===null)return null;
  return (11+7.4*Math.pow(s/100,2))*
    (1+Math.max(0,15-t)*.012+Math.max(0,t-28)*.006);
}
function power(soc,temp) {
  var p=soc<.5?100:soc<.8?100-(soc-.5)/.3*50:50-(soc-.8)/.2*30;
  return p*Math.max(.6,1-Math.max(0,10-temp)*.02);
}
function chargingMinutes(startPct,endPct,capacity,temp) {
  var a=value(startPct,0,100),b=value(endPct,0,100),
      c=value(capacity,30,120),t=value(temp,-35,55);
  if(a===null||b===null||c===null||t===null||b<a)return null;
  var minutes=0,start=c*a/100,end=c*b/100;
  for(var energy=start;energy<end;energy+=.2) {
    var next=Math.min(end,energy+.2);
    minutes+=(next-energy)/power((energy+next)/2/c,t)*60;
  }
  return minutes;
}
function forecast(o) {
  o=o||{};
  var d=value(o.distanceKm,.05,5000),
      drive=value(o.durationMin,.01,10000),
      soc=value(o.soc,0,100),
      cap=value(o.capacity,30,120),
      c=value(o.consumptionKwh100,5,80),
      min=value(o.minArrival,0,40);
  if(d===null||drive===null||cap===null||c===null||min===null)
    return {valid:false};
  var energy=d*c/100,
      departure=soc===null?null:soc,
      raw=soc===null?null:soc-energy/cap*100,
      arrival=o.stopIncluded===true?null:raw;
  var out={valid:true,distanceKm:d,durationMin:drive,energyKwh:energy,
    consumptionKwh100:c,startSoc:departure,arrivalSoc:arrival,
    reserve:min,hasSelectedStop:o.stopIncluded===true,candidate:null};
  var st=o.candidate,km=st&&value(st.routeProgressKm,0,d);
  if(!o.stopIncluded&&st&&km!==null&&departure!==null) {
    var socAt=departure-km*c/cap,remaining=d-km,
        max=value(o.maxCharge,min+1,100),temp=value(o.temperature,-35,55);
    if(max!==null&&temp!==null) {
      var target=Math.min(max,Math.max(socAt,(remaining*c/100+cap*min/100)/cap*100));
      var mins=socAt>=min&&target>socAt+1&&socAt>=0?
        chargingMinutes(socAt,target,cap,temp):null;
      var projected=mins===null?null:target-remaining*c/cap;
      out.candidate={km:km,arrivalSoc:socAt,targetSoc:target,
        chargeMin:mins,chargeKwh:mins===null?null:(target-socAt)*cap/100,
        destinationSoc:projected,
        feasible:mins!==null&&projected>=min-0.05};
    }
  }
  return out;
}
var api={consumption:consumption,chargingMinutes:chargingMinutes,forecast:forecast};
if(typeof module==='object'&&module.exports)module.exports=api;
if(!root.document)return;
var doc=root.document,route=null,candidate=null,corridorCandidates=[],scenario=null,measured=null,
    socAt=0,weather=null,weatherAt=0,packetTime=0,stationUpdated='',isOpen=false;
var TTL=10000,WEATHER_TTL=3*3600000;
function el(id){return doc.getElementById(id)}
function set(id,v){var node=el(id);if(node)node.textContent=String(v)}
function fmt(v,digits){return Number(v).toLocaleString('tr-TR',{minimumFractionDigits:digits||0,maximumFractionDigits:digits||0})}
function pct(v){return v===null||!Number.isFinite(v)?'—':('%'+fmt(v,0))}
function clock(min){return new Date(Date.now()+min*60000).toLocaleTimeString('tr-TR',{hour:'2-digit',minute:'2-digit'})}
function safe(name,handler) {
  var previous=root[name];if(typeof previous!=='function')return;
  root[name]=function() {
    var result;
    try{result=previous.apply(this,arguments)}
    finally{try{handler(arguments[0])}catch(e){if(root.console)root.console.warn('Trip planner',e)}}
    return result;
  };
}
function node(tag,cls,txt) {
  var n=doc.createElement(tag);
  if(cls)n.className=cls;
  if(txt!==undefined)n.textContent=String(txt);
  return n;
}
function makeInput(id,type,min,max,step,val,label) {
  return '<label class="eya-trip-field"><span>'+label+'</span><input id="'+id+
    '" type="'+type+'" min="'+min+'" max="'+max+'" step="'+step+'" value="'+val+'"></label>';
}
var panel=node('aside','eya-trip-panel');
panel.id='eya-trip-panel';panel.hidden=true;
panel.setAttribute('role','dialog');
panel.setAttribute('aria-label','EV Cockpit rota ve batarya planı');
panel.innerHTML='<div class="eya-trip-heading"><div><strong>Rota ve şarj planı</strong>'+
  '<small>Gerçek güzergâh · şeffaf enerji modeli</small></div>'+
  '<button id="eya-trip-close" type="button" aria-label="Rota planını kapat">✕</button></div>'+
  '<div id="eya-trip-status" class="eya-trip-status" role="status">Önce rota oluştur.</div>'+
  '<div class="eya-trip-source" id="eya-trip-source">OSRM: — · OBD: — · EPDK: —</div>'+
  '<div class="eya-trip-stats" aria-label="Rota özet bilgileri">'+
    '<div><small>Mesafe · rota</small><strong id="eya-trip-km">—</strong></div>'+
    '<div><small>Sürüş · rota</small><strong id="eya-trip-time">—</strong></div>'+
    '<div><small>Varış · tahmin</small><strong id="eya-trip-arrival-time">—</strong></div>'+
    '<div><small>Yol enerjisi · model</small><strong id="eya-trip-energy">—</strong></div>'+
    '<div><small>Varış SoC · model</small><strong id="eya-trip-arrival-soc">—</strong></div>'+
    '<div><small>Şarj maliyeti · senaryo</small><strong id="eya-trip-cost">—</strong></div>'+
  '</div>'+
  '<details class="eya-trip-settings"><summary>Model varsayımları ve şarj ayarları</summary>'+
  '<div class="eya-trip-fields">'+
    makeInput('eya-speed','number',30,180,5,110,'Hız · km/sa · varsayım')+
    makeInput('eya-temp','number',-35,55,1,15,'Dış sıcaklık · °C · varsayım')+
    makeInput('eya-cap','number',30,120,1,52,'Kullanılabilir kapasite · kWh · model')+
    makeInput('eya-reserve','number',5,40,1,10,'Min. varış · %')+
    makeInput('eya-maxcharge','number',50,100,5,80,'Maks. şarj hedefi · %')+
    makeInput('eya-price','number',0,100,0.1,'','₺/kWh · yalnızca manuel')+
  '</div><small>Model değerleri araç ölçümü değildir. Boş fiyat = bilinmiyor.</small></details>'+
  '<label class="eya-voice-volume">Sesli yönlendirme düzeyi'+
  '<input id="eya-voice-volume" type="range" min="0" max="100" step="5" value="85" aria-label="Sesli yönlendirme düzeyi"></label>'+
  '<h3>Yolculuk zaman çizelgesi</h3><div id="eya-trip-timeline" class="eya-trip-timeline"></div>'+
  '<h3>Batarya yüzdesi / gerçek yol mesafesi</h3>'+
  '<svg id="eya-trip-chart" viewBox="0 0 640 140" role="img" aria-label="Model batarya yüzdesi grafiği"></svg>'+
  '<p class="eya-trip-chart-note">Kesikli çizgi minimum rezervdir. Model grafiği GPS veya BMS ölçümü değildir.</p>'+
  '<div id="eya-trip-station" class="eya-trip-station"></div>'+
  '<button id="eya-trip-use-stop" type="button" hidden>⚡ Önerilen CCS üzerinden rota oluştur</button>'+
  '<p class="eya-trip-disclaimer">Şarj gücü %0–50 için 100 kW, %50–80 için 100→50 kW, %80–100 için 50→20 kW modelidir. Gerçek sıcaklık, batarya hazırlığı, trafik, rakım, rüzgâr, istasyon müsaitliği ve ücret fark yaratabilir. Sürüşte ekranla etkileşime girme.</p>';
doc.body.appendChild(panel);
var right=doc.querySelector('.cockpit-right .cockpit-panel');
if(right){
 var openBtn=node('button','eya-trip-open','▤ Rota ve şarj planı');
 openBtn.id='eya-trip-open';openBtn.type='button';
 right.appendChild(openBtn);
 openBtn.onclick=function(){panel.hidden=false;isOpen=true;render()};
 var vbtn=node('button','eya-voice-toggle','🔊 Sesi kapat');
 vbtn.id='eya-voice-toggle';vbtn.type='button';
 vbtn.onclick=function(){var legacy=el('tts');if(legacy)legacy.click()};
 right.appendChild(vbtn);
}
el('eya-trip-close').onclick=function(){isOpen=false;panel.hidden=true};
el('eya-voice-volume').addEventListener('change',function(){
 var bridge=root.MediaEngineNative;
 if(bridge&&typeof bridge.voiceVolume==='function')
   bridge.voiceVolume(Number(this.value)/100);
});
el('eya-trip-use-stop').onclick=function(){
 var real=el('station-suggestion-choose');
 if(real&&candidate&&real.onclick){panel.hidden=true;isOpen=false;real.click()}
};
Array.prototype.forEach.call(panel.querySelectorAll('input'),function(input){
 input.addEventListener('change',render);
});
function point(label,meta,soc,color) {
 var line=node('div','eya-trip-node');
 var icon=node('span','eya-trip-dot '+(color||''),'●');
 var copy=node('div','eya-trip-node-copy');
 copy.appendChild(node('strong','',label));
 copy.appendChild(node('small','',meta));
 line.appendChild(icon);line.appendChild(copy);
 line.appendChild(node('b','eya-trip-node-soc',soc));
 el('eya-trip-timeline').appendChild(line);
}
function chart(plan) {
 var svg=el('eya-trip-chart');while(svg.firstChild)svg.removeChild(svg.firstChild);
 if(!plan.valid||plan.startSoc===null||plan.hasSelectedStop)return;
 var ns='http://www.w3.org/2000/svg';
 function add(tag,attrs) {
   var item=doc.createElementNS(ns,tag);
   Object.keys(attrs).forEach(function(k){item.setAttribute(k,String(attrs[k]))});
   svg.appendChild(item);return item;
 }
 var y=function(p){return 118-Math.max(-10,Math.min(100,p))*0.95};
 var x=function(k){return 28+k/plan.distanceKm*590};
 [0,50,100].forEach(function(n){
   add('line',{x1:28,x2:618,y1:y(n),y2:y(n),stroke:'#31516a','stroke-width':1});
   add('text',{x:1,y:y(n)+4,fill:'#a5c9d8','font-size':12}).textContent=n;
 });
 add('line',{x1:28,x2:618,y1:y(plan.reserve),y2:y(plan.reserve),
   stroke:'#ffc05d','stroke-width':2,'stroke-dasharray':'5 5'});
 var final=plan.arrivalSoc;
 if(final===null)return;
 var d='M'+x(0)+' '+y(plan.startSoc);
 var charge=plan.candidate;
 if(charge&&charge.chargeMin!==null) {
   d+=' L'+x(charge.km)+' '+y(charge.arrivalSoc)+
      ' L'+x(charge.km)+' '+y(charge.targetSoc);
   final=charge.destinationSoc;
 }
 d+=' L'+x(plan.distanceKm)+' '+y(final);
 add('path',{d:d,fill:'none',stroke:'#43e7c0','stroke-width':4,'stroke-linecap':'round'});
 if(charge&&charge.chargeMin!==null){
   add('circle',{cx:x(charge.km),cy:y(charge.targetSoc),r:5,fill:'#54afff'});
 }
}
function render() {
 if(!panel)return;
 var now=Date.now(),verified=measured!==null&&socAt>0&&now-socAt>=0&&now-socAt<=TTL,
     source=verified?'OBD':'';
 var start=verified?measured:null;
 if(start===null&&scenario!==null){start=scenario;source='varsayım (%'+scenario+')'}
 var freshness=stationUpdated?'EPDK: '+stationUpdated:'EPDK: henüz yanıt yok';
 var useWeather=weather!==null&&now-weatherAt>=0&&now-weatherAt<WEATHER_TTL;
 var temp=useWeather?weather:value(el('eya-temp').value,-35,55);
 var capacity=value(el('eya-cap').value,30,120),
     speed=value(el('eya-speed').value,30,180),
     reserve=value(el('eya-reserve').value,5,40),
     maxCharge=value(el('eya-maxcharge').value,50,100),
     price=value(el('eya-price').value,0,100),
     c=consumption(speed,temp);
 set('eya-trip-source',(route?'OSRM: gerçek rota':'OSRM: —')+
   ' · SoC: '+(source||'—')+' · '+freshness+
   ' · Hava: '+(useWeather?'rota boyunca saatlik MODEL':'manuel varsayım'));
 if(!route){
   set('eya-trip-status','Önce gerçek rota oluştur. Örnek mesafe veya istasyon kullanılmaz.');
   ['eya-trip-km','eya-trip-time','eya-trip-arrival-time',
     'eya-trip-energy','eya-trip-arrival-soc','eya-trip-cost'].forEach(function(k){set(k,'—')});
   el('eya-trip-timeline').textContent='Rota bekleniyor';
   el('eya-trip-chart').textContent='';
   el('eya-trip-station').textContent='İstasyon verisi bekleniyor';
   el('eya-trip-use-stop').hidden=true;return;
 }
 var p=forecast({distanceKm:route.distanceKm,durationMin:route.durationMin,
   soc:start,capacity:capacity,consumptionKwh100:c,minArrival:reserve,
   maxCharge:maxCharge,temperature:temp,stopIncluded:route.stopIncluded,
   candidate:candidate});
 if(!p.valid){
   set('eya-trip-status','Enerji hesabı için model ayarlarını kontrol et.');
   return;
 }
 set('eya-trip-status','Yol ağı ve sürüş süresi: rota servisi. Enerji, SoC ve şarj süresi: model.');
 set('eya-trip-km',fmt(p.distanceKm,1)+' km');
 set('eya-trip-time',Math.round(p.durationMin)+' dk');
 set('eya-trip-arrival-time',route.stopIncluded?'— · şarj süresi bilinmiyor':clock(p.durationMin));
 set('eya-trip-energy',fmt(p.energyKwh,1)+' kWh');
 set('eya-trip-arrival-soc',p.hasSelectedStop?'— · alınacak enerji bilinmiyor':
   (p.arrivalSoc===null?'— · OBD veya senaryo gerekli':
      pct(p.arrivalSoc)+(p.arrivalSoc<reserve?' · şarj gerekebilir':'')));
 var charged=root.EyaChargingModel&&root.EyaChargingModel.plan({
   distanceKm:route.distanceKm,capacityKwh:capacity,speedKmh:speed,tempC:temp,
   startSoc:start,minArrivalPct:reserve,maxChargePct:maxCharge,
   priceTlKwh:price,stations:corridorCandidates});
 if(charged&&charged.valid&&!route.stopIncluded){
   if(charged.complete&&charged.arrivalSoc!==null){
     set('eya-trip-arrival-soc',pct(charged.arrivalSoc)+' · model');
     var mins=charged.stops.reduce(function(a,s){return a+s.chargeMin},0);
     set('eya-trip-arrival-time',clock(p.durationMin+mins)+' · model');
   }else if(charged.status){
     set('eya-trip-arrival-soc','— · '+charged.status);
   }
   if(charged.stops.length){
     var money=charged.stops.reduce(function(a,s){return a+(s.costTl||0)},0);
     set('eya-trip-cost',price===null?'— · gerçek tarife yok':
       fmt(money,0)+' ₺ · manuel fiyat');
   }
 }
 try{
   if(root.EyaEnergyRoute && route.coordinates)
     (route.stopIncluded?root.EyaEnergyRoute.clear():
       root.EyaEnergyRoute.draw(route.coordinates,{distanceKm:route.distanceKm,
       startSoc:start,kwh100:c,capacityKwh:capacity}));
 }catch(_){}
 var s=p.candidate;
 if(!(charged&&charged.stops&&charged.stops.length))set('eya-trip-cost',s&&s.chargeMin!==null&&price!==null?
   fmt(s.chargeKwh*price,0)+' ₺ · manuel fiyat':'— · gerçek tarife yok');
 var timeline=el('eya-trip-timeline');timeline.textContent='';
 point('Kalkış',clock(0)+' · %'+(source||'SoC bilinmiyor'),pct(p.startSoc),'origin');
 if(charged&&charged.stops&&charged.stops.length&&!route.stopIncluded){
   charged.stops.forEach(function(stop){
    point('⚡ '+stop.title,fmt(stop.routeProgressKm,0)+'. km · '+
     fmt(stop.chargeMin,0)+' dk · '+pct(stop.arrivalSoc)+' → '+pct(stop.departureSoc)+
     ' · EPDK '+(stop.snapshotAt||'tarih yok'),pct(stop.arrivalSoc),'charge');
   });
 } else if(s&&candidate){
   point('EPDK CCS adayı · '+String(candidate.title||'Şarj istasyonu').slice(0,70),
     fmt(s.km,0)+'. km · '+(s.chargeMin===null?
       'Erişim/enerji uygunluğu doğrulanamadı':
       'Model '+fmt(s.chargeMin,0)+' dk · hedef '+pct(s.targetSoc)),
     pct(s.arrivalSoc),'charge');
 }
 point('Varış',fmt(p.distanceKm,1)+' km · '+(route.stopIncluded?
   'seçili şarj mediaenginesı süresi belirsiz':('sürüş ~'+clock(p.durationMin))),
   p.hasSelectedStop?'—':(charged&&charged.complete?pct(charged.arrivalSoc):pct(p.arrivalSoc)),'destination');
 var station=el('eya-trip-station');
 if(route.stopIncluded){
   station.textContent='Rota üzerinde seçili şarj mediaenginesı var. Kaç kWh alınacağı doğrulanmadığından varış SoC ve toplam süre hesaplanmadı.';
 } else if(!candidate){
   station.textContent='Doğrulanmış rota koridoru CCS önerisi henüz yok. Örnek durak eklenmez.';
 } else if(!s||s.chargeMin===null){
   station.textContent='EPDK CCS adayı bulundu fakat mevcut SoC ve modelle erişilebilir şarj hesabı doğrulanamadı.';
 } else {
   station.textContent='Adayı seçersen: '+fmt(s.chargeKwh,1)+' kWh · ~'+fmt(s.chargeMin,0)+
     ' dk model şarj · varış '+pct(s.destinationSoc)+
     (s.feasible?' · min. rezerve uygun':' · min. rezerv sağlanmıyor')+
     '. İstasyon müsaitliği, tarife ve sapma süresi bilinmiyor.';
 }
 el('eya-trip-use-stop').hidden=!(candidate&&s&&s.feasible&&!route.stopIncluded);
 chart(p);
}
safe('mediaengineRoute',function(p) {
 if(!p||!Array.isArray(p.coordinates)||p.coordinates.length<2||
    value(p.distanceKm,.05,5000)===null||value(p.durationMin,.01,10000)===null) {
   route=null;candidate=null;render();return;
 }
 route={coordinates:p.coordinates,distanceKm:Number(p.distanceKm),durationMin:Number(p.durationMin),
    stopIncluded:p.stopIncluded===true,routeGeneration:p.routeGeneration,
    trafficAvailable:p.trafficAvailable===true};
 candidate=null;corridorCandidates=[];stationUpdated='';packetTime=0;
 weather=null;weatherAt=0;if(root.EyaEnergyRoute)root.EyaEnergyRoute.clear();render();
});
safe('mediaengineStations',function(p) {
 if(!route||!p||p.kind!=='route'||!Array.isArray(p.items))return;
 if(Number.isInteger(route.routeGeneration)&&Number.isInteger(p.routeGeneration)&&
    route.routeGeneration!==p.routeGeneration)return;
 stationUpdated=String(p.updatedAt||'tarih verilmedi').slice(0,64);
 corridorCandidates=root.EyaCorridorStations?root.EyaCorridorStations.select(p,route.distanceKm):[];
 packetTime=Date.now();
 var s=p.suggested,km=s&&value(s.routeProgressKm,0,route.distanceKm);
 candidate=p.chargeModel==='charge-likely'&&p.stopIncluded!==true&&
    !route.stopIncluded&&s&&km!==null&&value(s.lat,35,43)!==null&&
    value(s.lon,25,46)!==null?Object.assign({},s):null;
 render();
});
safe('mediaengineTelemetry',function(d) {
 var now=Date.now();
 measured=null;socAt=0;
 if(d&&d.fresh===true&&(Number.isFinite(d.measuredAtMs)&&
      now-d.measuredAtMs>=0&&now-d.measuredAtMs<=TTL)) {
   measured=value(String(d.soc).replace(',','.').trim().replace(/\s*%$/,''),0,100);
   if(measured!==null)socAt=now;
 }
 render();
});
safe('mediaengineSocScenario',function(p){
 if(!p)return;
 scenario=p.measured===false&&value(p.percent,0,100)!==null?
   value(p.percent,0,100):null;
 render();
});
safe('mediaengineJourneyWeather',function(packet){
 var now=Date.now();
 if(!route||!packet||packet.source!=='Open-Meteo'||!Array.isArray(packet.cards)||
    packet.cards.length<5||!Number.isFinite(packet.updatedAt)||
    now-packet.updatedAt<0||now-packet.updatedAt>WEATHER_TTL)return;
 if(Number.isInteger(route.routeGeneration)&&packet.routeGeneration!==route.routeGeneration)return;
 var found=packet.cards.filter(function(card){
   return card&&card.available===true&&value(card.temperature,-35,55)!==null&&
     Number.isFinite(card.forecastTime)&&Number.isFinite(card.etaMinutes)&&
     Math.abs(card.forecastTime*1000-(now+card.etaMinutes*60000))<5400000;
 });
 if(found.length<5)return;
 weather=found.reduce(function(total,card){return total+card.temperature},0)/found.length;
 weatherAt=packet.updatedAt;render();
});
safe('mediaengineWeather',function(w){
 var now=Date.now();
 if(w&&w.modelBased===true&&value(w.temperature,-35,55)!==null&&
    value(w.receivedAt,0,Number.MAX_SAFE_INTEGER)!==null&&
    now-w.receivedAt>=0&&now-w.receivedAt<WEATHER_TTL) {
   weather=Number(w.temperature);weatherAt=w.receivedAt;
 }else if(weatherAt===0||Date.now()-weatherAt>WEATHER_TTL){weather=null;weatherAt=0}
 render();
});
safe('mediaengineStatus',function(s) {
 if(s&&/durduruldu|iptal edildi/i.test(String(s.detail||''))){
   route=null;candidate=null;corridorCandidates=[];stationUpdated='';
   if(root.EyaEnergyRoute)root.EyaEnergyRoute.clear();render();
 }
});
render();
root.EyaTripPlanner=api;
})(typeof window==='object'?window:{});
