# FAZ 2 — TomTom Orbis Traffic v2, güvenli etkinleştirme
**13 Kasım 2026 — TASLAK / canlı anahtar + telefon + ID.4 kabulü DOĞRULANMADI.**

## Yetki ve sınır
- Kullanıcı TomTom Orbis Traffic'i 13 Kasım 2026'da onayladı.
  Önceden sızmış olabilecek eski TomTom anahtarını **kullanmayın**;
  eski anahtarın TomTom panelinden iptali **doğrulanmadı**.
- Uygulamada Ayarlar → Yeni TomTom API anahtarı: karakterler
  maskelenir, yalnız AndroidKeyStore AES-GCM anahtarıyla şifrelenmiş
  blob özel SharedPreferences'a yazılır. Geçersiz biçim/kırılmış
  Keystore kabul edilmez. Kullanıcı daha sonra ayrı konum paylaşım
  anahtarını **açıkça açmadan** HTTP isteği yoktur. Sildikten sonra
  istek ve gösterim kapatılır. Depo, sohbete, APK BuildConfig'ine,
  Debug raporuna veya URL query'sine anahtarı eklemeyin.
- Tarafınızdan açılan konum paylaşımı; gerçek GPS/OSRM güzergâhına
  ait sınırlı koordinatları TomTom API'ye iletir. Kullanım
  koşullarını ve veri aktarımını inceleyin.

## Kod
- `data/traffic/TomTomCredentialVault.kt`: yalnız cihazda Keystore.
- `data/traffic/TomTomTrafficHttp.kt`: sabit HTTPS TomTom origin,
  `TomTom-Api-Key` başlığı, redirect kapalı, MIME/boyut sınırları,
  GET Incident Details Orbis v2 ve Raster Flow v2.
- `TomTomIncidentParser`: sağlayıcının GeoJSON olaylarını ayrıştırır,
  `timeValidity=present` ve bilinen kategorileri kabul eder;
  rota noktasına **yaklaşık 3 km** uzaklıktaki adayları gösterir.
  Yakınlık aynı yol/şerit/yön etkilenmesinin kesin kanıtı değildir.
- `TomTomCorridorPolicy`: taze GPS ≤35 m, OSRM snap,
  yaklaşık sonraki 40 km bounded bbox, maksimum 6 raster
  örneği. Raster **sağlayıcının gerçek renkli PNG verisidir**;
  OSRM profile hız grafiği trafik sanılmasın diye gizli.
  Uygulama hız yüzdelerini veya road speed limit tahmin etmez.
- `TomTomTrafficRepository`: ortak StateFlow, Mutex,
  otomatik ≥5 dakika, kullanıcı elle yenilemesi ≥60 saniye,
  API 429/401/403 ve yerel kota 30 dakika backoff,
  güvenli timeout, ağ yoksa/konum yoksa/rota uyuşmazsa
  anında **Trafik: kullanılamıyor**.
- Yerel koruyucu rezerv: aylık **2.000 Incident Details** ve
  **80.000 raster flow tile** istek (başarısızlar da sayılır).
  Bu kota sahibin TomTom hesabındaki gerçek hakları
  garanti etmez ve diğer uygulama/anahtar kullanımını kapsamaz.
- `TomTomTrafficMapRenderer.kt`: MapLibre native `ImageSource`
  + `RasterLayer` + gerçek olay noktası; TTL sonrası layer'ları
  kaldırır. Telefon ve Android Auto Car Surface aynı şablon
  güvenli MapLibre kaynaklarını kullanır. Hiçbir video/WebView yok.
- `TrafficDetailSheet.kt`: telefonun ayrıntılı olay listesi;
  olayın lastReportTime (varsa) ve API HTTP sunucu saati
  birbirinden açıkça farklıdır. © TomTom atfı.
- `DebugDiagnosticsEntry.kt`: son istek/başarı, HTTP sunucu
  zamanını, olay/akış karo sayısını, sabit hata sınıfını
  verir; **koordinat, VIN, MAC, API key, URL, incident metni yok**.
- `TrafficEvidencePolicy.kt`: hatalı kaynaktan trafikli ETA
  çıkarmayı engeller. **Trafikli ETA, hız sınırı hâlen kullanılamıyor**:
  Orbis Routing v3 ile OSRM yol geometrisinin eşleşmesi
  test edilmeden rota süresi değiştirilmeyecek.

## Doğrulanmış API sözleşmesi ve kullanım şartları
- Incident GET /maps/orbis/traffic/incidents/details
  `apiVersion=2&bbox=minLon,minLat,maxLon,maxLat&timeValidity=present`.
  Tek bbox <10.000 km², API başlığıyla kimlik doğrulama.
  https://docs.tomtom.com/traffic-api/documentation/tomtom-orbis-maps/v2/traffic-incidents/incident-details
