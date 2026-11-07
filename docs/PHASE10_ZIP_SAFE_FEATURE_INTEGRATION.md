# FAZ 10 — Kullanıcının ZIP prototipinden EV Cockpit'e güvenli özellik aktarımı

**Kaynak:** 8 Kasım 2026'da kullanıcının yeniden yüklediği `IdYolArkadasi.zip`; SHA-256:
`9f66d0b1302789b7377ae82b94545daad198a02003c69625f7cf5ab4e413e62a`.

Bu ZIP'in README'si **"ID.4 Yol Arkadaşı v0.2 (derlenmemiş)"** başlığını taşıyor. Arşivde 8 dosya var; APK, imzalı sürüm, Gradle Wrapper, Android Auto Car App Service veya üretim testleri yok. Bu, derlenmiş/çalışır kod aktarımı değil, **özellik ve kaynak bilgisi aktarımıdır**.

## Yeni uygulanan işlevler

### 1. ZIP ID.4 Pure referansı → Ayarlar içinde elle alınan taslak

Yeni `ZipId4PureReference` prototipteki **beyan edilen** değerleri ve kaynağın SHA-256'sını taşır:

| Alan | ZIP'teki iddia | Üretim statüsü |
|---|---|---|
| Araç | Volkswagen ID.4 Pure | Kullanıcı aracı/modeli, bağımsız fabrikasyon teyit değil |
| Motor | 125 kW / 170 PS | Referans metni; OBD'den ölçülmüyor |
| Kullanılabilir batarya | 52 kWh | Kullanıcı/prototip referansı; gerçek SoH veya güncel kapasite değil |
| DC tepe güç | 145 kW | **Aday değer**; şasi, model yılı ve araç yazılımına göre teyit gerekli |
| AC şarj | 11 kW | **Aday değer**; araç özelinde teyit gerekli |
| WLTP | 354 / 363 km | ZIP'in iki farklı WLTP iddiası; tek resmî menzil kabul edilmez |

**Arayüz davranışı:** Ayarlar → "ZIP'deki 52 kWh / 145 kW değerlerini taslağa al" düğmesi sadece batarya ve DC metin kutularını doldurur. `stagedDraft` mevcut tüketim ve rezerv tercihini bozmaz, **`userConfirmed=false`** üretir, DataStore'a otomatik yazmaz. WLTP ve AC değerleri yalnızca referans metni olarak gösterilir; rota planlamasına, şarj eğrilerine, canlı SoC'ye veya resmî menzil gösterimine otomatik taşınmaz. Kullanıcı değerleri gözden geçirir, tüketim ve rezerv belirler, ayrı **"Araç parametrelerini kullanıcı olarak onayla"** düğmesine basarsa mevcut doğrulamalı DataStore yolu kullanılır.

**Mevcut teyitli profil otomatik değiştirilmez.** Taslak alınması mevcut araç/rota hesaplamalarını da sessizce yeniden hesaplamaz.

### 2. Kişisel yaz / kış tüketim senaryoları

Bunlar **ZIP'den alınmış ölçüm değildir**; kullanıcı önceki görüşmelerde bildirmiştir:
- Yaz: 10–11 kWh/100 km → düzenlenebilir örnek orta değer 10,5
- Kış: 17–19 kWh/100 km → düzenlenebilir örnek orta değer 18,0

`UserDrivingScenarios` bunları **tüketim senaryosu** olarak saklar. Arayüzde "Yaz · 10,5" ve "Kış · 18,0" düğmeleri tüketim **giriş alanını** günceller; SoC/BMS kaynağı oluşturmaz, kayıt için ayrıca kullanıcı onayı ister. Bu örneklerden menzil ancak kullanıcı teyitli profil ve ayrıca gerçek, kanıtlı BMS SoC mevcutsa model olarak hesaplanabilir.

### 3. ZIP'in Spotify / YouTube Music kısayolları

Mevcut **Medya → Müzik** sekmesinde yeni `OfficialMusicApp` kısa yolları:
- `com.spotify.music` → telefonda yüklü resmî Spotify
- `com.google.android.apps.youtube.music` → telefonda yüklü resmî YouTube Music

Kısıtlı `<queries>` manifest izinleri ile paket görünürlüğü sağlanır; uygulama yüklü değilse açık Türkçe mesaj verilir. **Mağaza/izinsiz URL fallback, hesap çalma, DRM çözme veya Android Auto'ya video/oynatıcı taşıma yoktur.** Dahili `InternalMediaPlayer` ve `PhoneVideoSafetyPolicy` değiştirilmeden kalır. Bu kısayolların çalışması ilgili uygulamanın gerçekten yüklü ve Android'in başlamasına izin veriyor olmasını gerektirir.

