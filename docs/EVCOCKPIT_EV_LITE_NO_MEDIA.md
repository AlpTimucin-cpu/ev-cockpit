# EV Cockpit — EV Lite (medyasız sürüm)

**Kullanıcının tercih ettiği yeni ürün:** Film/dizi/maç/IPTV,
VLC, Media3, MediaEngine, YouTube, Netflix ve Prime Video artık kullanıcıya
sunulan EV Lite APK'sında **derlenmez ve paketlenmez**. Eski medya
kaynakları depoda geçmiş dal/proje için bırakılmıştır; yeni Android
modülü `:evlite` onlardan bağımsızdır.

## Hafif sürüm neleri yapar?
- EPDK'nın doğrudan resmî halka açık istasyonlarından oluşturulan,
  kaynak zamanı kontrol edilen veri dosyasını yalnızca kullanıcının
  talebiyle indirir.
- İstasyon no, operatör, adres, CCS, Type 2, CHAdeMO,
  resmî güç alanları ve lisans numaralarını listeler.
- Soket, operatör ve metin filtresini **arka planda** çalıştırır;
  her seferinde en fazla **40** Android satırı çizilir.
- Harita motoru/GPU yüzünden donmamak için ilk sürümde gömülü
  MapLibre haritası yok; istasyon koordinatları Google Maps
  yönlendirmesine aktarılır.
- Resmî Trugo/ZES genel tarifelerini doğrulama zamanlarıyla
  açabilir; sokete özgü doğrulanmayan fiyatlar belirtilmez.
- EV Cockpit 52 kWh düzenlenebilir batarya profiliyle şarj, maliyet,
  ideal süre, rezervli yol menzili ve gerçek şarj harcama
  kayıtları hesaplar.
- İnternet dışında kullanıcıya ait hiçbir bilgi göndermez.
  Konum, mikrofon, kamera, SMS, rehber, OBD, Bluetooth,
  Shizuku, medya dosyası izni içermez.

## İndirilecek modül
- Gradle görevi: `gradle :evlite:testDebugUnitTest :evlite:assembleDebug`
- Çıktı: `evlite/build/outputs/apk/debug/evlite-debug.apk`
- Kimlik: `com.evcockpit.evguide.lite`, eski `com.evcockpit.mediahub.test`
  sürümünün **yanına kurulur**.
- CI: `.github/workflows/build-ev-lite.yml` ve
  mevcut `.github/workflows/build-apk.yml` içinde
  `ev-lite-debug` işi. Derleme olmadan uygulama
  çalışıyor iddiasında bulunulmaz.

## Şeffaf kısıtlar
- Telefon içi gerçek tam navigasyon motoru değildir. Google Maps
  üzerinden sesli yol tarifi başlatılır.
- EPDK resmî statik istasyon bilgisi, soketin canlı müsaitliği
  anlamına gelmez.
- Kapsam ve güvenlik testlerine rağmen gerçek POCO F5 Android 15
  üzerinde kullanıcı testi gerekir.
- Eski film/dizi kayıtları yeni uygulamaya aktarılmaz ve eski
  uygulama kaldırılmadan korunabilir.
