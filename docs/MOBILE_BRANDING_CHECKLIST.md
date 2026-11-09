# Mobil GitHub uygulama ve marka temizliği

## Güncellenen alanlar

- `nativecockpit/src/main/java/com/evcockpit/data/radio/RadioBrowserApi.kt`: Turkey ülke endpoint'i
- `nativecockpit/src/main/java/com/evcockpit/data/radio/RadioRepository.kt`: `Flow<Result<List<RadioStation>>>`, doğrulama ve iptal güvenliği
- `nativecockpit/src/main/java/com/evcockpit/presentation/media/RadioViewModel.kt`: asenkron yükleme ve hata durumu
- `nativecockpit/src/main/java/com/evcockpit/presentation/media/RadioScreen.kt`: arama çubuğu, Media3 ExoPlayer, yükleme ve hata göstergesi
- `nativecockpit/src/main/java/com/evcockpit/presentation/media/ParkedStreamingDialog.kt`: resmî DRM uygulamaları Intent ile açılır
- `nativecockpit/src/main/java/com/evcockpit/presentation/media/MediaLauncher.kt`: kurulu/yok/engelli hata ayrımı
- `nativecockpit/src/main/res/values*/strings.xml`: uyarı metinleri
- `nativecockpit/proguard-rules.pro`: sınırlı Retrofit/DTO/Media3 korumaları (R8 kapalı olduğundan bu sürümde pasif)
- `scripts/check_cockpit_radio_media_map_release.py`: otomatik regresyon kontrolü
- `scripts/audit_branding.py`: sadece okuyan eski marka denetimi

Korunan alanlar: `CockpitScreen.kt`, `MapLibreCockpit.kt`,
`NavigationViewModel.kt`, `DebugRouteSimulation.kt`,
`ManeuverGuidancePolicy.kt`, `AudioDuckingManager.kt` ve tüm OBD bağlantı kodu.

## Mobil cihazdan GitHub

1. `https://github.com/AlpTimucin-cpu/ID4-Media-Hub` adresini aç.
2. Üstten `feature/ev-cockpit` dalını seç. Kodları ilgili dosyaların tam içeriklerinden açıp okuyabilirsin.
3. `Actions > Native Cockpit · Kotlin Compose APK` bölümünde son başarılı çalışmayı seç.
4. `Artifacts > EV-Cockpit-Native-Kotlin-Debug-APK` dosyasını indir. ZIP içinden APK çıkar.
5. Debug sürümünü bir production APK üzerine kurmak Android imzası uyuşmazlığı nedeniyle başarısız olabilir. Mevcut uygulama verilerini yedeklemeden kaldırma.
6. Telefon üzerinde Medya > Radyo; kanal arama, Loading, hata; Media > Video > YouTube tam ekran; DRM servisleri Intent; test modunda 200 m / 50 m TTS kontrolünü yap.
7. Release için mevcut sahibin aynı keystore'u gerekir. GitHub `Settings > Secrets and variables > Actions` altında `EYA_SIGNING_BUNDLE_BASE64` yoksa imzalı release üretilmez.

## Depoyu yeniden adlandırma

1. Telefonda GitHub repo sayfasını aç.
2. `Settings > General > Repository name` alanına `ev-cockpit` gir.
3. `Rename` düğmesine bas; `https://github.com/AlpTimucin-cpu/ev-cockpit` açıldığını doğrula.
4. GitHub Actions, README, doküman, uzak klonlar ve haricî bağlantılardaki eski repo adresini yeni isimle güncelle. GitHub genelde eski adresleri yönlendirir.
5. Branch adı `feature/ev-cockpit` değiştirilmek zorunda değildir.

## Ana dal için eski marka temizleme stratejisi

1. Önce `main` ve `feature/ev-cockpit` dallarında `scripts/audit_branding.py` kapsamındaki kaynak/README ifadelerini tarayın.
2. **Görünen uygulama adı, ekran metinleri, README ve belgelerdeki** eski model/mülkiyet çağrışımlarını nötr metinlere değiştirin.
3. Paket isimleri, Android applicationId, mevcut imzalı medya motoru paket kimlikleri, Gradle modul adları, Git remote ve yasal telif atıflarını kullanıcı arayüzü yazısı gibi topluca değiştirmeyin. Bunların değişimi ayrı migration, uygulama yükleme ve lisans kontrolü ister.
4. Ekran marka temizliği ile Git geçmişinden tüm ifadelerin silinmesi aynı şey değildir. Git commit geçmişini zorla yeniden yazmak mevcut fork/PR ve imzaları bozar; tavsiye edilmez.
5. `main` README güncel, nötr İngilizce metni içerir. Kalan diğer main dosyaları ayrı PR ile denetlenebilir.

## Kalite ve sınırlar

- Radio Browser sunucusu/anlık istasyon erişilebilirliği değişebilir. Uygulama yalnız HTTPS stream oynatır.
- Media3 oynatıcı doğal `Player.STATE_BUFFERING` ve `PlaybackException` durumlarını gösterir.
- TTS `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` talep eder; üçüncü taraf uygulamanın gerçekten sesi kıstığı Android sistem davranışına bağlıdır.
- Debug unit test ve APK derleme, cihazda YouTube tam ekran, radyo yayını ya da release açılış testlerinin yerine geçmez.
- İmzalama secret'ı eksikken veya hata stacktrace'i yokken "release kesin çözüldü" iddiasında bulunulmaz.
