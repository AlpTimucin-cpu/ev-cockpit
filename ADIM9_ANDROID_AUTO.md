# EV Cockpit — Adım 9: Android Auto navigasyon iskeleti

Bu adım `:nativecockpit` modülüne Android for Cars App Library **1.7.0** (kararlı) ile Android Auto için `CarAppService` ekler; telefonun mevcut Kotlin/Compose/MapLibre OSRM/Photon/EPDK işlevleri değiştirilmez.

## Eklenenler

- `com.google.android.gms.car.application` ve `res/xml/automotive_app_desc.xml` (template)
- `CarAppService` — `NAVIGATION` kategorisi, yalnızca izinli hostlar (debug'da emülatör/DHU için tüm hostlar)
- Car App API 7+ `MapWithContentTemplate`, düşük sürümde `PaneTemplate` geri dönüşü
- Android Auto hostunun verdiği Surface üzerinde `VirtualDisplay` + `Presentation` + **ayrı** MapLibre `MapView`. Host Surface olmadığında harita oluşturulmaz.
- **Gerçek** OSRM `RouteRepository.currentRoute` geometrisi ve yaşam döngüsü boyunca gerçek Fused GPS koordinatı. Sahte pil/GPS/ETA oluşturulmaz.
- Oturum kapanınca GPS dinleyicileri, map view ve virtual display temizlenir.
- Mesafe ve GPS durumu için JVM birim testleri.

## Sınırlar

Bu bir **Android Auto entegrasyon temeli**, tamamlanmış ve Google Play onayından geçmiş navigasyon değildir. Android Auto hostu, Play dağıtımını ve uygulama kalite gereklerini ayrıca denetler. `Allow unknown sources` ayarı Car App Library uygulamalarını genel olarak kapsamaz. DHU, Google Play Internal Testing / Internal App Sharing ve gerçek araç testi gerekir. OSRM seçili rota yalnız uygulama işlemi belleğinde tutulur; uygulama işlemi yeniden başlatılırsa rota yeni baştan oluşturulmalıdır. Araç ekranında rota oluşturma/arama, `NavigationManager` Trip bildirimleri, voice-on-car ve otomatik yeniden rota hesaplama henüz tamamlanmadı.

## Derleme

```bash
gradle :nativecockpit:testDebugUnitTest :nativecockpit:assembleDebug
```

## Kaynaklar

- https://developer.android.com/training/cars/apps/library/set-up-project
- https://developer.android.com/training/cars/apps/navigation
- https://developer.android.com/training/cars/apps/library/draw-maps
- https://developer.android.com/training/cars/testing