- Raster tile GET /maps/orbis/traffic/flow/raster/tile/z/x/y,
  `apiVersion=2&style=dark&tileSize=256`.
  https://docs.tomtom.com/traffic-api/documentation/tomtom-orbis-maps/v2/traffic-flow/raster-flow-tiles
- Trafikli ETA ayrı Orbis Routing v3 POST:
  https://docs.tomtom.com/routing-api/documentation/tomtom-orbis-maps/v3/calculate-route
- Resmî fiyat ve limit dokümanı:
  https://docs.tomtom.com/pricing
  Yayınlanan free tier **Incident Details 2.500/ay,
  trafik tile 200.000/ay, Routing 20.000/ay**; hesap
  özel kota/plan, harcama, Türkiye kapsaması, production
  kullanım izni ve SLA **doğrulanmadı**. Kotalar değişebilir.
- Lisans/attribution:
  https://www.tomtom.com/en-gb/legal/third-party-product-terms/
  © TomTom gösterimi korunur. Başka sağlayıcının döşeme
  ve verilerini lisans ihlaliyle kopyalamayın.
- Sunucu yanıtındaki HTTP `Date` başlığı yalnız **sunucu
  yanıt zamanı**; sensör/flow gözlem zamanına çevrilmez.
  Stale/failure/clock skew sonrası katman gizlenir.

## CI ve kod testleri
```bash
python3 -m unittest discover -s tests -p 'test_phase2_traffic_gate.py' -v
./gradlew :nativecockpit:lintDebug :nativecockpit:testDebugUnitTest :nativecockpit:assembleDebug --no-daemon
```
- `TomTomTrafficHttpTest`: MockWebServer ile doğrulanmış
  gerçek request formatı, header/URL sır güvenliği, 429
  ve GeoJSON olay ayrıştırma; gerçek TomTom hesabı testi DEĞİL.
- `TomTomKeySyntaxTest`: kontrol karakteri/CRLF ve
  şüpheli key biçimi reddi; gerçek AES-GCM cihaz testi eksik.
- `TomTomQuotaLimitsTest`: 429 ve yerel bütçe, TTL/route
  güvenliği; `TomTomTileQuadTest`: döşeme geometri sırası.
- Kaynak regresyonları: `test_phase2_traffic_gate.py`,
  secret scan/Gitleaks, OBD wire-only 01/22 kuralları ve
  R8 Release smoke hiçbir production APK dağıtmadan geçmeli.
- **Ağ hizmeti/canlı olay/gerçek Türkiye trafik kapsaması
  ve gerçek 12,9" ekran testi doğrulanmadı.**

## Telefon/araç kabulü — bilgisayarsız
1. Actions'taki **son HEAD** `EV-Cockpit-Native-Debug-APK`
   artifact'ını telefona kur; `com.evcockpit.debug`.
2. Ayarlar: anahtar yokken **Trafik: kullanılamıyor**,
   anahtar girildikten sonra açık paylaşım izni KAPALI.
3. Yeni TomTom key'i **yalnız uygulamada** girin;
   ayrı paylaşım anahtarını açın. İnternet/konum/hassas GPS
   ve gerçek OSRM güzergâh seçin. Gerçek olay ve akış
   görünmezse Debug Tanılama'da istek/HTTP durumunu inceleyin.
4. Telefon rota ekranında kırmızı/yeşil yalnız TomTom
   flow PNG'den, yoksa trafik yok; OSRM ETA **trafiksiz**.
5. İnterneti kapat, GPS'i kes veya anahtarı sil: trafik
   simgeleri kaybolmalı; 5 dakika eski veri gösterilmemeli.
6. 429/kota/hata varsa rapor güvenli sınıf kodunu gösterecek;
   gerçek 429 test edilemiyorsa **DOĞRULANMADI**.
7. Park hâlinde ID.4 Android Auto Hava/Şarj/Navigasyon
   ekranı ve harita: TomTom raster/olay yalnız taze
   veri varsa, video/WebView yok, host satır limitleri
   aşılmıyor. 12,9" fiziksel kabul özellikle gerekli.
8. AndroidKeystore API key gizliliği, anahtar silme ve
   cihaz yeniden açıldıktan sonra yalnız yerel saklama
   kabulünü doğrula. Paylaşacağın rapor/fotodan API key,
   VIN, MAC, koordinat gizlenmiş olmalı.

**Geri alma:** #56 DRAFT PR'ını kapat,
`phase2/traffic-provider-review-20261113` dalını sil;
`main`, üretim Release ve Faz 3 değişmez.
**Faz 2 cihaz kabulü ve kullanıcının ek onayı olmadan
Faz 3, merge, release YOK.**
