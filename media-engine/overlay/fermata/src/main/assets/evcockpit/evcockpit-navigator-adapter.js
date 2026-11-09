/*
 * MediaEngine'nın mevcut navigator.html dosyasına EK olarak yüklenir.
 * Bu dosya MediaEngine haritasını, rota alternatiflerini ve mevcut callback'leri bozmaz.
 * Önkoşul: navigator.html'de map=L.map(...) satırından hemen sonra
 *           window.evcockpitLiveLeafletMap = map;
 * eklenmiş olmalı ve EVCOCKPIT_LIVE_ATTACH_ONLY = true ayarlanmalı.
 */
(function (w, d) {
  'use strict';
  var mod = w && w.EVCOCKPITLiveMap;
  try {
    if (!mod || !w.evcockpitLiveLeafletMap ||
        !mod.attachExistingMap(w.evcockpitLiveLeafletMap)) {
      console.warn('EVCOCKPITLiveMap: mevcut MediaEngine haritasına erişilemiyor');
      return;
    }
  } catch (error) {
    // A partially initialized map must not take down MediaEngine's TV or navigator.
    console.warn('EVCOCKPITLiveMap attach failed; leaving MediaEngine untouched', error);
    return;
  }

  // Üretimdeki arayüzün sağ tarafına küçük bir canlı bilgi kartı.
  var target = null;
  try { target = d.querySelector('.cockpit-right'); }
  catch (e) { console.warn('EVCOCKPIT cockpit selector unavailable', e); }
  try {
  if (target && !d.getElementById('evcockpit-live-addon')) {
    var panel = d.createElement('div');
    panel.id = 'evcockpit-live-addon'; panel.className = 'cockpit-panel';
    // Sabit metin: dış kaynaklı veri innerHTML ile yazılmıyor.
    panel.innerHTML = '<div class="evcockpit-addon-head">CANLI YOL VERİSİ</div>' +
      '<div class="evcockpit-addon-row">GPS hız: <b id="evcockpit-speed">—</b> km/sa · Batarya: <b id="evcockpit-soc">—</b></div>' +
      '<div id="evcockpit-soc-track"><div id="evcockpit-soc-bar"></div></div>' +
      '<div class="evcockpit-addon-row">Hava: <span id="evcockpit-weather">—</span></div>' +
      '<div class="evcockpit-addon-row">Enerji: <span id="evcockpit-range">—</span></div>' +
      '<div class="evcockpit-addon-row">İstasyon: <span id="evcockpit-station-count">—</span> · İhbar: <span id="evcockpit-incident-count">—</span></div>' +
      '<div class="evcockpit-addon-row" id="evcockpit-nearest-dc">—</div>' +
      '<div id="evcockpit-status">Yerel ihbar katmanı hazır.</div>';
    target.appendChild(panel);
  }
  } catch (e) { console.warn('EVCOCKPIT HUD panel unavailable', e); }
  var speed = null, soc = null, speedAt = 0, socAt = 0;
  function syncCar() {
    var now = Date.now();
    // Reject a time jump backwards; never revive stale measurements.
    var freshSpeed = speedAt > 0 && now >= speedAt && now - speedAt <= 30000;
    var freshSoc = socAt > 0 && now >= socAt && now - socAt <= 30000;
    mod.updateCarData(freshSpeed ? speed : null, freshSoc ? soc : null);
  }
  function wrap(name, after) {
    var original = w[name];
    if (typeof original !== 'function') return;
    w[name] = function (packet) {
      var result;
      try {
        // Native MediaEngine callback owns the UI, routes and media modules.
        result = original.apply(this, arguments);
      } catch (e) {
        console.warn('MediaEngine native callback failed: ' + name, e);
        try {
          var label = d.getElementById('evcockpit-status');
          if (label) label.textContent = 'MediaEngine verisi işlenemedi: ' + name;
        } catch (_) { /* DOM may be unavailable. */ }
      }
      try { after(packet); } catch (e) { console.warn('EVCOCKPITLive addon ' + name, e); }
      return result;
    };
  }

  wrap('mediaengineRoute', function (p) {
    if (!p || !Array.isArray(p.coordinates)) return;
    // MediaEngine native rota çizimi aynen kalır; çift çizgi/çift fitBounds oluşmaz.
    mod.ingestNativeRoute({ coordinates: p.coordinates,
      distanceKm: p.distanceKm, durationMin: p.durationMin,
      stopIncluded: p.stopIncluded === true });
  });
  // A native route packet is only a subset of EPDK; never pass it to setStations().
  wrap('mediaengineStations', function (p) {
    if (p && p.kind === 'route') mod.setRouteStations(p);
  });
  wrap('mediaenginePosition', function (p) {
    // Yerini bilinmeyen veya eski GPS'i kullanma.
    if (!p || !Number.isFinite(p.lat) || !Number.isFinite(p.lon) ||
        !Number.isFinite(p.timeMs) || !Number.isFinite(p.accuracy) ||
        p.accuracy > 60 || Date.now() - p.timeMs < 0 || Date.now() - p.timeMs > 30000) return;
    mod.updateLocation(p.lat, p.lon);
    if (Number.isFinite(p.speedKmh) && p.speedKmh >= 0 && p.speedKmh <= 300) {
      speed = p.speedKmh; speedAt = Date.now();
    }
    syncCar();
  });
  wrap('mediaengineTelemetry', function (p) {
    if (p && p.fresh === true && p.soc != null) {
      var value = Number.parseFloat(String(p.soc).replace(',', '.'));
      if (Number.isFinite(value) && value >= 0 && value <= 100) {
        soc = value; socAt = Date.now();
      } else { soc = null; socAt = 0; }
    } else { soc = null; socAt = 0; }
    syncCar();
  });
  wrap('mediaengineStatus', function (p) {
    if (p && /durduruldu|iptal edildi/i.test(String(p.detail || '')))
      mod.clearRoute();
  });

  // Native can call this from evaluateJavascript using a safely quoted JSON
  // string. Do not overwrite the actual Java @JavascriptInterface
  // window.AndroidBridge. This is an optional JS endpoint.
  function normalizePacket(raw) {
    if (typeof raw === 'string') {
      if (raw.length > 2 * 1024 * 1024) return null;
      try { raw = JSON.parse(raw); } catch (_) { return null; }
    }
    return raw && typeof raw === 'object' && !Array.isArray(raw) ?
      raw : null;
  }
  var commands = Object.freeze({
    route:'mediaengineRoute', stations:'mediaengineStations',
    gps:'mediaenginePosition', telemetry:'mediaengineTelemetry', status:'mediaengineStatus'
  });
  w.EVCOCKPITNativeBridge = Object.freeze({
    receive: function (type, raw) {
      try {
        if (typeof type !== 'string' ||
            !Object.prototype.hasOwnProperty.call(commands, type)) return false;
        var packet = normalizePacket(raw);
        if (!packet) return false;
        // Legacy caller spelling is intentionally preserved for the cockpit
        // contract. It does NOT enable or bypass Android Auto's parked-only
        // video restrictions. It only opens this APK's original media UI.
        if (type === 'status' &&
            packet.command === 'LAUNCH_MEDIAENGINE_32_NO_PARK') {
          if (Object.keys(packet).length !== 1) return false;
          if (!w.MediaEngineNative || typeof w.MediaEngineNative.openMedia !== 'function')
            return false;
          w.MediaEngineNative.openMedia(); // Android @JavascriptInterface, allowlisted.
          return true;
        }
        // Reject command-like packets that are not an explicit allowlisted
        // media handoff: arbitrary Java bridge method calls are forbidden.
        if (Object.prototype.hasOwnProperty.call(packet, 'command'))
          return false;
        var callback = w[commands[type]];
        if (typeof callback !== 'function') return false;
        callback(packet); // Original MediaEngine first, addon second.
        return true;
      } catch (error) {
        console.warn('EVCOCKPIT native packet refused:', error); return false;
      }
    },
    snapshot: function () {
      try { return mod.getState(); }
      catch (_) { return {soc:null,position:null,countryStationsVerified:false}; }
    },
    epdkCountry: function (raw) {
      function reject() {
        // Fail closed: do NOT keep highlighting a previous national DC set
        // after the latest full-country transfer is malformed/rejected.
        try { mod.setStations(null, null); } catch (_) { /* Already safe. */ }
        return false;
      }
      try {
        // Must be a verified COUNTRY batch; a MediaEngine route subset is NEVER
        // promoted into the national DC index. No assumed status or kW.
        var packet = normalizePacket(raw);
        if (!packet || !Array.isArray(packet.stations) || !packet.metadata ||
            packet.metadata.coverage !== 'country') return reject();
        var verdict = mod.validateEPDKDataContract(packet);
        if (!verdict || !verdict.ok) return reject();
        if (mod.setStations(packet.stations, packet.metadata) <= 0)
          return reject();
        return true;
      } catch (error) {
        console.warn('EVCOCKPIT EPDK batch refused:', error);
        return reject();
      }
    }
  });
})(window, document);
