# FAZ 0 — doğrulanmış zemin, izinler ve güvenli entegrasyon
Tarih: 11 Kasım 2026 · Depo: AlpTimucin-cpu/ev-cockpit · DURUM: TASLAK, MERGE / RELEASE YOK

## Kanıtlı dal gerçekliği
- `main` HEAD: `80ab19471abbe8ffb33a82e240d98e426e2a4b50`.
  Git ağacı 84 giriş içeriyor. Kökte `:app` var; `:nativecockpit`,
  `:evlite` ve `media-engine/` **main'de yok**.
  Eski `EV_Cockpit_Legacy_Source_v0.8.zip` hâlâ main kökünde.
- `integration/ev-cockpit-safe-snapshot-20261109` → PR #51 → main,
  HEAD `d843e5e1cfd1b01775bfaa8ed0cada7d112e516f`.
- `feature/job3-legacy-archive-audit-20261109` → PR #52 → #51,
  HEAD `1fb8b74d071efe492030d556e9a7fda076b6ec52`.
- `feature/cockpit-adaptive-landscape-20261110` → PR #53 → #52,
  doğrulanmış öncül HEAD `90bb510a02f0845d245ab6cf7b66de35c3c99029`.
- Bu Faz 0 dalı: `phase0/permissions-repo-foundation-20261111` → PR #53,
  ayrı taslak PR. Geçmiş alternatif PR #49/#50, eski fazların tamamı
  ayrıca merge edilmez; aynı kodun tekrarı ve imza bilgisi riski vardır.