## ZIP'in diğer özellikleri: var olan karşılıklar

| ZIP prototipindeki fikir | EV Cockpit'teki karşılık | Karar |
|---|---|---|
| Tesla tarzı koyu, geniş kokpit ve büyük hız | `CockpitScreen`, `MapLibreCockpit` | Mevcut UI korunur; ZIP'in eski Compose ekranı aktarılmaz |
| GPS ve bir sonraki manevra; 650 m/90 m TTS | `NavigationViewModel`, `ManeuverGuidancePolicy` | Yalnızca gerçek GPS + OSRM çizgisinde yönlendirme |
| Yaklaşık 40 km rota hava noktaları | `RouteForecastPolicy` | Mevcut; en fazla 8 konum; uzun rotalarda aralık büyür |
| Geçiş saatine göre GMT/UTC hava | `HourlyForecastParser`, `OpenMeteoWeatherRepository` | Mevcut; FAZ 8 ETA sapması 45 dk sınırı ve geçilmiş risk filtresi |
| 30 dakikada hava yenileme | `RouteWeatherViewModel` | Mevcut, yalnızca aktif ekranda/ön planda; arka plan garanti edilmez |
| Ücretsiz şarj istasyonu bulma, eksik fiyat | EPDK/operatör istasyonları + OSRM sapması | ZIP'in OCM API anahtarını/ücret tahminini aktarmaz; fiyat bilinmiyorsa boş |
| Hıza bağlı V8/elektrik/türbin sesi | `ActiveSoundManager`, `EngineMotionPolicy` | Mevcut Media3, gerçek taze OBD hızı, ses odağı |
| Netflix/Disney/Prime/YouTube medya | `MediaHomeScreen` + telefon park güvenlik kapısı | Mevcut; Android Auto navigasyon şablonunda video yok |
| Bluetooth bağlanınca karşılama | `WelcomeGreetingManager` ve gerçek OBD/AA oturumu | Mevcut; Bluetooth ACL_CONNECTED, kontak açılması sayılmaz |

## Açıkça aktarılmayan güvenlik riskleri

- `Hub.soc = 80f` / menzili otomatik gerçekmiş gibi gösteren formül;
- e-Golf/MQB kaynaklı `028C` ve `Obd.value(b,1,1,2.5)` ile **MEB** BMS yanıtını SoC'ye çevirmek;
- `passenger` "Yolcu modu" ile hareket hâlinde video kısıtlamasını aşmak;
- GPS hızı yokken `0f` kabul ederek park kanıtı çıkarmak;
- "test hızı" değerini gerçek telemetriye yazmak;
- Serbest biçimli ELM header/filter/AT komutlarını sokete göndermek;
- `router.project-osrm.org` genel demo adresini üretim yönlendirmesi yapmak;
- `weatherAt()` eşleşmeyen ETA saatinde `?:0` ile ilk hava tahminine dönmek;
- Ham API anahtarını URL'ye eklemek; dışa açık Bluetooth `GreetReceiver`.

## Kabul, dağıtım ve yapılmayanlar

- `ZipId4PureReferenceTest`: tam SHA/kaynak bilgisi, 52/145/11 ve WLTP referansı, varsayılan ve önceden teyitli profilin **onaysız** taslağa dönüşmesi.
- `UserDrivingScenariosTest`: yaz/kış kullanıcı senaryoları; SoC kanıtı oluşmaz.
- `test_phase10_zip_integration.py`: DataStore'a otomatik ZIP yazımı yok, resmî müzik kısayolları yalnızca `MediaHomeScreen`, video güvenliği ve ham-only MEB kuralları korunur.
- Android lint, Kotlin/JVM, APK debug derlemesi, test raporları, Gitleaks ve APK gizli anahtar taraması son commit için başarılı olmadan güvenilir APK iddia edilmez.
- **Gerçek telefon + ID.4/ELM327/Android Auto**, resmi araç teknik değerleri ve Play Store/Release imzalama doğrulaması **yapılmadı**.
- Debug paket `com.evcockpit.debug` / **EV Cockpit TEST** ayrı kurulabilir, imzalı stable Release değildir.
- `main` üzerinde eski manuel Release akışını durduracak **güvenlik PR #32** hâlâ ayrıca incelenip birleştirilmelidir.
