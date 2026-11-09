# EV Cockpit — Car Scanner, ABRP ve MediaEngine APK teknik incelemesi
28 Ekim 2026 · Yalnız statik/izinli inceleme · Kod/DRM/veri tabanı kopyalanmadı.

## Binary olarak gözlemlenen mimari
| Uygulama | Paket | Android SDK min/hedef | Manifest izin sayısı | Görülen ana teknoloji |
| --- | --- | --- | --- | --- |
| Car Scanner ELM OBD2 2.1.50 | `com.ovz.carscanner` | 21 / 35 | 29 | .NET MAUI, Xamarin/Mono AOT, Plugin.BLE, Android Auto AACarService |
| ABRP 7.1.13 | `com.iternio.abrpapp` | 26 / 36 | 56 | React Native, Mapbox, Enode BLE, Nitro location, Android Auto |
| MediaEngine 3.2 | `me.app.fermataX.auto.mola32.debug` | 28 / 36 | 18 | VLC, ExoPlayer, MediaSession, AutoFermataMediaService, CarService, MediaProjection |

ABRP XAPK, ana APK + 19 yapılandırma paketi içerir. Android manifest izinlerinin varlığı bunların cihazda fiilen çalıştığını kanıtlamaz. APK imza/dağıtıcı kökeni bu incelemede doğrulanmadı.

## Car Scanner'dan elde edilen teknik yönlendirme
- Bluetooth Classic, BLE GATT ve opsiyonel Wi-Fi için ayrı `ObdTransport` adaptörleri tasarla. Uygulamanın mevcut eşleştirilmiş Bluetooth Classic yaklaşımı, BLE'yi otomatik olarak kapsamaz.
- Veri hattı: taşıma bağlantısı → ELM327/UDS çerçeve ayrıştırma → araç profili → fiziksel birim/ölçek doğrulama → zaman damgalı `VehicleTelemetry`.
- PID, ECU adresi, byte endianness, ölçek, birim ve geçerlilik aralığı bağımsız olarak doğrulanmalı; üreticinin veya Car Scanner'ın lisanslı PID veri tabanı çıkarılıp aktarılmamalı.
- Sahte SoH, SoC, güç ve hız göstermemek için geçersiz/kesilmiş/bayat yanıtlar reddedilmeli. Salt-okunur tanılama dışındaki araç komutları kapsama alınmamalı.
- `Plugin.BLE` kütüphane izi BLE desteğini destekler; dongle üreticisine özgü GATT UUID'leri APK'dan güvenilir biçimde genellenemez.

## ABRP'den elde edilen teknik yönlendirme
- Rota = doğrulanmış OSRM geometri + tüketim modeli + güncel SoC + yol eğimi + hava + hız + şarj gücü/eğrisi + istasyon durumu; her kaynak ve belirsizlik ayrı olmalı.
- ABRP'nin resmi dokümanında başlangıç SoC, 110 km/s referans tüketim, minimum varış SoC, şarj durakları, batarya ve hız ayarları, hava/yol koşulları, canlı veri sayılıyor.
- Özgün `EvBaselineEnergyEstimator.kt` eklendi: doğrulanmış rota ve kullanıcı tarafından belirtilen referans tüketim/kullanılabilir batarya kapasitesi ile temel enerji/varış SoC hesabı. SoC `FreshObd` veya `ExplicitAssumption` olarak açıkça ayrılır; bayat OBD reddedilir.
- Bu modül kendi başına gerçek şarj durağı optimizasyonu yapmaz. Rota, istasyon ve şarj eğrileri doğrulanmadan sonuç gerçek zamanlı ABRP eşdeğeri diye gösterilmemeli.
- ABRP resmi Telemetry/OAuth2 API'si ayrıca dokümante edilmiştir; kullanıcı izni ve API koşullarıyla ileride opsiyonel bir entegrasyon yapılabilir. APK içinden gizli API anahtarı çıkarılmaz.

## MediaEngine'dan elde edilen teknik yönlendirme
- APK içindeki AutoFermataMediaService, CarService, FullScreenAppService, ProjectionService ile VLC/ExoPlayer izleri medya oturumu ve oynatıcı sorumluluklarını ayırma yönünde kanıt sağlar.
- Halihazırdaki GPL kaynaklı Fermata tabanı korunmalı; GPL-3.0 uyumluluğu ve atıf/sunulabilir kaynak şartları incelenmeli. Yüklenen, kaynağı doğrulanmamış fork APK'sından kapalı kod kopyalanmaz.
- Android Auto/Automotive park ve sürücü dikkat kuralları korunmalı. MediaProjection sistem kullanıcı onayı ister; DRM ve sürüş kısıtlamasını aşmaz.
- Netflix/Disney+/Prime için başka uygulamanın video karesini kendi uygulamamıza zorla yerleştirmek desteklenen PiP/Intent entegrasyonu değildir. Kendi medya servisinin ses odağı, geri yükleme, altyazı ve oynatıcı değişimi test edilmeli.

## Bizim uygulama için önceliklendirme
1. **OBD güvenilirliği:** Classic Bluetooth bağlantısı ve salt-okunur MEB decoder'ları doğrula. BLE için adapter-specific bağlantı modülü ve gerçek cihaz testi ekle.
2. **Enerji:** `EvBaselineEnergyEstimator` testlerini çalıştır; 80/100 SoC varsayımı ile gerçek OBD'yi farklı etiketle. Üretim UI entegrasyonu ve dinamik yeniden planlama daha sonra.
3. **Şarj:** EPDK kayıtlarının tazeliği ve CCS filtreleriyle doğrulanmış güç değerlerini kullan; şarj eğrisi olmadan şarj süresi uydurma.
4. **Medya:** Mevcut MediaEngine oynatıcı dosyalarını bozmadan lisansı açık modüllerden devam et; hareket kilidi ve DRM atlatma kodu ekleme.
5. **Test:** OBD zaman aşımı, yanlış PID, bayat GPS/SoC, 13k+ istasyon, Android Auto medya ve rota sahipliği, APK imza doğrulama.

## Sınırlar
Bu sonuçlar APK statik manifest/kütüphane/dize analizidir; uygulamaların gizli algoritmaları, tam PID formülleri veya kullanım hakları çıkarılmadı. APK çalıştırma ya da hesap/ağ trafiği müdahalesi yapılmadı.

Kaynaklar:
- https://abrp.featurebase.app/help
- https://abrp.featurebase.app/help/articles/3055653-link-your-vehicle-for-live-data
- https://web.abetterrouteplanner.com/resources/api
- https://github.com/AndreyPavlenko/Fermata
- https://developer.android.com/training/cars/parked
