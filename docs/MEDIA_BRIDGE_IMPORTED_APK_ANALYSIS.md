# FermataX — Kullanıcının yüklediği APK'nın statik analizi

**Tarih:** 12 Ekim 2026. **Kaynak:** Kullanıcının doğrudan yüklediği `FermataX-yeni-arayuz-debug.apk`.
APK çalıştırılmadı, kurulmadı veya yürütülebilir kodu değerlendirilmedi. ZIP ve Android binary manifest çözümlendi.

| Doğrulanan alan | Sonuç |
|---|---|
| APK boyutu | 159.752.312 bayt |
| SHA-256 | `56cc317e17590c0bcedac1c0cc92f845089641d28dc506913b226e66560d49bb` |
| Paket adı | `me.app.fermataX.auto.debug` |
| Sürüm | 2.2.2 (323) |
| min / target SDK | 28 / 36 |
| DEX dosyaları | 12 |
| Android bileşenleri | 8 activity, 7 service, 5 provider, 5 receiver |
| Yerel oynatma | LibVLC — arm64-v8a, armeabi-v7a, x86_64 |

Manifest içerisinde `CarService`, `AutoFermataMediaService` ve `ProjectionService` bulunuyor. Bu, gerçek araçta görüntünün açıldığını kanıtlamaz. **`MirrorService` bileşeni bu debug APK manifestinde görünmüyor**; Github'daki başka bir kaynak sürümünde bulunması farkı ortadan kaldırmaz.

Önemli bildirilmiş izinler: `INTERNET`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `REQUEST_INSTALL_PACKAGES`, `RECEIVE_BOOT_COMPLETED`, `POST_NOTIFICATIONS`, `WAKE_LOCK`, TV EPG read/write. Bu izinlerin varlığı tek başına kullanım veya zararlılık kanıtı değildir; yüklenmeden önce kaynak ve imzanın doğrulanması gerekir.

## Kaynak eşleşme sınırı

12 Ekim 2026'da `chuoinho/FermataX` deposunun `gradle/libs.versions.toml` kaynağında sürüm **2.0.1 (308)** iken APK sürümü **2.2.2 (323)**. Bu fark yüzünden depodaki herhangi bir source dosyasının bu derlemenin birebir karşılığı olduğu kanıtlanmadı. Kaynak: https://github.com/chuoinho/FermataX

## Media Hub'a somut aktarım

- Media Hub **Ortak Entegrasyon Merkezi** FermataX'in kararlı ve debug paketlerini algılar, yalnızca kuruluysa uygulamayı telefonda açar.
- Media3 oynatıcı için **HLS/MPEG-DASH** ayırt etme ve `media3-exoplayer-dash` modülü eklendi. FermataX LibVLC binary'si gömülmedi.
- GPL-3.0 lisanslı FermataX/Fermata bileşenleri, uygun dağıtım ve kaynak kodu yükümlülükleri çözülmeden Media Hub'a kopyalanmadı.
- Sürüş sırasında sürücü ön ekran video güvenlik kilidi veya Netflix DRM mekanizmaları değiştirilmedi.

## Video bağlantısı

Kullanıcının paylaştığı `https://youtu.be/CdU0OlLds2k` adresine bu oturumdaki video kaynağı aracı erişemedi. İçeriği seyredilmiş gibi yorumlanamaz.
