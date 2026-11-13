# FAZ 2 · Trafik sağlayıcısı seçimi ve devre dışı güvenlik iskeleti
**13 Kasım 2026 — TASLAK / SAHİP ONAYI BEKLENİYOR**

## Kaynak gerçekliği
- `main` eski Media Hub kaynaklarıdır. Faz 2, Faz 1 PR #55'in
  `44c070772fb5a56ed31fc206dd627c91b887dd26` commit'ini temel alır.
- Mevcut `OsrmRouteRepository` rota geometrisi/süre ve yol profili üretir;
  bunlar **trafiksiz tahmin** olup canlı trafik ölçümü değildir.
- Henüz doğrulanmış canlı trafik yanıtı, ücretli trafik hesabı, kullanıma
  hazır TomTom anahtarı, yol olay kaynağı veya trafik ETA'sı yoktur:
  **Trafik: kullanılamıyor**. Rota renkli hız profili canlı trafik
  olarak etiketlenmemelidir.
- Bu PR, kaynak ve zaman doğrulama politikası + JVM testleri + 
  telefon/Debug ekranında doğru kullanılamıyor etiketini ekler.
  Ağ çağrısı, yeni anahtar, otomatik istek veya araç overlay'i EKLEMEZ.

## Sağlayıcı karşılaştırması (resmî yayımlanmış kaynaklar)
| Sağlayıcı | Veri | Kota/ücret | Entegrasyon ve koşul |
| --- | --- | --- | --- |
| **TomTom Orbis Traffic** | Flow/incident tile, incident details; TomTom Routing trafikli ETA | İncident details ücretsiz 2.500/ay; vector/raster flow+incident tiles 200.000/ay; Routing 20.000/ay | Kişisel API key, hesap kotası/kullanım koşulu, atıf; Android native MapLibre REST katmanı. Türkiye güzergâh örneklemesi **doğrulanmadı** |
| TomTom eski Maps Flow Segment API | Nokta segment current/free-flow hız | Ücretsiz 20.000/ay (ayrı ürün) | Yeni geliştirmede Orbis öneriliyor; legacy endpoint'i karıştırma |
| HERE Traffic API v7 | Incidents, flow, coğrafi koridor filtresi | Güncel ücretsiz sayısal kota bu incelemede **doğrulanmadı** | API key, hesap ve Base Plan kullanım kısıtları; araçla ilgili telemetri/uyarı kapsamı ayrıca hukuk incelemesi gerektirir |
| Mapbox Directions driving-traffic | Trafikli güzergâh ETA; raw traffic ayrı data lisansı | Directions ilk 100.000/ay ücretsiz gösteriliyor; ham Traffic Data ayrıca satış lisansı | Trafik segmentlerinin MapLibre'da bağımsız kullanımı ayrı lisans denetimi |

Resmî kaynaklar (güncel doğrulama):
- TomTom https://docs.tomtom.com/pricing
- TomTom Orbis https://docs.tomtom.com/traffic-api/documentation/tomtom-orbis-maps/v2/product-information/introduction
- TomTom API kullanım koşulları https://www.tomtom.com/en-gb/legal/third-party-product-terms/
- HERE https://docs.here.com/traffic-api/docs/send-request-readme
- HERE Base Plan restrictions https://www.here.com/get-started/pricing/base-plan-restrictions
- Mapbox https://docs.mapbox.com/data/traffic/
- Mapbox Directions https://www.mapbox.com/pricing

## ÖNERİ / ONAY KAPISI
**Tek öneri: TomTom Orbis Traffic + gerektiğinde ayrı TomTom Routing API.**
Neden: Resmî free-tier tabloları açık, olay/akış verisi ve trafik
duyarlı rota aynı sağlayıcı ailesinde, MapLibre katmanı için uygun
veri biçimleri var. Android Kotlin katmanında SDK JS kopyalanmaz:
sağlayıcı onayından sonra lisans ve endpoint'e uygun REST
istemcisi/kısa ömürlü katman tasarlanır. Trafik ETA'sı verilmeden
önce tek tek bağımsız rota yanıtının mevcut OSRM rotasıyla kapsam/
zaman eşleşmesi doğrulanır; farklı rotanın ETA'sı mevcut rotanın
ETA'sı gibi yazılmaz.