## Tek entegrasyon dalı
**Kanonik hedef**: `integration/ev-cockpit-safe-snapshot-20261109` (#51).
Sıra: PR #51 kaynak ve geçmiş güvenliği incelemesi → #52 arşiv güvenlik
kararı → #53 UX kabul → bu Faz 0 izin/zemin kabulü → sonraki ayrı faz
dalları. Sıra güvenlik nedeniyle yeniden sıralanabilir ama **main'e hiçbir
PR doğrudan yazılmaz, otomatik merge olmaz**. Birleştirme ancak kullanıcı
onayı, geçmiş sır incelemesi ve her son HEAD'de CI kanıtıyla yapılır.
Tüm dalların birlikte entegrasyonunu tek seferlik bir son kabul değişikliği
olarak planla; çakışan alternatif PR #49/#50'yi ayrıca birleştirme.

## İzin kabul politikası
- Konum: sistem isteminde **Uygulamayı kullanırken + Hassas** seçimi.
  Android 12+ `ACCESS_FINE_LOCATION` ve `ACCESS_COARSE_LOCATION`
  birlikte istenir. Sadece yaklaşık izni **Hassas** sayma. Telefonun
  konumu ayrıca açık olmalı. İzin verilmesi gerçek GPS fix kanıtı DEĞİL.
- Bluetooth: Android 12+ yalnız eşleşmiş adaptör için
  `BLUETOOTH_CONNECT`; yeni BLE taraması eklenmedi.
- Bildirim: Android 13+ `POST_NOTIFICATIONS`; red halinde
  bildirim görünmeyebilir ama FGS'nin otomatik kapandığını iddia etme.
- İlk telefon açılışında sihirbaz görünür; atlamak mümkündür,
  eksik izinlerin etkisi listelenir. Sihirbaz Ayarlar'dan tekrar açılır.
  Debug Tanılama yalnız izin durumu/etkisini yazar, **konum/VIN/MAC yok**.
- Kullanıcının OS ayarındaki 'yalnız bu kez' iznini standart permission
  API ile 'her zaman uygulama açıkken' izninden güvenilir ayıramayız.
  Uygulamayı tekrar açtığında izin durumu yeniden sorgulanmalıdır.

## README / Gradle / CI sınırı
- Bu dalda `settings.gradle.kts` üç Gradle modülünü tanımlar:
  `:app`, `:evlite`, `:nativecockpit`. `media-engine/` Gradle modülü
  değildir. **Bu iddia main dalı için geçerli değildir**.
- Android SDK 36, JDK 17, Gradle Wrapper 8.14.3 (checksum doğrulaması);
  native paket kimlikleri Debug `com.evcockpit.debug`, Release `com.evcockpit`.
- Faz 0: `./gradlew :nativecockpit:lintDebug
  :nativecockpit:testDebugUnitTest :nativecockpit:assembleDebug`.
  Ayrıca Python kaynak kontrolleri, read-only OBD/UDS testleri,
  Git patch secret scan ve APK secret scan gerekir.
- CI yeşili **cihazın gerçek fonksiyonunu kanıtlamaz**. Build sonucu
  bu PR'ın son HEAD'inde okunmadan 'geçti' yazılamaz.

## Release iskeleti (YALNIZCA CI, dağıtım yok)
- `nativecockpit/build.gradle.kts` üzerinde Release:
  `isDebuggable=false`, `isMinifyEnabled=true`,
  `isShrinkResources=true`, ABI `arm64-v8a`.
  `AndroidManifest.xml`: `allowBackup=false`,
  `usesCleartextTraffic=false`; network config HTTPS-only.
- `.github/workflows/verify-native-release-smoke.yml` *geçici*
  CI anahtarıyla Release derler ve APK'yı **yayınlamaz**.
- Gerçek `.github/workflows/release.yml` imza işi `if: false`
  durumda kalmalıdır. Eski imza anahtarı/sertifika riski çözülmeden
  gerçek Release, otomatik tag veya GitHub Release YOK.

## ZIP ve TomTom güvenlik kapıları
- `EV_Cockpit_Legacy_Source_v0.8.zip` ve
  `EV_Cockpit_v0.8_Source.zip` PR #51/#52 ağacında görünür.
  İçerik/köken/SHA-256/sır denetimi ve insan onayı olmadan
  Releases'e yükleme veya kaynak ZIP'leri silme YOK.
  Bir Releases asset'i dosyanın güvenli olduğunu göstermez.
- TomTom anahtarının **iptal/rotasyonu DOĞRULANMADI**.
  Sahip telefondan TomTom geliştirici paneline giriş yapıp eski anahtarı
  iptal etmeli; yeni anahtar üretirse kendisi saklamalı; GitHub'a,
  ChatGPT'ye, APK'ya veya WebView içine kopyalamamalıdır.
  Kota ve eski kullanım kontrolü, varsa fatura kontrolü, eskiden
  yayımlanmış APK/artifact incelemesi ve tarihi sır taraması ayrı kanıt
  gerektirir. Geçmişi force-push ile değiştirme.
- GitHub repo **description=null**, **topics=[]** durumunda
  doğrulandı. Metadata yazma yetkisi bu bağlantıda yok:
  sahibi mobil tarayıcıda Repo → About → Edit:
  açıklama 'EV Cockpit · Android EV navigation and read-only telemetry (development)'
  ve topics 'kotlin, android, jetpack-compose, maplibre, electric-vehicle'
  girebilir. Bu işlem yapılmış olarak raporlanmaz.
- Eski v1.0.0 ve v1.0.2 Releases, yeni native uygulamanın dağıtımı
  olarak **etiketlenmemelidir**.

## Mobil kabul kapısı (sahip dolduracak)
1. Telefonda Debug APK'yı Actions artifact'tan yükle.
   Beklenen: ayrı 'EV Cockpit TEST/DEBUG' başlatılır.
2. İlk açılış: 3 izin adımı görünür; konumda 'Hassas' istenir.
3. Yalnız 'Yaklaşık' ver: eksik yetki açıklaması görünür; hassas
   navigasyon kullanılıyor gibi iddia edilmez.
4. Bluetooth/bildirim reddedildiğinde her birinin etkisi yazılır.
5. 'Kısıtları kabul et' ile kokpite geç; Ayarlar →
   'Konum, Bluetooth ve bildirim izinlerini kontrol et' ile yeniden aç.
6. Debug Tanılama raporunda 'İzin etkileri' satırını doğrula;
   VIN, koordinat, MAC veya anahtar paylaşmadan rapor/fotoğraf ver.
7. Araç: yalnız park halindeyken Android Auto bağlantısı dene,
   izin davranışlarını gözle; otomatik kod testi gerçek 12,9" ekrana
   uyumluluğu kanıtlamaz.

**Kabul durumu:** Telefon doğrulanmadı · ID.4 doğrulanmadı ·
TomTom iptali doğrulanmadı · ZIP varlık güvenliği/doğrulama tamamlanmadı.
Sahibin kabulünden önce Faz 1'e geçilmez.
