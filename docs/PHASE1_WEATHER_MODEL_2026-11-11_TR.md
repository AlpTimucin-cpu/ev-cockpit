# Faz 1 — Open-Meteo güncel MODEL ve kalan rota TAHMİNİ
Tarih: 11 Kasım 2026. Durum: DRAFT; gerçek telefon/ID.4 onayı bekleniyor.

## Kaynak ve kapsam
- Mevcut GPS: yalnız taze, hassas (<=35 m) gerçek konum.
- Rota: yalnız gerçek OSRM geometri, GPS eşleşmesi ve profil süresi
  kullanılarak kalan rotada en fazla **8** saatlik geçiş noktası.
  ETA **modeldir**, canlı trafik içermez.
- Tek HTTPS Open-Meteo /v1/forecast çağrısı en fazla 9 koordinat:
  ilk koordinat mevcut gerçek GPS; sonraki en fazla 8 rota noktası.
  İstenen alanlar current temperature_2m, wind_speed_10m, weather_code;
  hourly bu alanlar ve precipitation_probability.
  `timezone=GMT`, `timeformat=unixtime`. UTC epoch > Instant,
  kullanıcı saat dilimi > yerel saat; sabit +3 eklenmez.
- Current koşulları Open-Meteo MODEL verisidir; istasyon gözlemi
  değildir. Yanıtın model zamanı alım saatinden çok eskiyse reddedilir.
- Rota yoksa yalnız GPS güncel model; internet yoksa önceki verinin
  gerçek yaşı 'X dk önce', hiç veri yoksa 'kullanılamıyor'.

## Yenileme / güvenlik
- Uygulama görünürken StateFlow abonesi üzerinden; Android Auto ekranı
  açıkken Car Screen coroutine üzerinden. Otomatik istek başlangıçları
  arasında en az 30 dakika; manuel buton erken isteği tetikleyebilir.
- Aynı rota için kaynak havuzunda 30 dakikalık tekil model cache;
  telefon ve araç art arda API'ye gitmez. Rota ID değişince sıfırlanır.
- WorkManager asgari 15 dk ama Doze tam zamanı garanti etmez:
  https://developer.android.com/reference/androidx/work/PeriodicWorkRequest .
  Arka plan GPS, exact alarm veya uyandırma servisi eklenmedi.
- Debug Tanılama'da son gerçek deneme / son başarı zaman damgası.
  Koordinat, VIN, MAC ve API anahtarı günlüğe yazılmaz.
- Yağmur/sis (1), kar (2), fırtına (3) risk artışı; tekrar etmez.
  Manevra sesi öncelikli, hava uyarısı en fazla 2 dakika bekler.

## Telefon ve Android Auto
- Telefon: Hava ayrıntı sayfası; güncel model saat/sıcaklık/rüzgâr,
  rota nokta saati ve yağış olasılığı, açık kaynak etiketi.
  Saatlik harita ikonları tahminin yaşı 45 dakikayı aşarsa
  kaldırılır; eski veri yalnız yaş etiketiyle ayrıntıda kalır.
- Araç: mevcut Car App Library Pane/MapWithContentTemplate düzeni;
  host ConstraintManager satır limiti, video/WebView yok.
  İki Pane eyleminden biri "Havayı yenile" (açık manuel istek),
  diğeri "Rotaya dön"; şarj listesi navigasyon menüsünden ulaşılır.
  Hem güncel model hem saatlik rota varsa host limiti paylaşılır.
- Resmî MGM MeteoUYARI kamu sayfası:
  https://www.mgm.gov.tr/meteouyari/turkiye.aspx ;
  lisanslı ve kararlı makine API erişimi **doğrulanmadı**. Bu PR,
  MGM adına resmî alarm üretmez. Veri paylaşımı:
  https://www.mgm.gov.tr/site/bilgi-edinme.aspx .

## Open-Meteo lisans
- https://open-meteo.com/en/docs ve https://open-meteo.com/en/terms
- Ücretsiz API yalnız ticari olmayan kullanım:
  **<10.000/gün, <5.000/saat, <600/dakika** ve
  CC BY 4.0 kaynak gösterimi. Ticari yayım için izin/lisans
  yeniden değerlendirilmeli. API servis sürekliliği garanti değil.
- Gerçek Türkiye kapsaması, kaynak çağrı hakkı/uzun vadeli
  üretim lisansı ve fiziksel ID.4 ekran testi **doğrulanmadı**.

## CI ve kabul
```sh
python3 -m unittest discover -s tests -p 'test_phase1_weather_integrity.py' -v
./gradlew :nativecockpit:lintDebug :nativecockpit:testDebugUnitTest :nativecockpit:assembleDebug
```
- CurrentWeatherParserTest: epoch/geçerlilik/sahte veri red.
- HourlyForecastParserTest: doğru UTC/DST saat eşlemesi.
- CarWeatherPresentationPolicyTest: şablon satır limiti/model ayrımı.
- WeatherChangeAlertGateTest: risk şiddeti değişimi.
- Python: gerçek GPS, veri kaynağı, zamanlayıcı, ekran sınırları.
- CI PASS gerçek ağ yanıtı ya da gerçek cihaz kullanımı değildir.

## Telefon sahibinin Faz 1 testi (park halinde)
1. Güncel PR Debug APK artifact'ı kur: com.evcockpit.debug.
2. Hassas GPS+internet açık, rota yok: Hava ekranında
   gerçek model ve geçerlilik saati ya da açık 'kullanılamıyor'.
3. Gerçek OSRM rota aç: en fazla 8 nokta, geçiş saati
   yerel saat ve kaynaktaki yağış olasılığını kontrol et.
4. İnterneti kes: eski MODEL 'X dk önce' güncelliği
   ya da veri yok durumu. Manuel yenileme hatasını gör.
5. 30 dk önce/sonra Debug Tanılama'da son istek zamanlarını
   karşılaştır; yanlış veya eksik koşul varsa test edilmedi.
6. ID.4 Android Auto: parkta Hava şablonunu aç,
   model/veri yok/host sınırı ve uyarıların manevra
   sesini kesmemesini gözlemle.
7. Ekran kaydı/fotoğraf veya maskelenmiş Debug rapor gönder;
   konum, VIN, MAC, gizli anahtar paylaşma.
Sahip doğrulayıp onaylamadan Faz 2, merge, release YOK.
