/*
 * EV Cockpit — Leaflet canlı veri mimarisi
 * Gerçek veriler: OSRM, Open-Meteo, Android Native ve verilen istasyon JSON'u.
 * Sahte SoC, hız, konum veya şarj istasyonu ÜRETMEZ.
 * Uyumlu global fonksiyonlar: evcockpitShowRoute, evcockpitShowStations,
 * evcockpitGetRouteEndpoints, evcockpitMapBounds, evcockpitFocus, evcockpitIsReady.
 *
 * Yükleme: leaflet.js dosyasından SONRA ve DOM hazırken.
 */
(function (window, document) {
  'use strict';

  // An optional *user-supplied* key for standalone previews. A placeholder
  // never triggers paid requests. In the Android APK, the existing per-user
  // native Settings / EVCOCKPIT_TRAFFIC_CONFIG takes precedence.
  // Browser tile URLs expose API keys: restrict traffic API usage and quota.
  const TOMTOM_API_KEY = "YOUR_API_KEY";
  const FLOW_URL =
    'https://api.tomtom.com/maps/orbis/traffic/flow/raster/tile/' +
    '{z}/{x}/{y}?apiVersion=2&style=dark&tileSize=256&key=';

  function isTomTomKey(key) {
    return typeof key === 'string' && /^[A-Za-z0-9_-]{15,200}$/.test(key) &&
      key !== 'YOUR_API_KEY' && key !== 'YOUR_TOMTOM_API_KEY';
  }

  // Exactly one shared Leaflet map. The traffic provider owns the lifecycle
  // and never creates an extra map or a second flow tile subscription.
  // MapLibre renders in Leaflet's overlayPane (z≈400). Raster flow must use
  // a transparent higher pane (z=425), still below regular markers (z=600).
  function createTomTomFlowTileLayer(key, paneName) {
    try {
      if (typeof L === 'undefined' || !L.tileLayer || !isTomTomKey(key))
        return null;
      return L.tileLayer(FLOW_URL + encodeURIComponent(key), {
        pane: paneName || 'evcockpit-live-traffic',
        tileSize: 256,
        opacity: 0.92,
        maxZoom: 19,
        noWrap: true,
        updateWhenIdle: true,  // Avoid re-fetches during pan gestures.
        keepBuffer: 2,         // Bounded tile cache; less churn on POCO F5.
        attribution: '© TomTom'
      });
    } catch (error) {
      // Missing Leaflet, unsupported WebView or invalid key may NOT crash map.
      try { console.warn('Trafik katmanı kullanılamıyor', error); } catch (_) {}
      return null;
    }
  }

  function connectTrafficProvider() {
    try {
      var p = window.EvCockpitTrafficProviders;
      if (!p || !map || typeof p.attachLeaflet !== 'function') return;
      p.attachLeaflet(map);
      if (typeof p.configured === 'function' &&
          typeof p.setTomTomKey === 'function' && !p.configured().tomtom) {
        var key = window.EVCOCKPIT_TRAFFIC_CONFIG && window.EVCOCKPIT_TRAFFIC_CONFIG.tomtomKey;
        if (!isTomTomKey(key)) key = TOMTOM_API_KEY;
        if (isTomTomKey(key)) p.setTomTomKey(key);
      }
    } catch (error) {
      try { console.warn('Trafik bağlantısı kurulamadı', error); } catch (_) {}
    }
  }

  function toggleTraffic(enabled) {
    try {
      var p = window.EvCockpitTrafficProviders;
      if (!map || !p || typeof p.activate !== 'function') return Promise.resolve(false);
      var turnOn = typeof enabled === 'boolean' ? enabled : p.getActive() !== 'tomtom';
      if (!turnOn) return Promise.resolve(p.activate('osm')).catch(function(){return false});
      if (!p.configured || !p.configured().tomtom) {
        status('TomTom API anahtarı gerekli');
        return Promise.resolve(false);
      }
      var center = map.getCenter && map.getCenter();
      var position = center && Number.isFinite(center.lat) && Number.isFinite(center.lng)
        ? [center.lat, center.lng] : undefined;
      var zoom = map.getZoom && map.getZoom();
      return Promise.resolve(p.activate('tomtom', position, zoom))
        .catch(function(){status('Canlı trafik bağlantısı kurulamadı');return false});
    } catch (error) {
      status('Trafik katmanı kullanılamıyor');
      return Promise.resolve(false);
    }
  }

  var CFG = Object.freeze({
    osrmUrl: 'https://router.project-osrm.org', // Demo servisi: üretimde özel OSRM kullanın.
    weatherUrl: 'https://api.open-meteo.com/v1/forecast',
    corridorKm: 5,
    weatherRefreshMs: 10 * 60 * 1000,
    telemetryStaleMs: 30 * 1000,
    localIncidentKey: 'evcockpit-incidents-v1',
    localIncidentTtlMs: 24 * 60 * 60 * 1000, // Personal traffic notes are never live/verified.
    nearestRefreshMs: 15 * 1000
  });

  var map = null, attachedToExisting = false;
  var markers = { origin: null, destination: null, nearestDC: null, current: null };
  var layers = { route: null, stations: null, incidents: null };
  var origin = null, destination = null;
  var route = null, weather = null;
  var stations = [], visibleStations = [];
  var countrySnapshot = { complete: false, verifiedAt: 0 };
  // Whole-country data is explicitly certified by the native importer; an array
  // (or 5 km route packet) on its own never proves countrywide coverage.
  // A native route packet is partial; never treat it as a full-country DC index.
  var routeStationSnapshot = null;
  var incidents = [];
  var car = { speed: null, soc: null, receivedAt: 0 };
  var currentLocation = null, locationAt = 0;
  // Bu değerler araçtan teyit edilince setEnergyProfile(...) ile verilmelidir.
  var energyProfile = { usableBatteryKWh: null, consumptionKWhPer100Km: null };
  var routeEpoch = 0, routeAbort = null, weatherAbort = null;
  var weatherTimer = null, healthTimer = null, lastNearestAt = 0;
  var onIncidentToDB = null;

  var $ = function (id) { return document.getElementById(id); };
  function finiteNumber(v) {
    // null, undefined, boş string ve NaN'ı gerçek ölçüm sayma.
    if (v === null || v === undefined || v === '' || typeof v === 'boolean') return null;
    var n = Number(v);
    return Number.isFinite(n) ? n : null;
  }
  function validLL(lat, lon) {
    return Number.isFinite(lat) && Number.isFinite(lon) &&
      lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
  }
  function normalizeLL(p) {
    if (!Array.isArray(p) || p.length < 2) return null;
    var lat = finiteNumber(p[0]), lng = finiteNumber(p[1]);
    return validLL(lat, lng) ? [lat, lng] : null;
  }
  // DOM is optional in standalone mode: a missing cockpit node must not abort
  // a native Android callback or block navigation.
  function setText(id, value) {
    try {
      var node = $(id);
      if (node) node.textContent = value == null ? '—' : String(value);
    } catch (error) { console.warn('EVCOCKPIT DOM update:', id, error); }
  }
  function status(msg) { setText('evcockpit-status', msg); }
  function icon(content, css) {
    return L.divIcon({ className: '', html: '<span class="evcockpit-pin ' + css + '">' + content + '</span>', iconSize: [32,
32], iconAnchor: [16, 16] });
  }
  function kilometers(a, b) { return L.latLng(a).distanceTo(L.latLng(b)) / 1000; }
  function validLocalIncident(s) {
    if (!s || !validLL(s.lat, s.lng) || !['accident', 'closed'].includes(s.type)) return false;
    var at = Date.parse(s.timestamp), now = Date.now();
    // Do not revive future-dated entries after a clock change, or show an
    // old personal report as if it were verified real-time road intelligence.
    return Number.isFinite(at) && at <= now && now - at <= CFG.localIncidentTtlMs;
  }
  function normalizeLocalIncident(s) {
    if (!validLocalIncident(s)) return null;
    var id = String(s.id || [s.lat, s.lng, s.type, s.timestamp].join(':'));
    if (!id || id.length > 160) return null;
    return {id: id, lat: Number(s.lat), lng: Number(s.lng),
      type: s.type, timestamp: new Date(Date.parse(s.timestamp)).toISOString()};
  }
  function safeStorageLoad() {
    try {
      var rows = JSON.parse(window.localStorage.getItem(CFG.localIncidentKey) || '[]');
      return Array.isArray(rows) ? rows.map(normalizeLocalIncident).filter(Boolean).slice(-250) : [];
    } catch (_) { return []; } // WebView DOM storage may be disabled.
  }
  function safeStorageSave() {
    try { window.localStorage.setItem(CFG.localIncidentKey, JSON.stringify(incidents.slice(-250))); }
    catch (_) { status('İhbar kaydedildi; kalıcı yerel depolama kullanılamıyor.'); }
  }

  // Noktaların indeks ortası değil, rotanın METRE CİNSİNDEN yol ortası.





function distanceMidpoint(line) {
  if (!line || line.length === 0) return null;
  if (line.length === 1) return line[0];
  var distances = [], total = 0;
  for (var i = 1; i < line.length; i++) {
    var d = kilometers(line[i - 1], line[i]);
    distances.push(d); total += d;
  }
  var half = total / 2, sum = 0;
  for (var j = 1; j < line.length; j++) {
    var segment = distances[j - 1];
    if (sum + segment >= half) {
      var t = segment ? (half - sum) / segment : 0;
      return [line[j - 1][0] + (line[j][0] - line[j - 1][0]) * t,
              line[j - 1][1] + (line[j][1] - line[j - 1][1]) * t];
    }
    sum += segment;
  }
  return line[line.length - 1];
}

// Gerçek GPS rotaya yakınsa (<=500 m), kalan mesafeyi rota geometrisinden bul.
// 1800 örnek civarında sınır: konum güncellemelerinde bütün yüksek çözünürlüklü
// OSRM geometrisini tekrar tekrar gezmekten kaçınır.
function progressNodes(line) {
  var nodes = [], step = Math.max(1, Math.ceil(line.length / 1800));
  for (var i = 0; i < line.length; i += step) nodes.push(line[i]);
  if (nodes[nodes.length - 1] !== line[line.length - 1]) nodes.push(line[line.length - 1]);
  var cum = [0];
  for (var j = 1; j < nodes.length; j++)
    cum.push(cum[j - 1] + kilometers(nodes[j - 1], nodes[j]));
  return { nodes: nodes, cum: cum, total: cum[cum.length - 1] };
}
function remainingRouteDistance() {
  if (!route) return null;
  // GPS yoksa yalnız başlangıçtan tahmini mesafe kullanılabilir.
  if (!currentLocation || Date.now() < locationAt ||
      Date.now() - locationAt > 30000)
    return { km: route.distanceKm, note: 'başlangıç esaslı' };
  var p = currentLocation, path = route.progress;
  var nearest = Infinity, along = 0;
  for (var i = 1; i < path.nodes.length; i++) {
    var a = path.nodes[i - 1], b = path.nodes[i];
    var kmPerLon = 111.195 * Math.cos(p[0] * Math.PI / 180);
    var dx = (b[1] - a[1]) * kmPerLon, dy = (b[0] - a[0]) * 111.195;
    var px = (p[1] - a[1]) * kmPerLon, py = (p[0] - a[0]) * 111.195;
    var len2 = dx * dx + dy * dy;
    var t = len2 ? Math.max(0, Math.min(1, (px * dx + py * dy) / len2)) : 0;
    var distance = Math.hypot(px - t * dx, py - t * dy);
    if (distance < nearest) {
      nearest = distance;
      along = path.cum[i - 1] + (path.cum[i] - path.cum[i - 1]) * t;
    }
  }
  if (nearest > .5 || path.total <= 0) return null; // rota dışındayken tahmin yok.
  return { km: route.distanceKm * Math.max(0, 1 - along / path.total),
           note: 'GPS yaklaşık kalan' };
}

// Rota etrafındaki istasyonları verimli filtrelemek için segment uzamsal ızgarası.
// 0.1° grid, segment AABB alanları koridor kadar genişletilir.
var CELL = 0.1;
function cellKey(a, b) { return a + '/' + b; }
function indexRoute(line) {
  var cells = new Map();
  for (var i = 1; i < line.length; i++) {
    var a = line[i - 1], b = line[i], midLat = (a[0] + b[0]) / 2;
    var dy = CFG.corridorKm / 110.574;
    var dx = CFG.corridorKm / (111.32 * Math.max(.1, Math.cos(midLat * Math.PI / 180)));
    var la = Math.floor((Math.min(a[0], b[0]) - dy) / CELL);
    var lb = Math.floor((Math.max(a[0], b[0]) + dy) / CELL);
    var oa = Math.floor((Math.min(a[1], b[1]) - dx) / CELL);
    var ob = Math.floor((Math.max(a[1], b[1]) + dx) / CELL);
    for (var y = la; y <= lb; y++) {
      for (var x = oa; x <= ob; x++) {
        var key = cellKey(y, x), entry = cells.get(key);
        if (!entry) { entry = []; cells.set(key, entry); }
        entry.push([a, b]);
      }
    }
  }
  return cells;
}
// Equirectangular yerel düzlemde, nokta ile DOĞRU PARÇASI arası asgari uzaklık.
// Yalnız 5 km yakınlık filtresi için kullanılır; sürüş yolu uzaklığı değildir.
function segmentDistanceKm(p, a, b) {
  var latScale = 111.195;
  var lonScale = 111.195 * Math.cos(p[0] * Math.PI / 180);
  var ax = (a[1] - p[1]) * lonScale, ay = (a[0] - p[0]) * latScale;




  var bx = (b[1] - p[1]) * lonScale, by = (b[0] - p[0]) * latScale;
  var dx = bx - ax, dy = by - ay, den = dx * dx + dy * dy;
  var t = den ? Math.max(0, Math.min(1, -(ax * dx + ay * dy) / den)) : 0;
  return Math.hypot(ax + t * dx, ay + t * dy);
}
function inCorridor(s, cells) {
  var parts = cells.get(cellKey(Math.floor(s.lat / CELL), Math.floor(s.lon / CELL)));
  if (!parts) return false;
  var p = [s.lat, s.lon];
  for (var i = 0; i < parts.length; i++) {
    if (segmentDistanceKm(p, parts[i][0], parts[i][1]) <= CFG.corridorKm) return true;
  }
  return false;
}
function isDC(s) {
  // Şarj gücünden DC tahmin edilmez; yalnız açık tip bilgisi kullanılır.
  if (typeof s.dc === 'boolean') return s.dc;
  if (s.ccs === true) return true;
  var types = [s.type, s.currentType, s.current_type, s.connectorType, s.sockets]
    .flat().filter(function (v) { return typeof v === 'string'; }).join(' ');
  return /\bDC\b|\bCCS\b|CHADEMO/i.test(types);
}
function sanitizeStation(s, i) {
  if (!s || typeof s !== 'object') return null;
  var lat = finiteNumber(s.lat), lon = finiteNumber(s.lon != null ? s.lon : s.lng);
  if (!validLL(lat, lon)) return null;
  return {
    id: String(s.id != null ? s.id : 'station-' + i),
    lat: lat, lon: lon,
    title: String(s.title || s.name || 'Şarj istasyonu').slice(0, 90),
    operator: String(s.operator || '').slice(0, 60),
    sockets: String(s.sockets || s.type || '').slice(0, 60),
    dc: isDC(s)
  };
}

function stationPopup(s) {
  var root = document.createElement('div');
  var head = document.createElement('strong');
  head.textContent = s.title;
  var detail = document.createElement('div');
  detail.textContent = [s.operator, s.dc ? 'DC' : 'AC / tip doğrulanmadı', s.sockets]
    .filter(Boolean).join(' • ');
  var button = document.createElement('button');
  button.type = 'button'; button.textContent = 'Hedef yap';
  button.addEventListener('click', function () {
    setTarget(s.lat, s.lon, s.title);
    map.closePopup();
  });
  root.append(head, detail, button);
  return root;
}
function renderStations() {
  if (!map) return;
  layers.stations.clearLayers();
  visibleStations = [];
  if (!route) { setText('evcockpit-station-count', '—'); highlightNearestDC(true); return; }
  if (!isCountryDataReady() && routeStationSnapshot) {
    // Show the genuine EPDK route packet in the HUD; MediaEngine renders its markers.
    visibleStations = routeStationSnapshot.items;
    setText('evcockpit-station-count', routeStationSnapshot.total + ' (rota)');
    highlightNearestDC(true);
    return;
  }
  var grid = indexRoute(route.line);
  // Tüm istasyon listesi filtrelenir; sadece koridordakiler çizilir.
  for (var i = 0; i < stations.length; i++) {
    var s = stations[i];
    if (!inCorridor(s, grid)) continue;
    visibleStations.push(s);
    if (!attachedToExisting) {
      var marker = L.marker([s.lat, s.lon], {
        icon: icon('⚡', s.dc ? 'evcockpit-dc' : 'evcockpit-ac')
      }).bindPopup(stationPopup(s));
      layers.stations.addLayer(marker);
    }
  }
  setText('evcockpit-station-count', visibleStations.length +
    (isCountryDataReady() ? '' : ' (kısmi)'));
  highlightNearestDC(true);
}

function isCountryDataReady() {
  return countrySnapshot.complete && countrySnapshot.verifiedAt > 0 &&
    Date.now() - countrySnapshot.verifiedAt >= 0 &&
    Date.now() - countrySnapshot.verifiedAt <= 72 * 60 * 60 * 1000;
}
function highlightNearestDC(force) {
  if (!map) return;
  // GPS alınmadıysa haritanın merkezini veya rota başını gerçek konum diye kullanma.
  if (!currentLocation || !locationAt ||
      Date.now() < locationAt ||
      Date.now() - locationAt > CFG.telemetryStaleMs ||
      car.soc === null || car.soc >= 20 || isTelemetryStale()) {
    if (markers.nearestDC) map.removeLayer(markers.nearestDC);
    markers.nearestDC = null;
    setText('evcockpit-nearest-dc', '—'); return;
  }
  if (!force && markers.nearestDC && Date.now() - lastNearestAt < CFG.nearestRefreshMs) return;
  if (markers.nearestDC) { map.removeLayer(markers.nearestDC); markers.nearestDC = null; }
  lastNearestAt = Date.now();
  if (!isCountryDataReady()) {
    setText('evcockpit-nearest-dc', 'Güncel ve tam EPDK listesi doğrulanmadı');
    return;
  }
  var nearest = null, best = Infinity;
  // Rota dışındaki DC de en yakın olabilir; TÜM gerçek istasyonları tara.
  for (var i = 0; i < stations.length; i++) {




     if (!stations[i].dc ||
        stations[i].currentStatus === 'offline' ||
        stations[i].currentStatus === 'out_of_service') continue;
     var dist = kilometers(currentLocation, [stations[i].lat, stations[i].lon]);
     if (dist < best) { best = dist; nearest = stations[i]; }
    }
    if (!nearest) { setText('evcockpit-nearest-dc', 'DC kaydı bulunamadı'); return; }
    markers.nearestDC = L.marker([nearest.lat, nearest.lon], {
      zIndexOffset: 2000, icon: icon('⚡', 'evcockpit-nearest-dc-pulse')
    }).bindPopup(stationPopup(nearest)).addTo(map);
    var age = Date.now() - nearest.statusObservedAtMs;
    var recentState = nearest.statusObservedAtMs > 0 &&
      age >= 0 && age <= 15 * 60 * 1000;
    var availability = recentState && nearest.currentStatus === 'available' ?
      'müsait (tarihli veri)' :
      recentState && nearest.currentStatus === 'occupied' ?
      'meşgul (tarihli veri)' : 'müsaitlik bilinmiyor';
    setText('evcockpit-nearest-dc', nearest.title + ' • kuş uçuşu ' +
      best.toFixed(1) + ' km • ' + availability);
}

// Sosyal ihbarlar, Firebase bağlanana kadar yalnızca bu cihazın yerel verisidir.
function renderIncidents() {
  var before = incidents.length;
  incidents = incidents.filter(validLocalIncident);
  if (before !== incidents.length) safeStorageSave();
  if (!map) return;
  layers.incidents.clearLayers();
  incidents.forEach(function (it) {
    var label = it.type === 'accident' ? '💥 Kaza' : '⛔ Yol kapalı';
    var mark = L.marker([it.lat, it.lng], {
      icon: icon(it.type === 'accident' ? '💥' : '⛔', 'evcockpit-incident')
    });
    var popup = document.createElement('div');
    var txt = document.createElement('strong'); txt.textContent = label;
    var note = document.createElement('div');
    note.textContent = 'Yalnız bu cihazdaki kişisel not · doğrulanmadı';
    var timestamp = document.createElement('div');
    timestamp.textContent = new Date(it.timestamp).toLocaleString('tr-TR');
    var remove = document.createElement('button');
    remove.type = 'button'; remove.textContent = 'Bu notu sil';
    remove.addEventListener('click', function () {
      removeLocalIncident(it.id);
      if (map && typeof map.closePopup === 'function') map.closePopup();
    });
    popup.append(txt, note, timestamp, remove);
    mark.bindPopup(popup);
    layers.incidents.addLayer(mark);
  });
  setText('evcockpit-incident-count', incidents.length + ' yerel ihbar (doğrulanmadı)');
}
function removeLocalIncident(id) {
  var before = incidents.length;
  incidents = incidents.filter(function (item) { return item.id !== id; });
  if (incidents.length === before) return false;
  safeStorageSave(); renderIncidents();
  status('Yerel ihbar bu cihazdan silindi.');
  return true;
}
// Firebase için takılabilir boş veri gönderme mimarisi.
function sendIncidentToDB(lat, lng, type) {
  // TODO Firebase örneği:
  // addDoc(collection(db, 'incidents'), {lat, lng, type, timestamp: serverTimestamp()});
  // Backend kurulana kadar bir şey gönderilmez.
  if (typeof onIncidentToDB === 'function') {
    try { Promise.resolve(onIncidentToDB(lat, lng, type)).catch(console.warn); }
    catch (e) { console.warn('İhbar gönderilemedi:', e); }
  }
}
function reportIncident(lat, lng, type) {
  if (!validLL(lat, lng) || !['accident', 'closed'].includes(type)) return null;
  var obj = { id: 'local-' + Date.now() + '-' + Math.random().toString(36).slice(2),
              lat: lat, lng: lng, type: type, timestamp: new Date().toISOString() };
  incidents.push(obj);
  incidents = incidents.slice(-250);
  safeStorageSave(); renderIncidents();
  sendIncidentToDB(lat, lng, type);
  status('İhbar cihazda kaydedildi (grup paylaşımı henüz bağlı değil).');
  return obj;
}
function incidentPopup(latlng) {
  var root = document.createElement('div');
  var head = document.createElement('strong'); head.textContent = 'Bu noktada ne var?';
  root.appendChild(head);
  [['💥 Kaza', 'accident'], ['⛔ Yol Kapalı', 'closed']].forEach(function (arr) {
    var button = document.createElement('button');
    button.type = 'button'; button.textContent = arr[0];
    button.addEventListener('click', function () {
      reportIncident(latlng.lat, latlng.lng, arr[1]); map.closePopup();
    });
    root.appendChild(button);
  });
  var originButton = document.createElement('button');
  originButton.type = 'button'; originButton.textContent = '📍 Başlangıç yap';
  originButton.addEventListener('click', function () {
    setOrigin(latlng.lat, latlng.lng); map.closePopup();
  });
  root.appendChild(originButton);
  L.popup().setLatLng(latlng).setContent(root).openOn(map);
}

function abortCurrent() {
  if (routeAbort) routeAbort.abort();
  if (weatherAbort) weatherAbort.abort();
  routeAbort = weatherAbort = null;
}
function clearRoute() {
  routeEpoch++;
  abortCurrent();
  if (layers.route) layers.route.clearLayers();
  route = null; weather = null; routeStationSnapshot = null;
  if (layers.stations) layers.stations.clearLayers();
  if (markers.nearestDC) { map.removeLayer(markers.nearestDC); markers.nearestDC = null; }
  setText('evcockpit-route', '—'); setText('evcockpit-weather', '—');
  setText('evcockpit-range', '—'); setText('evcockpit-station-count', '—');
  setText('evcockpit-nearest-dc', '—');
}




function setOrigin(lat, lon) {
  lat = finiteNumber(lat); lon = finiteNumber(lon);
  if (!map || !validLL(lat, lon)) return false;
  origin = [lat, lon];
  if (markers.origin) map.removeLayer(markers.origin);
  markers.origin = L.circleMarker(origin, {
    radius: 8, color: '#fff', weight: 2, fillColor: '#f59e0b', fillOpacity: 1
  }).addTo(map).bindPopup('Başlangıç');
  clearRoute(); status('Başlangıç seçildi. Haritadan hedef belirleyin.');
  return true;
}
function setTarget(lat, lon, title) {
  lat = finiteNumber(lat); lon = finiteNumber(lon);
  if (!map || !validLL(lat, lon)) return false;
  destination = [lat, lon];
  if (markers.destination) map.removeLayer(markers.destination);
  markers.destination = L.circleMarker(destination, {
    radius: 8, color: '#fff', weight: 2, fillColor: '#10b981', fillOpacity: 1
  }).addTo(map).bindPopup(String(title || 'Hedef').slice(0, 90));
  clearRoute();
  status('Hedef seçildi. evcockpitCreateRoute() ile OSRM rotası çizilebilir.');
  return true;
}
function drawRoute(data, options) {
  if (!data || typeof data !== 'object' || !Array.isArray(data.coordinates)) return false;
  if (data.coordinates.length < 2 || data.coordinates.length > 40000) return false;
  var line = [];
  for (var i = 0; i < data.coordinates.length; i++) {
    // GeoJSON/OSRM ve mevcut Android kontratı: [longitude, latitude].
    var raw = data.coordinates[i];
    if (!Array.isArray(raw) || raw.length < 2) return false;
    var lng = finiteNumber(raw[0]), lat = finiteNumber(raw[1]);
    if (!validLL(lat, lng)) return false;
    line.push([lat, lng]);
  }
  clearRoute();
  var shouldDraw = !(options && options.draw === false);
  if (shouldDraw) layers.route.addLayer(L.polyline(line, { color: '#2675f5', weight: 6, opacity: 0.92 }));
  var distanceKm = finiteNumber(data.distanceKm);
  if (distanceKm === null || distanceKm <= 0) {
    distanceKm = 0;
    for (var j = 1; j < line.length; j++) distanceKm += kilometers(line[j - 1], line[j]);
  }
  var durationMin = finiteNumber(data.durationMin);
  route = { line: line, distanceKm: distanceKm, stopIncluded: data.stopIncluded === true,
    durationMin: durationMin !== null && durationMin >= 0 ? durationMin : null,
    midpoint: distanceMidpoint(line), progress: progressNodes(line) };
  if (shouldDraw) {
    var rLayer = layers.route.getLayers()[0];
    map.fitBounds(rLayer.getBounds(), { padding: [28, 28], maxZoom: 15 });
  }
  setText('evcockpit-route', distanceKm.toFixed(1) + ' km' +
    (route.durationMin === null ? '' : ' • ' + Math.round(route.durationMin) + ' dk (trafiksiz)'));
  renderStations(); updateEnergy();
  refreshWeather();
  status('Gerçek OSRM rota geometrisi çizildi; hava durumu kontrol ediliyor.');
  return true;
}

async function fetchJSON(url, controller, ms) {
  var timer = window.setTimeout(function () { controller.abort(); }, ms);
  try {
    var res = await window.fetch(url, { signal: controller.signal, cache: 'no-store' });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    return await res.json();
  } finally { window.clearTimeout(timer); }
}
async function createRoute(start, end) {
  if (start != null && end != null) {
    // API uçları [lat, lon] olarak alınır.
    var a = normalizeLL(start), b = normalizeLL(end);
    if (!a || !b) throw new Error('Geçersiz başlangıç/hedef koordinatı');
    setOrigin(a[0], a[1]); setTarget(b[0], b[1]);
  }
  if (!origin || !destination) throw new Error('Başlangıç ve hedef seçilmedi');
  var epoch = ++routeEpoch;
  abortCurrent();
  var controller = new AbortController(); routeAbort = controller;
  var url = CFG.osrmUrl + '/route/v1/driving/' +
    origin[1] + ',' + origin[0] + ';' + destination[1] + ',' + destination[0] +
    '?overview=full&geometries=geojson&steps=false';
  try {
    status('OSRM rotası hesaplanıyor…');
    var json = await fetchJSON(url, controller, 16000);
    if (epoch !== routeEpoch) return false;
    var r = json && json.routes && json.routes[0];
    if (json.code !== 'Ok' || !r || !r.geometry || !Array.isArray(r.geometry.coordinates)) {
      throw new Error('OSRM yanıtında kullanılabilir rota yok: ' + (json.code || 'unknown'));




      }
      // drawRoute içindeki clearRoute epoch'u artırır; bundan sonra stale kontrolü gerekmez.
      return drawRoute({ coordinates: r.geometry.coordinates,
                         distanceKm: r.distance / 1000, durationMin: r.duration / 60 });
    } catch (e) {
      if (!controller.signal.aborted) status('Rota oluşturulamadı: ' + String(e.message));
      console.warn('OSRM hata:', e);
      return false;
    } finally { if (routeAbort === controller) routeAbort = null; }
}

async function refreshWeather() {
  if (!route || !route.midpoint) return;
  if (weatherAbort) weatherAbort.abort();
  var epoch = routeEpoch, pt = route.midpoint;
  var controller = new AbortController(); weatherAbort = controller;
  var url = CFG.weatherUrl + '?latitude=' + pt[0].toFixed(5) +
    '&longitude=' + pt[1].toFixed(5) +
    '&current=temperature_2m,wind_speed_10m&wind_speed_unit=kmh' +
    '&timezone=GMT&timeformat=unixtime';
  try {
    var json = await fetchJSON(url, controller, 12000);
    if (epoch !== routeEpoch) return;
    var t = finiteNumber(json && json.current && json.current.temperature_2m);
    var w = finiteNumber(json && json.current && json.current.wind_speed_10m);
    var modelSec = finiteNumber(json && json.current && json.current.time);
    if (t === null || w === null || w < 0 ||
        modelSec === null || Math.abs(Date.now() - modelSec * 1000) > 90 * 60000)
      throw new Error('Open-Meteo verisi eksik veya eski');
    // Bir veya her iki eşik aşılırsa YALNIZ bir defa +%15.
    weather = { temperatureC: t, windKmh: w, multiplier: (t < 0 || w > 30) ? 1.15 : 1,
                time: new Date(modelSec * 1000).toISOString(), source: 'Open-Meteo model' };
    setText('evcockpit-weather', t.toFixed(1) + '°C • rüzgâr ' + w.toFixed(0) + ' km/sa' +
      (weather.multiplier > 1 ? ' • tüketim +%15' : ''));
  } catch (e) {
    if (epoch !== routeEpoch) return;
    weather = null;
    setText('evcockpit-weather', 'Hava verisi alınamadı');
    console.warn('Open-Meteo hata:', e);
  } finally {
    if (weatherAbort === controller) weatherAbort = null;
    if (epoch === routeEpoch) updateEnergy();
  }
}

function isTelemetryStale() {
  // A clock jump backwards must not revive an obsolete OBD measurement.
  var age = Date.now() - car.receivedAt;
  return !car.receivedAt || age < 0 || age > CFG.telemetryStaleMs;
}
function updateCarData(speed, soc) {
  var s = finiteNumber(speed), p = finiteNumber(soc);
  var validSpeed = s !== null && s >= 0 && s <= 300;
  var validSoc = p !== null && p >= 0 && p <= 100;
  // Asla 0% veya 0 km/sa'yı geçersiz sayma.
  car.speed = validSpeed ? s : null;
  car.soc = validSoc ? p : null;
  car.receivedAt = Date.now();
  renderTelemetry(); updateEnergy(); highlightNearestDC(false);
  return validSpeed || validSoc;
}
function renderTelemetry() {
  var fresh = !isTelemetryStale();
  setText('evcockpit-speed', fresh && car.speed !== null ? Math.round(car.speed) : '—');
  setText('evcockpit-soc', fresh && car.soc !== null ? car.soc.toFixed(1) + '%' : '—');
  var bar = $('evcockpit-soc-bar');
  if (bar) {
    bar.style.width = fresh && car.soc !== null ? car.soc + '%' : '0%';
    bar.style.opacity = fresh && car.soc !== null ? '1' : '0.2';
  }
}
function setEnergyProfile(profile) {
  var kwh = finiteNumber(profile && profile.usableBatteryKWh);
  var per100 = finiteNumber(profile && profile.consumptionKWhPer100Km);
  energyProfile = {
    usableBatteryKWh: kwh !== null && kwh > 0 ? kwh : null,
    consumptionKWhPer100Km: per100 !== null && per100 > 0 ? per100 : null
  };
  updateEnergy();
  return energyProfile.usableBatteryKWh !== null && energyProfile.consumptionKWhPer100Km !== null;
}
function updateEnergy() {
  if (!route || car.soc === null || isTelemetryStale() ||
      energyProfile.usableBatteryKWh === null || energyProfile.consumptionKWhPer100Km === null) {
    setText('evcockpit-range', '— • doğrulanmış SoC ve tüketim profili gerekli'); return;
  }
  var remaining = remainingRouteDistance();
  if (!remaining) { setText('evcockpit-range', 'GPS rota dışında • varış SoC hesaplanmadı'); return; }
  // Kalan yol mesafesi (km) x teyitli tüketim (kWh/100km).
  var factor = weather ? weather.multiplier : 1;
  var energyKWh = remaining.km * energyProfile.consumptionKWhPer100Km / 100 * factor;
  var deltaSoc = energyKWh / energyProfile.usableBatteryKWh * 100;
  var finalSoc = car.soc - deltaSoc;
  // Şarj mediaenginesının miktarı bilinmiyorsa varış batarya yüzdesi hesaplanamaz.




  if (route.stopIncluded) {
    setText('evcockpit-range', energyKWh.toFixed(1) +
      ' kWh • şarj mediaenginesı miktarı belirsiz; varış SoC yok'); return;
  }
  // Negatif tahmini SoC'yi sıfıra yuvarlayıp 'varır' izlenimi verme.
  setText('evcockpit-range', energyKWh.toFixed(1) + ' kWh • varış ~%' +
    finalSoc.toFixed(1) + (finalSoc < 0 ? ' (enerji yetersiz)' : '') +
    ' (' + remaining.note + ')' +
    (weather ? '' : ' (hava etkisi hesaplanmadı)'));
}
function updateLocation(lat, lon) {
  lat = finiteNumber(lat); lon = finiteNumber(lon);
  if (!validLL(lat, lon)) return false;
  var oldLocation = currentLocation;
  currentLocation = [lat, lon]; locationAt = Date.now();
  if (map) {
    if (!attachedToExisting && !markers.current) {
      markers.current = L.circleMarker(currentLocation, {
        radius: 7, color: '#fff', weight: 2, fillColor: '#0ea5e9', fillOpacity: 1
      }).addTo(map).bindPopup('GPS konumu');
    } else if (markers.current) markers.current.setLatLng(currentLocation);
    // GPS çok az oynadıysa binlerce istasyonu yeniden tarama.
    highlightNearestDC(!oldLocation || kilometers(oldLocation, currentLocation) > .3);
    updateEnergy();
  }
  return true;
}
// Phase 3 production contract: strict *connector-level* schema. The official
// EPDK public snapshot currently has coordinates + raw socket descriptions,
// but NOT trustworthy per-socket kW units or live currentStatus. Do not
// synthesize missing fields or promote an EPDK-only snapshot to 'validated'.
//
// Input: {metadata:{source:'EPDK',coverage:'country',complete:true,
//   verifiedAtUtc:'...',geocodedCount:N},stations:[
//   {id:'...',coordinates:{lat:39,lng:35},socketType:'AC'|'DC',
//    power_kw:50,currentStatus:'unknown'|'available'|'occupied'|'offline'|
//       'out_of_service', statusObservedAtUtc?:'...'}]}
//
// 'available'/'occupied' require a fresh, explicit observation timestamp;
// unknown is valid but can NEVER be presented as known availability.
function validateEPDKDataContract(data) {
  var errors = [], normalized = [];
  function fail(reason) {
    if (errors.length < 8) errors.push(reason);
  }
  try {
    if (!data || typeof data !== 'object' ||
        !Array.isArray(data.stations) ||
        !data.metadata || typeof data.metadata !== 'object') {
      return { ok: false, errors: ['EPDK veri yapısı eksik'], stations: [] };
    }
    var meta = data.metadata, input = data.stations;
    var verifiedAt = Date.parse(meta.verifiedAtUtc);
    var age = Date.now() - verifiedAt;
    if (meta.source !== 'EPDK' || meta.coverage !== 'country' ||
        meta.complete !== true) fail('Kapsam ve EPDK kaynağı doğrulanmadı');
    if (!Number.isInteger(meta.geocodedCount) || meta.geocodedCount < 5000 ||
        meta.geocodedCount !== input.length || input.length > 100000)
      fail('Tam ülke kayıt sayısı doğrulanmadı');
    if (!Number.isFinite(verifiedAt) || age < 0 ||
        age > 72 * 60 * 60 * 1000)
      fail('EPDK doğrulama zamanı geçersiz veya eski');
    // Never spend O(N) CPU on an obviously incomplete or excessive batch.
    if (errors.length) return {ok:false, errors:errors, stations:[]};
    var ids = new Set();
    for (var i = 0; i < input.length; i++) {
      var item = input[i];
      if (!item || typeof item !== 'object') {fail('Boş istasyon #' + i); break;}
      var id = item.id;
      var key = (typeof id === 'string' || typeof id === 'number') ?
        String(id).trim() : '';
      var p = item.coordinates;
      var lat = p && p.lat, lon = p && p.lng;
      var power = item.power_kw, type = item.socketType;
      var state = item.currentStatus;
      if (!key || key.length > 128 || ids.has(key)) {
        fail('Benzersiz id hatası #' + i); break;
      }
      if (typeof lat !== 'number' || typeof lon !== 'number' ||
          !Number.isFinite(lat) || !Number.isFinite(lon) ||
          lat < 35 || lat > 43 || lon < 25 || lon > 46) {
        fail('Türkiye koordinatları hatalı #' + i); break;
      }
      if (type !== 'AC' && type !== 'DC') {
        fail('AC/DC socketType eksik #' + i); break;
      }
      if (typeof power !== 'number' || !Number.isFinite(power) ||
          power <= 0 || power > 1000) {
        fail('Doğrulanmış power_kw eksik #' + i); break;
      }
      if (['unknown','available','occupied','offline','out_of_service']
            .indexOf(state) < 0) {
        fail('currentStatus eksik veya geçersiz #' + i); break;
      }
      if (state === 'available' || state === 'occupied') {
        var observedAt = Date.parse(item.statusObservedAtUtc);
        var observedAge = Date.now() - observedAt;
        if (!Number.isFinite(observedAt) || observedAge < 0 ||
            observedAge > 15 * 60 * 1000) {
          fail('Canlı soket durumu eski veya zaman damgası yok #' + i); break;
        }
      }
      ids.add(key);
      normalized.push({
        id:key, lat:lat, lon:lon, dc:type==='DC',
        socketType:type, power_kw:power, currentStatus:state,
        statusObservedAtMs: state === 'available' || state === 'occupied' ?
          Date.parse(item.statusObservedAtUtc) : 0,
        title:String(item.title || item.name || 'Şarj istasyonu').slice(0,90),
        operator:String(item.operator || '').slice(0,60),
        sockets:type+' · '+power+' kW'
      });
    }
    return {ok:errors.length===0 && normalized.length===input.length,
      errors:errors, stations:errors.length?[]:normalized};
  } catch (error) {
    console.warn('EPDK contract exception', error);
    return {ok:false, errors:['EPDK doğrulaması yapılamadı'],stations:[]};
  }
}
var lastEPDKValidation = {ok:false, errors:['Veri henüz sağlanmadı']};
function loadStations(entries, metadata) {
  // A regional array, a map viewport and an old EPDK JSON are all rejected
  // atomically: no marker from the failed batch may survive on this addon.
  var checked = validateEPDKDataContract({stations:entries,metadata:metadata});
  lastEPDKValidation = {ok:checked.ok, errors:checked.errors.slice()};
  if (!checked.ok) {
    countrySnapshot = {complete:false, verifiedAt:0};
    stations = [];
    visibleStations = [];
    if (layers.stations) layers.stations.clearLayers();
    if (markers.nearestDC && map) {
      map.removeLayer(markers.nearestDC);
      markers.nearestDC = null;
    }
    setText('evcockpit-station-count','—');
    setText('evcockpit-nearest-dc','—');
    status('EPDK veri sözleşmesi reddedildi: ' +
      (checked.errors[0] || 'Eksik veri'));
    return 0;
  }
  stations = checked.stations;
  countrySnapshot = {
    complete:true, verifiedAt:Date.parse(metadata.verifiedAtUtc)
  };
  renderStations();
  return stations.length;
}
function setRouteStations(packet) {
  // Separate legacy MediaEngine route subset: for existing UI count ONLY.
  // It never enters validateEPDKDataContract(), because EPDK snapshot
  // cannot certify live currentStatus or kW, and it never drives the
  // nearest-country-DC highlight.
  if (!packet || packet.kind !== 'route' || !Array.isArray(packet.items)) return 0;
  var unique = new Map();
  packet.items.forEach(function (item, i) {
    if (!item || item.cluster) return;
    var station = sanitizeStation(Object.assign({}, item, {
      dc: item.ccs === true || item.dc === true
    }), i);
    if (station) unique.set(station.id + ':' + station.lat + ':' + station.lon, station);
  });
  var items = Array.from(unique.values());
  var reported = finiteNumber(packet.total);
  routeStationSnapshot = {
    items: items,
    total: reported !== null && reported >= items.length ? Math.floor(reported) : items.length
  };
  if (route && !isCountryDataReady()) renderStations();
  return items.length;
}
function importIncidents(entries) {
  if (!Array.isArray(entries)) return 0;
  var byId = new Map();
  incidents.concat(entries).forEach(function (raw) {
    var item = normalizeLocalIncident(raw);
    if (item) byId.set(item.id, item);
  });
  incidents = Array.from(byId.values()).slice(-250);
  safeStorageSave(); renderIncidents();
  return incidents.length;
}
function getBounds() {
  if (!map) return null;
  var b = map.getBounds();
  return [b.getSouth(), b.getWest(), b.getNorth(), b.getEast()];
}
function focus(lat, lon, title) {
  if (!setTarget(lat, lon, title)) return false;
  map.setView([Number(lat), Number(lon)], 14, { animate: false });
  return true;
}
function init(existingMap) {
  if (map) return;
  if (typeof L === 'undefined') { status('Leaflet yüklenemedi'); return; }
  if (!$('map')) { console.error('#map elementi bulunamadı'); return; }
  if (existingMap) {
    // Mevcut MediaEngine Leaflet'i yeniden oluşturulmaz; harita altlığı/araç UI korunur.
    attachedToExisting = true;
    map = existingMap;
  } else {
    map = L.map('map', { preferCanvas: true, zoomControl: true, maxZoom: 18 })
      .setView([39.0, 35.0], 6); // Varsayılan harita görünümü, GPS değil.
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
      attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap katkıcıları</a>',
      maxZoom: 18, updateWhenIdle: true, keepBuffer: 1
    }).addTo(map);
  }
  connectTrafficProvider();
  layers.route = L.layerGroup().addTo(map);
  layers.stations = L.layerGroup().addTo(map);
  layers.incidents = L.layerGroup().addTo(map);
  if (!existingMap) map.on('click', function (e) {
    setTarget(e.latlng.lat, e.latlng.lng, 'Harita hedefi');
  });
  // Leaflet: sağ tık VE desteklenen mobil cihazlarda uzun basma.
  map.on('contextmenu', function (e) { incidentPopup(e.latlng); });
  if ($('evcockpit-route-create')) $('evcockpit-route-create').addEventListener('click', function () {




        createRoute().catch(function (e) { status(e.message); });
      });
      incidents = safeStorageLoad();
      renderIncidents(); renderStations(); renderTelemetry(); updateEnergy();
      weatherTimer = window.setInterval(function () {
        if (route) refreshWeather();
      }, CFG.weatherRefreshMs);
      healthTimer = window.setInterval(function () {
        if (incidents.some(function (item) { return !validLocalIncident(item); })) renderIncidents();
        if (isTelemetryStale()) {
          renderTelemetry(); updateEnergy(); highlightNearestDC(false);
        }
      }, 5000);
      setText('evcockpit-status', 'Harita hazır • hedef için dokun, ihbar için uzun bas.');
      if ($('loading')) $('loading').style.display = 'none';
      window.evcockpitReady = true;
      window.dispatchEvent(new Event('evcockpit:ready'));
  }

  // Centralized fail-safe for every Android/native bridge entrypoint and
  // Leaflet/DOM helper. Catches both synchronous exceptions and async
  // rejections so malformed native JSON never escapes into the WebView.
  function guarded(name, fn, fallback) {
    return function () {
      try {
        var value = fn.apply(this, arguments);
        if (value && typeof value.then === 'function') {
          return value.catch(function (error) {
            console.warn('EVCOCKPIT ' + name, error);
            status(name + ': veri kullanılamıyor');
            return fallback;
          });
        }
        return value;
      } catch (error) {
        console.warn('EVCOCKPIT ' + name, error);
        status(name + ': veri kullanılamıyor');
        return fallback;
      }
    };
  }
  renderStations = guarded('istasyon haritası', renderStations, undefined);
  highlightNearestDC = guarded('DC gösterimi', highlightNearestDC, undefined);
  renderIncidents = guarded('ihbar haritası', renderIncidents, undefined);
  renderTelemetry = guarded('araç göstergesi', renderTelemetry, undefined);
  updateEnergy = guarded('enerji hesabı', updateEnergy, undefined);
  drawRoute = guarded('rota çizimi', drawRoute, false);
  createRoute = guarded('rota servisi', createRoute, false);
  clearRoute = guarded('rota temizleme', clearRoute, undefined);
  setOrigin = guarded('başlangıç', setOrigin, false);
  setTarget = guarded('hedef', setTarget, false);
  getBounds = guarded('harita sınırları', getBounds, null);
  focus = guarded('harita odağı', focus, false);
  importIncidents = guarded('yerel ihbar alımı', importIncidents, 0);
  sendIncidentToDB = guarded('ihbar iletimi', sendIncidentToDB, undefined);
  loadStations = guarded('EPDK aktarımı', loadStations, 0);
  setRouteStations = guarded('rota istasyonları', setRouteStations, 0);
  updateCarData = guarded('SoC/hız', updateCarData, false);
  updateLocation = guarded('konum', updateLocation, false);
  setEnergyProfile = guarded('enerji profili', setEnergyProfile, false);
  refreshWeather = guarded('hava durumu', refreshWeather, undefined);
  init = guarded('harita başlatma', init, undefined);
  var api = {
     init: init,
     createRoute: createRoute,
     showRoute: drawRoute,
     // MediaEngine navigator.html kendi rotasını çiziyorsa ikinci çizgi ekleme.
     ingestNativeRoute: function (data) { return drawRoute(data, { draw: false }); },
     createTomTomFlowTileLayer: createTomTomFlowTileLayer,
     toggleTraffic: toggleTraffic,
     attachExistingMap: function (existingMap) {
       if (!existingMap || typeof existingMap.addLayer !== 'function') return false;
       init(existingMap);
       return !!(map && layers.route && layers.stations && layers.incidents);
     },
     clearRoute: clearRoute,
     setOrigin: setOrigin,
     setTarget: setTarget,
     setStations: loadStations,
     validateEPDKDataContract: validateEPDKDataContract,
     getEPDKValidation: function () {
       return {ok:lastEPDKValidation.ok,errors:lastEPDKValidation.errors.slice()};
     },
     isCountryDataReady: isCountryDataReady,
     setRouteStations: setRouteStations,
     setEnergyProfile: setEnergyProfile,
     updateCarData: updateCarData,
     updateLocation: updateLocation,
     setIncidentSender: function (fn) { onIncidentToDB = typeof fn === 'function' ? fn : null; },
     importIncidents: importIncidents,
     removeLocalIncident: removeLocalIncident,
     sendIncidentToDB: sendIncidentToDB,
     getState: function () {
       return { routeKm: route ? route.distanceKm : null, weather: weather,
         soc: isTelemetryStale() ? null : car.soc,
         position: !locationAt || Date.now() < locationAt ||
           Date.now() - locationAt > CFG.telemetryStaleMs ? null : currentLocation,
         corridorStations: visibleStations.length,
         countryStationsVerified: isCountryDataReady(),
         localIncidents: incidents.length };
     }
  };
  window.EVCOCKPITLiveMap = api;
  window.validateEPDKDataContract = validateEPDKDataContract;
  // Var olan Kotlin/Java evaluateJavascript çağrıları bu isimleri kullanabilir.
  window.evcockpitShowStations = loadStations;
  window.evcockpitShowRoute = drawRoute;
  window.evcockpitClearRoute = clearRoute;
  window.evcockpitMapBounds = getBounds;
  window.evcockpitFocus = focus;
  window.evcockpitIsReady = function () {
    return !!(map && layers.route && layers.stations && layers.incidents);
  };
  window.evcockpitGetRouteEndpoints = function () {
     return origin && destination ? [origin[1], origin[0], destination[1], destination[0]] : null;
  };
  window.evcockpitCreateRoute = createRoute;
  window.evcockpitUpdateCarData = updateCarData;
  window.evcockpitUpdateLocation = updateLocation;
  window.evcockpitSetEnergyProfile = setEnergyProfile;
  window.evcockpitImportIncidents = importIncidents;
  // İsteğe bağlı CustomEvent: new CustomEvent('evcockpit:car-data', {detail:{speed,soc}})
  window.addEventListener('evcockpit:car-data', function (e) {
     if (e && e.detail) updateCarData(e.detail.speed, e.detail.soc);
  });
  // navigator.html için ATTACH_ONLY=true: 2. bir Leaflet haritası OLUŞTURMA.
  if (!window.EVCOCKPIT_LIVE_ATTACH_ONLY) {
     if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', function () { init(); });
     else init();
  }
})(window, document);
