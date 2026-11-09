# EV Cockpit — FAZ 2: Navigasyon ve Türkçe sesli yönlendirme

7 Kasım 2026 · Durum: taslak PR; gerçek cihaz sürüş testi yapılmadı. FAZ 2 dışında özellik eklenmedi.

## 1. Kanıtlanan başlangıç noktası
- Taban: FAZ 1 PR #26 dalı; gerçek Gradle hedefi `:nativecockpit`, `main` değil.
- Kaynakta zaten OSRM GeoJSON, adım manevraları, Fused Location, MapLibre, TTS ve AudioFocus vardı. Bunlar yeniden yazılmadı; doğrulanmış eksikler ele alındı.
- OSRM adresi FAZ 1'de kullanıcı yapılandırmalı HTTPS sunucudur; public demo üretimde kullanılmaz. OSRM `steps=true`, `geometries=geojson` kullanımı korunur.

## 2. Yapılan işler
- `OsrmResponseParser.kt`: OSRM'nin gerçek `type`/`modifier`/`name` alanlarından doğrudan Türkçe manevra metni üretilir. Desteklenmeyen manevra için talimat uydurulmaz.
- `ManeuverGuidancePolicy.kt`: yaklaşık 650 m ve 90 m aşamalarında en fazla birer sesli uyarı. Her aşama yalnızca gerçek, taze GPS'e ve TTS onStart onayına dayanır. Hatalı TTS sonraki GPS ölçümünde tekrar denenebilir.
- `RouteProgressTracker.kt`: ±80 rota segmentli pencere araması; GPS sıçramasında 150 m'den büyük ayrışma algılanırsa tüm GeoJSON'da ikinci arama. Rota dışındaki konumlar 35 m içi gibi rota üstüne oturtulmaz.
- `RouteDeviationPolicy.kt`: doğrulanmış `GPS` hassasiyeti ≤35 m iken, birbirinden farklı beş ardışık GPS ölçümünde orijinal OSRM hattından >150 m sapma olursa tek otomatik yeniden rota çağrısı; route ID değişince yeniden hazırlanır.
- `NavigationViewModel.kt`: yalnızca telefon/ön planda ve Android Auto navigasyon sahibi değilken yeniden hesaplar. Eşzamanlı istekler `planningJob` ve coroutine iptaliyle korunur; hata görünür kalır. Seçilmiş şarj durakları var ise durakların yanlışlıkla silinmemesi/geçmiş istasyona geri dönülmemesi için otomatik rota yerine açık uyarı verir.
- `NativeVoiceGuide.kt`: ayarlardan seçilen gerçekten kurulu tr-TR ses ve konuşma hızı navigasyona uygulanır. Ses odağı reddedilince TTS konuşması başlamaz; mevcut AppAudioDucking ses sürerken radyo/sentetik iç ses düzeyini geri indirip konuşma bitince normalleştirir.
- `NominatimGeocoder.kt`: yalnızca kullanıcı arama düğmesine bastığında istek; Nominatim public politika gereği en fazla 1 istek/sn, tanımlayıcı User-Agent, istek serileştirme ve 24 saatlik bellek önbelleği. Yanıt boyutu 300 KB ile sınırlı, geçersiz koordinatlar reddedilir.
- `RoutePlannerDialog.kt`, `CockpitScreen.kt`, `MainActivity.kt`: başlangıç ve varış aramaları açık düğmelerle başlatılır; tuşa basıldıkça Nominatim sorgusu oluşturulmaz. Daha önce kullanıcı tarafından Photon URL'si kaydedilmişse mevcut Photon yolu korunur.
- `AppConfiguration.kt`, `AppConfigRepository.kt`: yeni kurulumdaki geocoding kökü Nominatim; sağlayıcı URL'si kullanıcı tarafından değiştirilebilir.