**Sahip onayına kadar TomTom çağrısı ve anahtar girişi AKTİF DEĞİL.**
Eski TomTom anahtarının iptali/rotasyonu **DOĞRULANMADI**; önce sahibi
my.tomtom.com üzerinden iptal/kota/harcama/kapsamı kontrol etmeli.
Yeni anahtar sahibi tarafından uygulamaya girilecek; sohbete/GitHub
secret dosyasına girilmez. Android Keystore AES-GCM/uygulama özel
şifreli kayıt, URL/HTTP hata/günlüklerde anahtar maskeleme ve 429'dan
sonra kontrollü bekleme **sağlayıcı onayını izleyen kod adımıdır**.
İstek gövdesinde/URL'de koordinatın sağlayıcıya aktarılması
gizlilik bilgilendirmesi gerektirir; kullanıcı izni olmadan aktarılmaz.

## Sonraki Faz 2 alt işleri — henüz tamamlanmadı
- Sağlayıcı + ToS + Türk şehirlerarası rota kapsamı onayı.
- Anahtarın cihazda Keystore ile saklanması; hiçbir anahtar APK,
  BuildConfig, GitHub, log, Crashlytics'e konulmaz.
- Gerçek olay yanıtı (kaynak tarihi/olay türü/seviyesi/geometri) ve
  taze trafik akışı/segmentleri; en az 5 dakikada bir yaşı
  ve ağ-kota sınırlaması açık; kaynak artık bayatsa kırmızı/yeşil
  trafik katmanı silinir ve durum kullanılamıyor'a döner.
- Gerçek trafik-aware routing API cevabında ETA ve rota eşleşmesi.
  OSRM profili ile trafik gecikmesi asla türetilmez.
- Hız sınırı yalnız kaynağı doğrulanmışsa; görülmüyorsa
  'kullanılamıyor'. Android Auto Car App Library navigation
  template/Alert/ses önceliği sınırları ve gerçek 12,9 ekran kabulü.
- Faz 2 Debug Tanılama'nın canlı veri saatleri, olay/kayıt sayısı,
  hata/kota gibi sayımları yalnız gerçek yanıtla dolmalıdır.

## CI ve gerçek kabul
```bash
python3 -m unittest discover -s tests -p 'test_phase2_traffic_gate.py' -v
./gradlew :nativecockpit:lintDebug :nativecockpit:testDebugUnitTest :nativecockpit:assembleDebug
```
TrafficEvidencePolicyTest: onaysız/key'siz/verisiz durum, stale,
farklı rota, 429, network failure, trafik-aware ETA provenance.
Kaynak testi: UI ve Debug etiketlerinin düzmece veri üretmemesi.
Gitleaks/R8/OBD read-only kapıları korunacak.

Sahip mobil kabulü:
1. PR Faz 2 Debug APK artefact'ını yükle, Settings ve Rota planı:
   **Trafik: kullanılamıyor**, **OSRM trafiksiz tahmin** görünmeli.
2. Debug Tanılama: trafik veri saati/olay/speed limit
   kullanılamıyor; key/VIN/MAC/konum görünmemeli.
3. Android Auto parkta: canlı trafik akışı/olay göstermemeli,
   OSRM profil renkleri canlı ölçüm diye sunulmamalı.
4. Henüz sağlayıcı onaylanmadığı için hesap/API erişimi/traffic ETA/
   hız limiti **DENENMEDİ / DOĞRULANMADI**.
Faz 2 kullanıcı onayı ve tam gerçek veri CI + araç testi olmadan
Faz 3'e geçilmez; PR birleştirilmez, Release kapalı kalır.
Geri dönüş: PR'ı kapat, yalnız Faz 2 dalını sil;
main/Faz 1/Releases değişmez.
