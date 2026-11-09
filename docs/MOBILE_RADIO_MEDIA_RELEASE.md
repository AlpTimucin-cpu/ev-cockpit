# EV Cockpit – radyo, medya, harita ve release mobil kontrolü

Hedef kaynak dalı: `feature/ev-cockpit`. Test dalı değişiklikler PR üzerinden hedefe alınır.
Bu yönerge telefon tarayıcısı + GitHub Actions ile kullanılabilir; Android Studio gerektirmez.

## 1. Türkiye radyoları

- Retrofit: `nativecockpit/src/main/java/com/evcockpit/data/radio/RadioBrowserApi.kt`
- Depo: `.../data/radio/RadioRepository.kt`
- Ekran/oynatıcı: `.../presentation/media/RadioScreen.kt`
- Media menüsü: `.../presentation/media/ParkedStreamingDialog.kt`
- Bağımlılık: `.../di/RadioModule.kt`
- Android ağ izni: `nativecockpit/src/main/AndroidManifest.xml`

API isteği: `GET https://de1.api.radio-browser.info/json/stations/search?countrycode=TR&hidebroken=true&order=votes&reverse=true&limit=200`.
Model alanları: `stationuuid`, `name`, `url_resolved`, `favicon`, `tags`, `votes`.
Radio Browser oy verilerini kullanır, bağımsız yayın lisanslarını garanti etmez.
Uygulama HTTPS olmayan stream URL'lerini bilerek filtreler; sınırsız cleartext izni açılmaz.

Android telefon: uygulamada **Medya > Radyo** sekmesini aç, arama alanından kanal adı veya tür yaz,
kanala dokun. Duraklat/Durdur düğmeleri ExoPlayer'ı kontrol eder.
Ekranı kapatınca veya sekmeden ayrılınca yayın durdurulur.

## 2. Harici Medya Motoru ve YouTube

`MediaLauncher.launchMediaEngine(context)` yalnız manifest `<queries>` listesinde bulunan
debug/release package adlarını denetler. Package kurulu değilse `NotInstalled`,
kurulu ama başlatılamıyorsa `Blocked` döner. `CockpitScreen` ikisi için de koyu
temalı Material3 `AlertDialog` gösterir.

`MediaWebViewScreen`: YouTube video tam ekran isteği `WebChromeClient.onShowCustomView`
ile `FullscreenVideoDialog` penceresine geçer; geri tuşu/çıkış ve
`onHideCustomView` ile görünüm ve callback temizlenir. Kapatma esnasında medya duraklatılır.
Bu işlev yalnız telefondaki uygun ekran içindir; Android Auto hareket halindeyken video
kısıtları aşılmaz. Üçüncü taraf DRM desteği site/cihazdan bağımsız olarak garanti edilemez.

## 3. Harita Z katmanları

Gerçek rota katmanı kimliği `eya-route-line` (`ROUTE_LAYER`). İstenen genel
`route-layer` burada **kullanılmıyor**.
- Hava durumu: `style.addLayerAbove(weatherLayer, ROUTE_LAYER)`.
- DC sembolleri: `style.addLayerAbove(chargingLayer, ROUTE_LAYER)`.
- Asenkron kaynak güncellemelerinde `ensureRouteOverlayZOrder(style)`,
  rota ve OSRM hız profili üstüne şarj ve hava katmanlarını yeniden sıralar.

Telefonla kontrol: rota çiz > DC filtre uygula > hava katmanının görünmesini bekle >
haritayı yakınlaştır/uzaklaştır > simgelerin rota çizgisi üzerinde kaldığını doğrula.

## 4. Release çökmesi, R8 ve imzalama

- `gradle.properties`: `android.enableR8.fullMode=false`.
- `nativecockpit/build.gradle.kts` `release`: `isMinifyEnabled=false`,
  `isShrinkResources=false`.
- `nativecockpit/proguard-rules.pro` mevcut sınıf korumalarını içerir, ancak küçültme
  kapalı olduğu için bunlar aktif bir crash düzeltmesi değildir.
- `FATAL EXCEPTION` içeren **uygulama açılış logcat'i henüz mevcut değil**.
  Bu nedenle spekülatif `-keep class ** { *; }` gibi genel kurallar eklenmedi.
- Önceki workflow'larda debug test ve APK üretimi başarıyla bitti.
  Release job'u **imzalama anahtarının geri yüklenmesi** sırasında hata verdi;
  bu bir uygulama açılış stacktrace'i değildir.

Sadece mobil kullanım: GitHub > Actions > **Native Cockpit · Kotlin Compose APK** >
en son run > `build` içindeki `Native Cockpit unit tests` / `Compile native debug APK`
adımlarına bak. Başarılıysa run altındaki **Artifacts** bölümünden debug APK'yı al.
Debug APK, sahibinin release imzasını taşımaz; yüklü release sürümü üzerine kurulmayabilir.

Crash kanıtı gerekiyorsa Android Ayarlar > Geliştirici seçenekleri >
**Hata raporu al / Take bug report** ile yeniden oluşturulan açılış hatası sonrası
rapor hazırla. Rapor kişisel veri barındırabilir: paylaşmadan önce e-posta,
hesap kimlikleri, GPS ve token gibi özel alanları maskele.
PC üzerinden alternatif:

```powershell
adb logcat -c
# Telefonda uygulamayı açıp hatayı yeniden oluştur.
adb logcat -d -v time -b crash | Select-String -Pattern "FATAL EXCEPTION|AndroidRuntime|Caused by" -Context 0,24
```

Exception sınıfı / neden stack'i doğrulandığında yalnız hedef sınıflar için
minimum -keep kuralı ve gerekiyorsa kaynak kod düzeltmesi yapılmalıdır.

## 5. GitHub deposunun adını telefondan değiştirme

1. Telefonda tarayıcıda `https://github.com/AlpTimucin-cpu/ID4-Media-Hub` aç ve giriş yap.
2. Depo menüsündeki **Settings** sekmesine git (görünmüyorsa **…** veya
   tarayıcıdan **Masaüstü sitesi**).
3. **General** sayfasında **Repository name** alanına `ev-cockpit` yaz.
4. **Rename** düğmesine bas ve onayla.
5. Yeni adresi `https://github.com/AlpTimucin-cpu/ev-cockpit` olarak kontrol et.
6. README, GitHub Actions YAML, proje belgeleri, klonlar ve harici linklerde eski yolu
   yeni adrese geçir. `feature/ev-cockpit` **dalının adını** değiştirmek zorunlu değildir.

GitHub çoğu eski depo URL'sini yönlendirir; ancak yeniden adlandırılan depodan
çağrılan GitHub Actions action URL'leri için yönlendirme garantisi yoktur.
Eski depo adını yeni bir depo için yeniden kullanma.

Resmî kaynak: https://docs.github.com/en/repositories/creating-and-managing-repositories/renaming-a-repository