## 3. Testler
- Manevra Türkçesi: OSRM sağ/sol dönüş, dönel kavşak ve bilinmeyen manevra.
- 650/90 m: tek kademe duyurusu, yinelenen GPS'te konuşmama, TTS onStart olmadan aşamayı tüketmeme, stale GPS ve eski route tokenlarını yok sayma.
- Sapma: gerçek olmayan sadece sentetik JVM GPS verisinde 5 ardışık >150 m; aynı rota için tek tetikleme, araya giren rota-üstü ölçümle sıfırlama, eski/hassas olmayan GPS'le tetiklenmeme, rota değişince yeniden hazırlık.
- Pencere araması: uzun geometri üzerinde konum sıçraması sonrası tam geometriye güvenli geri dönüş.
- Nominatim: JSON alanları ve geçerli koordinatlar; MockWebServer ile `User-Agent`, `format=jsonv2`, aynı sorgunun önbellekten dönüşü.
- Mevcut `AudioFocusLeaseTest` ve `AppAudioDuckingTest`: TTS odak ve uygulama içi ses arbitrasyonu regresyonu.

## 4. Doğrulama komutları
    git switch feature/phase2-navigation-voice-20261107
    ./gradlew --version
    ./gradlew projects
    ./gradlew :nativecockpit:lintDebug :nativecockpit:testDebugUnitTest :nativecockpit:assembleDebug --no-daemon --stacktrace
    python3 -m unittest discover -s tests -p 'test_native_phase0_policy.py' -v
    python3 -m unittest discover -s tests -p 'test_apk_secret_scan.py' -v
    python3 scripts/check_apk_secrets.py nativecockpit/build/outputs/apk/debug/nativecockpit-debug.apk

## 5. Sınırlar, riskler ve geri alma
- Nominatim public kullanımının arayüzden otomatik tamamlama sağlaması yasak; açık arama ve tanımlayıcı UA kullanılıyor. Bir saniyelik tavan işlem başına uygulanan kilittir; gerçek dağıtımın toplu yükü ve şartları ayrıca incelenmeli. Sağlayıcı kendi sunucusuyla değiştirilebilir.
- Rota dışına çıkma eşiği bir sürücü yardım kestirimidir; GPS hatası, paralel yollar, tünel ve çok katlı bağlantılar için gerçek cihaz UAT gereklidir. Trafik veya kavşak bilgisi uydurulmaz.
- Android Auto kendi navigasyon sahibi olduğunda telefon otomatik reroute başlatmaz. AA şablon/testleri ayrı FAZ 7 kapsamındadır.
- Onaylı şarj durağı olan rota otomatik yeniden planlanmaz; kullanıcı durakları incelemelidir. Geçmiş durağa geri yönlendirmeme güvenlik tercihidir.
- Ses cinsiyeti Android'de güvenilir bir öz nitelik değildir. Ses seçim ekranı yalnızca mevcut kurulu Türkçe ses adlarını gösterir. Ses yoksa uyarı; odak yoksa sessizlik.
- Hiçbir BMS SoC DID/CAN adresi uydurulmadı, OBD yazma eklenmedi. 52 kWh/tepe DC gücü kullanıcı teyidi olmadan otomatik ölçüm sayılamaz.
- İmzalı Release ve Android Auto/ID.4 cihaz testi gerçekleştirilmedi. `minSdk=28`; API26 uyumu ayrıca test edilmeli.
- Geri alma: PR'ı kapatmak/değişiklik dalını bırakmak `main` ve taban dallarını etkilemez. Birleştirmeden sonra ayrı `git revert` PR.

## 6. Sahip işleri
1. Kendi HTTPS OSRM adresini ayarla ve yol çizimlerini gerçek rota üzerinde kontrol et.
2. Android telefonun kurulu Türkçe TTS sesini seç; sesli uyarıların 650/90 m davranışını güvenli ve durarak test et.
3. Nominatim/OSM attribution ve kullanım koşullarını koru; API 1/s talebini aşan dağıtımda kendi geocoding sunucuna geç.
4. ID.4 Pure ve ELM327/AA testlerini mahremiyet koruyan güncel cihaz kayıtlarıyla ayrıca doğrula; eski TomTom/keystore olayını kapat.

## 7. Çıkış koşulu
Bu faz yalnızca aynı commit'te Android lint, JVM testleri, gerçek `:nativecockpit:assembleDebug`, Gitleaks ve APK içi gizli bilgi taraması yeşilse kod kalite kapısından geçmiş sayılır. Fiziksel cihaz deneyimi ayrı.
