# EV Cockpit — Adım 13: Uçtan uca entegrasyon

**Kaynak:** GitHub `AlpTimucin-cpu/ID4-Media-Hub`, `feature/ev-cockpit`, 12. adımın kaynak paketi üzerine değişiklikler.

## Veri akışı

`RoutePlannerDialog -> NavigationViewModel.edit*/select*/plan -> OsrmRouteRepository.calculate -> StateFlow<RouteSnapshot> -> NavigationViewModel.uiState.cockpit.route -> CockpitScreen/MapLibreCockpit`.

`RouteRepository` Hilt ile `@Singleton` bağlandığından Android Auto `EyaCarNavigationScreen` de aynı seçilmiş rotayı okur. `NavigationViewModel` `_planner` verisini ve araç/GPS/rota/voice durumunu tek `uiState` altında sunar. Mevcut `ChargingViewModel` ve OBD kontrolcüsü kendi kaynakları üzerinde kalır; Android Auto ve EPDK kodları silinmedi.

## OSRM formatı

Servis URL'si: `https://router.project-osrm.org/route/v1/driving/34.123000,37.456000;30.789000,36.123000?alternatives=6&steps=true&geometries=geojson&overview=full`

Dikkat: İlk sayı boylam, ikinci sayı enlem. `Locale.US` kullanılır; çift arasındaki `;` URL path'inde korunur. HTTP 400/429/5xx döndüğünde `OsrmResponseParser` çağrılmaz. Hatalar `RouteCalculationResult.Failure(code, message)` ile UI'a taşınır. OSRM demo sunucusu kesintisiz servis garantisi vermez; 400'ün başka nedenleri de olabilir. `400` kodu otomatik olarak koordinat sırası hatası kanıtı değildir.

## Foreground service kararı

Bu entegrasyon **telefon uygulaması açıkken** GPS/TTS/OBD kullanır; Android Auto ekranının ayrı `CarAppService` yaşam döngüsü korunur. Çalışmayan bir arka plan servisi tanımlanmadı. Bu yüzden Manifest'e `FOREGROUND_SERVICE_LOCATION` izni veya `foregroundServiceType=location` eklenmedi. Arka planda bağımsız sesli navigasyon için Android 14+ gereksinimlerine uyan gerçek bir notification + servis + izin akışı ayrı cihaz testleriyle uygulanmalıdır. `POST_NOTIFICATIONS` Manifest'e eklendi ama henüz bildirim gönderilmediği için çalışma anında istenmiyor.

## Gradle

```powershell
# Windows, Android SDK yüklü, Gradle PATH'te veya proje gradlew mevcut
.\gradlew.bat :nativecockpit:testDebugUnitTest :nativecockpit:assembleDebug
# veya Github Actions üzerinden build-nativecockpit.yml
```

## Telefon test planı

1. Önceki APK ile aynı paket kimliği altında debug imzası uyumluysa üzerine kur. Aksi halde uygulamanın kendi verilerini yedekle ve eski debug'u kaldır.
2. Android cihazın konumunu ve internet erişimini aç. Uygulama açıldığında konum iznini (en az yaklaşık konum) ver.
3. Rota Oluştur > Nereden: **Niğde** yaz, Photon önerilerinden şehri *dokunarak seç*. Nereye: **Antalya** yazıp önerilerden birini seç. Yazılmış metin tek başına seçim sayılmaz.
4. Her iki seçimin geçerli koordinatı olunca **Gerçek yol rotasını hesapla** aktif olur. Bas.
5. İnternet ve OSRM uygunsa alternatifler ekranda belirir, MapLibre güzergâhı çizer, ana NAVİGASYON paneli hedefi/toplam mesafeyi gösterir. Trafiksiz tahmin etiketlidir.
6. Paneli kapatıp ana NAVİGASYON kartında hedefin ve toplam mesafenin olduğunu gör. Hareket/kalan mesafe/sesli dönüş yalnız gerçek, güncel ve rota üzerinde GPS ile güncellenir; araç park halindeyken sahte ilerleme bekleme.
7. Alternatif rotayı değiştir: haritada çizgi ve rota verilerinin birlikte değiştiğini doğrula.
8. İnterneti kapat ve farklı hedef ara: Photon bağlantı hatası görmelisin. Sunucu HTTP 400/500 verirse rota hatası UI'da koduyla görülmeli.
9. Klavyeyi aç; Dialog içinde alanlar kaydırılmalı, **Gerçek yol rotasını hesapla** butonu erişilebilir kalmalı.
10. **SES -> Türkçe ses testi** ile TTS motorunu kontrol et. GPS/gerçek rota manevrası yokken otomatik sesli anons bekleme.
11. Android Auto simgesi için gerçek DHU ve Google Play test dağıtımı doğrulaması ayrıca gerekli. Gerçek sürüş testlerini güvenli koşullarda yap.

## Derlemede kullanılan bağımlılıklar

Depoda başarılı derlenmiş sürümün sabitlenmiş sürümleri korunmuştur. Android Gradle Plugin, Kotlin plugin, Compose BOM, MapLibre, Retrofit, Gson converter, OkHttp, Car App Library, Hilt ve Android Play Location bağımlılıkları `build.gradle.kts` içindedir. Kararsız “en yeni” sürümleri karıştırmak yerine önce tekrarlanabilir bir derleme hedeflenmiştir.
