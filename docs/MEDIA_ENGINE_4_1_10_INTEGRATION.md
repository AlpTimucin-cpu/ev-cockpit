# MediaEngine 4.1.10 incelemesinden medya entegrasyonu

## Son davranış

- MediaEngine tabanlı APK'da kokpitin Netflix, Disney+ ve Prime Video kartları harici uygulama başlatmak yerine **mevcut WebBrowserFragment** eklentisine sabit HTTPS adresi gönderir. `showFragmentWhenReady(id, input)` girdiyle birlikte çağrılır; web eklentisi asenkron yüklenirken istek kaybolmaz. Eklenti kapalıysa önceki seçime/dashboard'a dönülür.
- TV, radyo, podcast, yerel medya, YouTube, VLC, ExoPlayer ve MediaSession uygulamaları pinlenmiş GPL kaynakta aynen korunur. Navigasyon Activity'si back stack'te kalır. MediaEngine oynatıcısını kokpit WebView'inin içine yeniden kurmuyoruz.
- Native Kotlin kokpitinde sabit `verifiedParked=false` kaldırıldı. `CarConnection` bağlantı durumu telefonda medya erişimini belirler: yalnızca bağlantısız telefon oturumu açılır; bağlı veya henüz bilinmeyen araç bağlantısında video izni üretilmez. Bu, GPS=0 değerini Park vitesi saymaz.
- Native kokpitte dört servis de uygulama içi WebView kullanır. Siteye ait HTTPS alanları, giriş yönlendirmeleri, Netflix masaüstü user-agent seçimi, üçüncü taraf oturum çerezleri, tam ekran/geri tuşu ve renderer kaybında yeniden deneme bağlandı.
- Korumalı medya izni yalnızca izin verilen sitenin **aynı origin** isteğine verilir; kamera/mikrofon web izinleri otomatik verilmez.
- Web medyası uygulama arka plana geçince duraklatılır; çerezler yazılır. Yeni WebView nesli bırakılırken eski neslin view referansı yok edilir, replacement view yok edilmez.
- Eski Media Hub abonelik ekranı da aynı-origin korumalı medya izni, Netflix user-agent ve oturum çerezleriyle güncellendi.
- MediaEngine companion köprüsüne `me.app.fermataX.auto.mola.debug` (4.1.10) eklendi; eski 3.2 paketi fallback olarak korunur. Companion açılışı ayrı ve isteğe bağlıdır; dahili site kartlarının yolu değildir.

## Kaynak ayrımı

Verilen APK: 4.1.10 / versionCode 433, SHA-256
`3036868e2255e30ad0d59ed497877cfc48d929122b742fb496e31de25a1c2b34`.

Mevcut MediaEngine tabanlı derleme, depoda zaten kullanılan GPL kaynak commit'ini korur:
`3f45ad3557a25822c2a3a9f152f32ef82f57e005`.
Bu kaynağın appVersionName değeri 2.2.2'dir. Dağıtım APK'sının bütün özgün kaynaklarıyla birebir eşleştiği iddia edilmez. Derlenmiş DEX veya kapalı APK dosyaları uygulamaya gömülmedi. 4.1.10 incelemesinde doğrulanan uyumlu davranışlar mevcut kaynak API'leriyle bağlandı.

Vosk modelini/bütün APK'yı kopyalamadık; bu commit yeni mikrofon izni veya offline ses tanıma modülü eklemez. Native navigasyon TTS ve mevcut MediaEngine ses özellikleri kendi kaynaklarının kapsamını korur. Harita/OSRM, OBD, enerji hesabı ve mevcut koyu kokpit tasarımı değiştirilmedi.

## Doğrulama

- `python3 media-engine/patch_mediaengine.py <pinned-mediaengine-checkout> media-engine/overlay`
- `python3 media-engine/tests/test_embedded_media.py <patched-mediaengine-checkout>`: gerçek Java router'ı derleyip üç site, disabled add-on fallback ve radyo regresyonunu çalıştırır; özgün medya motorlarının değişmediğini kontrol eder.
- `node media-engine/tests/test_mediaengine_media.js`
- `node media-engine/tests/test_external_mediaengine32.cjs`
- `gradle :nativecockpit:testDebugUnitTest :nativecockpit:assembleDebug`
- `gradle :app:testDebugUnitTest :app:assembleDebug`
- Mevcut MediaEngine workflow: kaynak hash/manifest/harita/OBD/kokpit regresyonları ve tam APK.

`MediaSitePolicyTest` ve `SubscriptionWebPermissionsTest`: giriş adresleri, kandırıcı alan adları, userinfo/port/scheme reddi ve exact-origin medya izni.

## Cihaz kabulü

Her serviste giriş → gerçek oynatma → tam ekran → geri → uygulamayı kapat/aç → tekrar giriş gerekip gerekmediği ayrı denenmeli. Ana sayfanın yüklenmesi DRM oynatmanın başarılı olduğunu kanıtlamaz. Abonelik, Widevine, WebView ve servis politikaları cihazda sonucu değiştirebilir.

Release imzalama mevcut kalıcı owner secret üzerinden devam eder. Yeni imzalama anahtarı üretilmez; secret yoksa debug test APK ile signed release birbirine karıştırılmaz. Bu commit gerçek cihazdaki eski release çökmesinin çözüldüğünü tek başına kanıtlamaz.
