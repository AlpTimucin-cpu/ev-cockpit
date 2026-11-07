# EV Cockpit — “Rota hesaplanamadı (OSRM)” ekran görüntüsü: neden ve düzeltme

**Kullanıcı tarafından bildirilen ekran:** 8 Kasım 2026, 02.03 TSİ, yatay Android/EV Cockpit TEST uygulaması; kullanıcı başlangıç/varış konumu için **“Gerçek yol rotasını hesapla”** düğmesine bastığında rota yerine `“Araç veya hizmet ayarları kullanıcı tarafından teyit edilmedi. Kendi HTTPS OSRM sunucunuzu ...”` uyarısı görülüyor.

## Koddan doğrulanmış kök neden

1. `OsrmRouteRepository.calculateVia()` içinde `configuration.first().endpoints.osrmBaseUrl` **null** ise, daha OSRM HTTP çağrısı yapılmadan `RouteCalculationResult.Failure` dönüyor. OSRM servisinin, GPS'in veya MapLibre'ın bozuk olduğuna ilişkin herhangi bir kanıt yok.
2. Bu dal eski `CoreIssue.CONFIG_UNCONFIRMED.messageTr` metnini kullandığından “araç veya hizmet” ifadesi görünüyor. **Yol güzergâhının hesaplanması için batarya kapasitesi, BMS SoC veya EV profili onayı gerekmiyor**. Enerji/şarj tahminleri ayrı `RemainingRouteEnergyPolicy` ve `VerifiedBatterySocPolicy` kurallarında kalmalı.
3. `ServiceEndpointConfig` özel HTTPS OSRM adresi **null** ile başlıyor. `SettingsScreen`'den kullanıcıya ait bir OSRM sunucusu girilmesi isteniyordu, ancak “Hedefler” penceresinde doğrudan **doğru ayarlara git** eylemi yoktu; ayrıca Cockpit ana alt ayarlar simgesi geçmişte `ObdSettingsDialog` açıyor, `SettingsScreen`'e farklı bir giriş gerekiyor.
4. `NavigationViewModel.plan()` hata metnini `take(90)` ile kestiğinden ekran görüntüsündeki cümle sonu yarıda kalıyor. Bu bir ağ timeout'u değil, kesilmiş ön-koşul hatası.
5. `RoutePlannerDialog` uzun, kaydırılabilir alt sayfa kullanıyor; ekran görüntüsünün alt kısma kaydırılması başka alanların görünmemesine sebep olabilir. Bu durum HTTP isteğinin neden atlanmadığını değiştirmez.

## Yapılan kalıcı düzeltmeler

- `RoutingEndpointPolicy.choose()` araç bataryasından bağımsız OSRM hizmetini seçer. Geçerli **özel HTTPS** servis ayarlandıysa her zaman önceliklidir. Yoksa:
  - **Üretim Release sürümünde:** rota servisi ayarlanmadı; özel/güvenilir HTTPS OSRM sunucusu gereklidir.
  - **Debug TEST sürümünde ve açık kullanıcı onayıyla:** `https://router.project-osrm.org/` yalnızca sınırlı **kişisel test** için seçilebilir. Varsayılan **kapalıdır**; özel URL alanına demo adresi kaydedilemez.
- `AppConfigRepository.saveDemoRoutingConsent` ve `SettingsViewModel.setDebugDemoRoutingConsent` kullanıcı onayını saklar; `BuildConfig.DEBUG` değilse saklama ve kullanma reddedilir. Release modunda saklı eski onay bile otomatik etkisizdir.
- Ayarlar'da test sunucusuna **başlangıç, hedef ve durak koordinatlarının gönderileceği**, canlı trafik ve uptime garantisi olmadığı belirtilir.
- Tek paylaşımlı `OsrmDemoRateLimiter` bütün OSRM çağrılarını ve retry denemelerini 1200 ms minimum aralıkla serileştirir. Demo isteklerinde proje kimlikli User-Agent kullanılır; HTTP ve farklı servise redirect engellenir.
- TEST demo için sürüş sırasında **otomatik reroute yoktur**. EPDK adaylarından 6 istasyon için otomatik yol sapma taraması yoktur. Kullanıcı bir durağı seçip **elle** yeniden rota hesaplayabilir. Gerçek/güvenilir özel OSRM için önceki davranış korunur.
- `RoutePlannerDialog` servis eksikliğini eksiksiz gösterir ve **“OSRM / rota servisi ayarlarını aç”** eylemi eklenir. Bu düğme ana uygulamanın gerçek `SettingsScreen` paneline gider, OBD ayarlarına değil.
- `NavigationViewModel` artık kurulum talimatını `take(90)` ile yarım kesmez. Harita çizimi yalnızca doğrulanmış gerçek yol geometrisi alınırsa yapılır. Başarısız rota, sahte düz çizgiye dönüşmez.

## Çalıştırma ve kabul senaryosu

**Mevcut/önceki APK:** Uygulama `Ayarlar → Araç ve Servis Ayarları → HTTPS OSRM` bölümünde kendi güvenilir HTTPS OSRM sunucusu yapılandırılmadıysa bu hatayı vermesi beklenir. Batarya profili girmek rota problemi için çözüm değildir. Sahte URL, dış servis API anahtarı veya `http://` girmek de çözüm değildir.

**Yeni FAZ 11 Debug APK:** Hedefler → rota hesapla → kurulum hatası görünürse **OSRM / rota servisi ayarlarını aç** → **“Sınırlı OSRM deneme rotası (TEST)”** anahtarını, koordinat paylaşımı açıklamasını okuyarak aç → ayar panelini kapat → Hedefler'e dön → başlangıç ve varışı seç → **Gerçek yol rotasını hesapla**. Sunucu o sırada çalışıyorsa ve kullanımı sınırlamıyorsa doğrulanmış yol geometrisi/alternatif rotalar gelir. Bu testin geçmesi **gerçek Android Auto/car head-unit onayı, trafik bilgisi, sürüş güvenliği veya BMS/şarj verisi** doğrulaması anlamına gelmez.

**Önemli:** OSRM public demo sunucusu, FOSSGIS'in gönüllü/paylaşımlı hizmetidir; kullanımı makul ve kişisel tutmak gerekir, maksimum 1 istek/saniye kılavuzu vardır; kesintisiz hizmet garantisi yoktur. Güncel resmî kaynaklar:
- https://github.com/Project-OSRM/osrm-backend/wiki/Demo-server
- https://github.com/Project-OSRM/osrm-backend/wiki/Api-usage-policy

## Sürümleme ve sınırlar

Bu dal yalnızca üst üste geliştirilmiş **FAZ 10 `feature/phase10-zip-pure-profile-20261108`** temelinden türetilen taslak PR'dır; varsayılan `main` değiştirilmez. Kullanıcı telefonda Debug test APK kurabilir, ancak her Actions koşusunda debug imzası değişebileceği için eski TEST uygulamasını kaldırmak gerekebilir. Uygulamayı kaldırmak yerel ayarları silebilir; bağlantı/OSRM onayı yeniden yapılmalıdır.

Araç/GPS/ELM327 ve Android Auto fiziksel denemeleri, OSRM'nin gerçek telefondaki ağ erişimi ve karayolu hesaplarının doğruluğu ayrıca doğrulanmalıdır. Eski `main` Release akışını durdurmayı hedefleyen güvenlik PR #32 hâlâ ayrıca incelenip birleştirilmelidir.
