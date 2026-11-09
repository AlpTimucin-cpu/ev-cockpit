# Android Auto hava tahmini — 30 dakikalık yenileme doğrulaması

**Kaynak kontrolü: 9 Kasım 2026; gerçek cihazda yenileme zamanlaması henüz doğrulanmadı.**

## Doğrudan kod kanıtı

- `nativecockpit/src/main/java/com/evcockpit/car/CarWeatherPresentationPolicy.kt`:
  `CarWeatherRefreshGate.AUTOMATIC_REFRESH_MS = 30L * 60_000L`.
  Aynı `routeId` için `markAttempt` sonrasında otomatik yenileme
  **en erken 1.800.000 ms sonra** izinlenir.
- `nativecockpit/src/main/java/com/evcockpit/car/EyaCarNavigationScreen.kt`:
  `maybeRefreshWeather` yalnız canlı Car Screen `work` coroutine'i
  ve gerçek rota kimliği, güncel hassas GPS (≤35 m), OSRM üzerinde
  `ON_ROUTE` eşleşmesi varken çağrılır. Job devam ediyorsa ikinci istek
  başlatılmaz. Rota kimliği değişirse gate ve durum sıfırlanır.
- `nativecockpit/src/main/java/com/evcockpit/data/weather/OpenMeteoWeatherRepository.kt`:
  `DiagnosticsHub.weatherAttempt()` ve başarı/başarısızlık
  olayları kaydedilir; API yanıtı ve eski veri zamanı ayrı alanlardır.
- `nativecockpit/src/test/java/com/evcockpit/car/CarWeatherPresentationPolicyTest.kt`:
  29:59.999'da yeni istek YOK, 30:00.000'da izin VAR; GPS/rota yoksa
  istek YOK, sistem saatinin geri alınmasıyla yeni istek YOK.
- `nativecockpit/src/test/java/com/evcockpit/diagnostics/DiagnosticsHubTest.kt`:
  Tanılama ekranındaki gösterilen 30 dk sabiti doğrudan aynı gate
  değişkeninden okunur. Bu **gerçek isteğin atıldığını kanıtlamaz**.

## Doze / WorkManager neden kullanılmadı?

- Resmî `PeriodicWorkRequest` asgari tekrarlama aralığı **15 dakikadır**,
  ancak Doze/batarya optimizasyonları ve koşullar zamanlamayı
  **geciktirebilir**: 30 dakikalık worker ayarı **tam 30 dakikada**
  çalışma garantisi vermez.
- Bu ekran **yalnız aktif Android Auto navigasyonunun rota üzerindeki
  tahmini** için veri çekiyor. Telefon kilitliyken veya Car Screen
  kapanmışken arka plan konum/worker işlemi gerektirecek bir kullanım
  zorlanmıyor. İş ayrıca `ACCESS_BACKGROUND_LOCATION` istemiyor.
- Android Auto host aktifken bir saniyelik UI tick yalnız gate'i
  kontrol ediyor; API isteği en sık **30 dakikada bir** (aynı rota).
  Android, ağ veya GPS kesintisinde gecikme olabilir; bu bir sabit
  zaman garantisi değildir. Hava durumu **TAHMİN** olarak kalır.

## Telefonda test (bilgisayar olmadan)

1. Park halinde izinleri ver, internete bağlan, gerçek OSRM rotası seç ve
   Android Auto'da Hava bölümünü aç.
2. Ayarlar → Tanılama'da `Son hava yenileme denemesi`,
   `Son başarılı hava verisi`, `Hava sonucu` satırlarını kaydet.
3. Aynı gerçek rota, konum ve foreground Android Auto açıkken
   **31–35 dakika sonra** raporu yeniden kopyala. Son yeni istek
   zamanıyla ilkini karşılaştır.
4. 30 dakika dolmadan ikinci otomatik deneme varsa **KALDI**.
   Süre geçtiği halde internet/izin/host durumu belirsizse
   **TEST EDİLEMEDİ**; kesin arıza denmez.
5. Ağ kesildiğinde son aynı rota verisi varsa "X dk önce" etiketini
   kontrol et. Sorunlu tarihlerde koordinat, VIN, Bluetooth adresi
   veya gizli anahtar paylaşma.

Resmî kaynak: https://developer.android.com/reference/kotlin/androidx/work/PeriodicWorkRequest
