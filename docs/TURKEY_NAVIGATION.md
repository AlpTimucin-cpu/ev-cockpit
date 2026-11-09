# EV Cockpit — Türkiye harita ve şarj entegrasyonu

## Amaç
Türkiye genelinde bir şehir/ilçe/sokak/adres/işletme arayıp haritada
görmek, haritaya uzun basarak varış noktası belirlemek, görünür
bölgedeki şarj istasyonlarını işaretlemek, istasyona yol tarifi
başlatmak ve resmî veriler için Şarj@TR sayfasına ulaşmak.

## Mevcut ilk uygulama (kaynak kodu)
- `TurkeyNavigationActivity`: telefondaki yerel MapLibre harita arayüzü.
- `TurkeyGeoServices`: OpenStreetMap/Nominatim ile **kullanıcı düğmesine
  basınca** Türkiye adres araması; Overpass ile dar alanda OSM şarj
  noktalarını gösterme. Hiçbir arka plan veya konum izleme yok.
- `OpenChargeMapSources`: kullanıcı isteğe bağlı ücretsiz OCM API anahtarı
  girerse ikinci istasyon kaynağını sorgular. Anahtar Android Keystore
  AES-GCM ile yerelde saklanır ve HTTPS `X-API-Key` başlığında gönderilir.
- Harita görünümü: `org.maplibre.gl:android-sdk:13.7.1`.
- Harita stili: `https://tiles.openfreemap.org/styles/liberty`.
- Harita üzerine işaret koyma ve istasyon listesinden seçme.
- **Gerçek dönüş dönüş navigasyon** Google Maps uygulamasına açık,
  kullanıcı tarafından seçilen hedef üzerinden devredilir. Maps kurulu
  değilse HTTPS Google Maps Directions bağlantısı açılır.
- Resmî şarj verileri için EPDK Şarj@TR web bağlantısı.
- EV Enerji Merkezi ve uygulama ana sayfasından hızlı erişim.

## Kısıtlar
1. **MediaEngine ve Volkswagen'in yerleşik navigasyonu etkinleştirilmez.**
   Bu telefon uygulamasına eklenen yeni bir navigasyon/harita ekranıdır.
2. Haritada yalnızca kaynakta kayıtlı olan noktalar görünür.
   Hiçbir gönüllü veri kaynağı Türkiye'deki **bütün** kamu/özel
   şarj istasyonlarının eksiksiz ve güncel olduğunu garanti edemez.
3. OSM/OCM istasyon kayıtları anlık soket müsaitliğini veya fiyatı
   kesin olarak göstermez. Bu ayrıntılar resmî EPDK Şarj@TR ve
   işletmeci uygulamasında kontrol edilmelidir.
4. Kamuya açık Nominatim örnek sunucusu yaklaşık **1 req/s toplam**
   sınıra, uygulama tanımlayan User-Agent, manuel arama (otomatik tamamlama
   yok) ve atıf koşullarına sahiptir. Kod kullanıcı elle arama başlatır
   ve arama aralığı uygular; yine de **çok kullanıcılı üretim dağıtımı
   için bu sunucu uygun değildir.** Ölçekli dağıtımda ayrı barındırılan
   geocoder gerekir.
5. Overpass halka açık sunucuları büyük alan taraması ya da sürekli
   otomatik sorgu için uygun değildir. Kod haritanın dar görüntü alanı
   ve elle yenileme sınırı kullanır. Geniş ölçekli dağıtım kendi
   istasyon indeksini/veri sağlayıcısını gerektirir.
6. OpenFreeMap'in büyük çevrimdışı indirme koşulları ve servis
   kullanım sınırları dikkate alınmalıdır. Bu sürüm ülke çapında
   offline haritayı APK içine paketlemez.
7. Uygulama `ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`
   veya SMS/erişilebilirlik izinleri talep etmez. Kullanıcı haritayı
   elle kaydırır veya hedef adres arar.
8. Google Maps'in trafik tahmini, sesli yönlendirmesi, sapınca
   yeniden rota çizmesi gibi işlevler **EV Cockpit içinde
   geliştirilmiş değildir**, hedefi Maps'e devreder.
9. Android Auto üzerinde **yerel navigasyon kategorisi** için
   `androidx.car.app.category.NAVIGATION`, CarAppService, sürüşe uygun
   şablonlar, navigasyon motoru ve Google incelemesi ayrı gereksinimlerdir.
   Bu sürüm bu kategoriye geçiş/araç ekranında harita gösterimi iddia etmez.

## Doğrulanmış kaynak belgeleri
- MapLibre Android: https://maplibre.org/maplibre-native/android/examples/annotations/marker-annotations/
- OpenFreeMap: https://openfreemap.org/quick_start/
- OSM geocoding policy: https://operations.osmfoundation.org/policies/nominatim/
- OSM Overpass fair usage: https://dev.overpass-api.de/overpass-doc/en/preface/commons.html
- Open Charge Map API: https://www.openchargemap.org/develop/api
- EPDK Şarj@TR: https://www.epdk.gov.tr/Detay/Icerik/1-3428/serbest-erisim-platformu--sarj%40tr
- Android Auto navigation requirements: https://developer.android.com/training/cars/apps/navigation

## Profesyonel sürüme giderken gerekenler
- Türkiye OSM yol ağına dayalı lisanslı, kendi backend'i veya sözleşmeli
  routing sağlayıcısı: gerçek rota, ETA, yol kapanması, kılavuz talimatları.
- Yol boyunca şarj duraklarını mesafe/SoC/tüketim/rezerv hesabıyla
  seçen rota optimizer.
- EPDK veya işletmeci/OCPI sözleşmeli API'si ile anlık soket/fiyat;
  belgesiz API kazıma yok.
- İhtiyaç varsa kullanıcı onaylı mevcut konum + navigasyon bildirim
  servisleri, gizlilik ve güç tüketimi testleri.
- Harita verisi lisans/atıfları, önbellek, offline kapsama ve oran limitleri.
- POCO F5 Android 15 ve EV Cockpit Android Auto üzerinde birebir test.
- Android Auto Car App Library navigasyon sertifikasyonu.

**Durum:** Kaynak kodu eklendi; Play Store onayı, derleme ve fiziksel
navigasyon testlerinin tamamlandığı henüz doğrulanmadı.
