/* EV Cockpit medya kataloğu.
 * Native MediaEngine owns playback/effects. We do not embed DRM players,
 * replace Android Auto media services, or spoof parked state. */
(function (root, doc) {
 'use strict';
 var cards = [["tv","📺 Canlı TV","İnternet televizyonu","tv"],["youtube","▶ Video","Video oynatıcı","youtube"],["mirror","▣ Ekran Yansıtma","Telefon ekranı","mirror"],["netflix","Film ve Dizi 1","Uygulama içinde","netflix"],["disney","Film ve Dizi 2","Uygulama içinde","disney"],["prime","Film ve Dizi 3","Uygulama içinde","prime"],["media","▣ Yerel Medya","Video ve müzik dosyaları","media"],["radio","◉ Radyo","İnternet radyoları","radio"],["podcast","♫ Podcastler","Sesli programlar","podcast"],["web","◎ Web","Tarayıcı","web"],["windasist","Yol Araçları","Ek araç özellikleri","windasist"],["mediaengine","EV Cockpit","Ana ekran","mediaengine"],["shortcuts","＋ Kısayollar","Kısayolları düzenle","shortcuts"],["settings","⚙ Ayarlar","Uygulama ayarları","settings"],["equalizer","▥ Ses","Ses ve ekolayzır","equalizer"]];
 var overlay = null, opened = false;
 function node(tag, cls, txt) {
  var el = doc.createElement(tag);
  if (cls) el.className = cls;
  if (txt) el.textContent = txt;
  return el;
 }
 function setOpen(flag) {
  if (!overlay) return false;
  opened = !!flag;
  overlay.hidden = !opened;
  overlay.setAttribute('aria-hidden', opened ? 'false' : 'true');
  return true;
 }
 function launch(id) {
  setOpen(false);
  try {
   var bridge = root.MediaEngineNative;
   if (bridge && typeof bridge.openMediaEngineCard === 'function') {
    bridge.openMediaEngineCard(id);
    return true;
   }
   // Legacy preview may only have the original MediaEngine dashboard.
   if (bridge && typeof bridge.openMedia === 'function' &&
       id !== 'netflix' && id !== 'disney' && id !== 'prime') {
    bridge.openMedia();
    return true;
   }
  } catch (e) {
   try { root.console.warn('EVCOCKPIT media handoff', e); } catch (_) {}
  }
  if (typeof root.mediaengineStatus === 'function')
   root.mediaengineStatus({status:'media',
    detail:'Bu kısayol için yeni EV Cockpit Android uygulama sürümü gerekli.'});
  return false;
 }
 function openOriginalMedia() {
  setOpen(false);
  try {
   var bridge = root.MediaEngineNative;
   if (bridge && typeof bridge.openOriginalMedia === 'function') {
    bridge.openOriginalMedia();
    return true;
   }
  } catch(e) {
   try{root.console.warn('original media handoff unavailable',e)}catch(_){}
  }
  if(typeof root.mediaengineStatus==='function')
   root.mediaengineStatus({status:'media',detail:
     'Orijinal uygulama geçişi için güncel EV Cockpit APK sürümü gerekli.'});
  return false;
 }
 function install() {
  if (overlay || !doc.body) return !!overlay;
  overlay = node('section', 'evcockpit-mediaengine-drawer');
  overlay.id = 'evcockpit-mediaengine-drawer';
  overlay.hidden = true;
  overlay.setAttribute('role', 'dialog');
  overlay.setAttribute('aria-label', 'EV Cockpit medya uygulamaları');
  overlay.setAttribute('aria-modal', 'true');
  overlay.setAttribute('aria-hidden', 'true');
  var panel = node('div', 'evcockpit-mediaengine-panel');
  var head = node('div', 'evcockpit-mediaengine-head');
  var title = node('div', 'evcockpit-mediaengine-title');
  title.appendChild(node('strong', '', 'EV Cockpit · Medya'));
  title.appendChild(node('small', '', 'EV Cockpit · mevcut oynatıcılar korunur'));
  var close = node('button', 'evcockpit-mediaengine-close', '✕ Kapat');
  close.id = 'evcockpit-mediaengine-close';
  close.type = 'button';close.onclick = function(){setOpen(false)};
  head.appendChild(title);head.appendChild(close);
  var grid = node('div', 'evcockpit-mediaengine-grid');
  cards.forEach(function (c) {
   var button = node('button', 'evcockpit-mediaengine-card evcockpit-mediaengine-' + c[3]);
   button.type = 'button'; button.id = 'evcockpit-mediaengine-' + c[0];
   button.appendChild(node('strong', '', c[1]));
   button.appendChild(node('small', '', c[2]));
   button.onclick = function () { launch(c[0]) };
   grid.appendChild(button);
  });
  var originalButton=node('button','evcockpit-mediaengine-external-original');
  originalButton.id='evcockpit-mediaengine-external-original';
  originalButton.type='button';
  originalButton.setAttribute('aria-label','Yüklü, değiştirilmemiş orijinal uygulamayı aç');
  originalButton.appendChild(node('strong','','◉ Orijinal medya uygulamasını aç'));
  originalButton.appendChild(node('small','',
   'MediaEngine 4.1.10 / önceki sürüm · orijinal uygulamayı ayrıca aç'));
  originalButton.onclick=openOriginalMedia;
  panel.appendChild(head);panel.appendChild(originalButton);panel.appendChild(grid);
  panel.appendChild(node('p', 'evcockpit-mediaengine-disclaimer',
   'Film ve dizi servisleri resmî uygulamada veya tarayıcıda açılır. ' +
   'Canlı TV, video, radyo, ses ayarları ve diğer özellikler medya bölümüne geçer. ' +
   'DRM ve araç hareketi kısıtları değiştirilmez.'));
  overlay.appendChild(panel);
  overlay.addEventListener('click',function(e) {
   if (e && e.target === overlay) setOpen(false);
  });
  doc.body.appendChild(overlay);
  ['dock-apps','dock-music'].forEach(function(id) {
   var b = doc.getElementById(id);
   if (b) b.onclick = function(){setOpen(true)};
  });
  doc.addEventListener('keydown',function(e){
   if(opened && e && (e.key==='Escape'||e.key==='Esc'))setOpen(false);
  });
  return true;
 }
 root.EVCOCKPITMediaEngineMediaHub = {start:install,open:function(){return install()&&setOpen(true)},
  close:function(){return setOpen(false)},
  isOpen:function(){return opened},
  openOriginalMedia:openOriginalMedia,
  items:function(){return cards.map(function(c){return c[0]})}};
 if (doc.readyState === 'loading') doc.addEventListener('DOMContentLoaded',install);
 else install();
})(window, document);
