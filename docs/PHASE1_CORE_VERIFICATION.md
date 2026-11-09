# EV Cockpit — FAZ 1 çekirdek doğrulaması

7 Kasım 2026. Kapsam yalnızca FAZ 1. Bu PR gerçek native kokpit için FAZ 0 taslak PR #25 üzerine kuruludur. main dalı değiştirilmedi.

## Mimari
Mevcut Compose → ViewModel / StateFlow → Repository → Fused GPS, Bluetooth Classic ELM327 ve Retrofit HTTP katmanları korunmuştur; ikinci bir navigation owner veya GlobalScope oluşturulmadı.

- domain/model/CoreEvidence.kt: nullable olmayan değer + kaynak + zaman damgası + kanıt sınıfı. Ölçüm, sağlayıcı tahmini, model tahmini ve manuel senaryo ayrıştırılır; TTL ve gelecek zaman kontrolü bulunur.
- domain/model/AppConfiguration.kt: VehicleProfileConfig başlangıçta bilinmeyen değerlerle gelir; kullanıcı net kapasite, DC gücü, tüketim ve rezervi açıkça onaylar. 52 kWh ve 110–145 kW değerleri kodda araç gerçeği olarak sabit değildir.
- data/settings/AppConfigRepository.kt: araç parametreleri ve HTTPS servis kökleri DataStore ile korunur. Bozuk yapılandırma ölçüm yaratmaz.
- presentation/settings/SettingsViewModel.kt ve SettingsScreen.kt: araç senaryo değerlerini kullanıcı girip onaylayabilir; ağ servis köklerini yönetebilir.
- presentation/navigation/NavigationViewModel.kt: gizli/shared preferences yerine kullanıcı onaylı DataStore enerji ayarlarını izler. Gerçek ve taze BMS SoC hâlâ şarttır; doğrulanmamış değerlerden menzil/varış yüzde üretilmez.
- data/routing/OsrmRequestFormat.kt ve OsrmRouteRepository.kt: yol sunucuya göreli olarak istenir. OSRM public demo üretim için kapalıdır; kullanıcı kendi HTTPS OSRM sunucusunu girmezse rota kullanılamıyor olarak kalır. Photon URL değiştirilebilir.
- data/weather/OpenMeteoWeatherRepository.kt: HTTPS sunucu adresi yapılandırılabilir, rota örnekleme saf hesaplama olarak test edilir. Gelecekteki ETA saatlik hava tahmini FAZ 3 kapsamında yapılacak.
- domain/usecase/BoundedNetworkRetry.kt: IOException için en fazla 4 deneme, üstel geriye çekilme ve iptal propagasyonu; sınırsız retry yok.

## Teknik gerçekler ve güvenlik
Mevcut TimedValue alanlarında alan başına zaman damgası vardır; CoreEvidence yeni bir kaynak/sınıflandırma sözleşmesidir. Bütün eski UI tüketicilerinin CoreEvidence tipine taşındığı iddia edilmez.
API servis kökleri gizli anahtar değildir. Herhangi bir token bu DataStore içinde tutulmamalıdır; yeni bir token desteği ancak ayrı bir Android Keystore entegrasyonuyla geliştirilebilir.
Eski TomTom anahtarı ve imzalama paketleri iptal/inceleme konusu olmaya devam eder. OBD yalnızca Mode 01 / UDS 0x22. minSdk 28 olarak kalır; API 26 uyumlu olduğu kanıtlanmadı.
Photon mevcut genel sunucusunun kullanım koşulları, hız sınırı ve atıf doğrulaması gereklidir. Nominatim entegrasyonu FAZ 2; Open-Meteo saatlik ETA değerlendirmesi FAZ 3 kapsamıdır. OSRM public demo bilinçli olarak devre dışı.

## Yeni testler
- AppConfigurationTest: başlangıçta bilinmeyen kapasiteler; kullanıcı teyidi, birimler ve URL demo/HTTPS/kimlik bilgisi reddi.
- CoreEvidenceTest: zaman damgası, TTL ve kaynak-sınıf eşleşmesi; manuel senaryo gerçek ölçüm sayılamaz.
- BoundedNetworkRetryTest: IO retry sınırı, iptal ve kalıcı hatada tek deneme.
- Güncel OsrmHttpIntegrationTest: gerçek HTTP test sunucusunda göreli rota yolu.
- Güncel OpenMeteoWeatherRepositoryTest: ağ kullanmadan rota örneklemesi.
Sayısal birim test örnekleri sentetiktir; araca veya UI telemetrisine aktarılmaz.

## Komutlar
    git switch feature/phase1-core-trust-settings-20261107
    ./gradlew --version
    ./gradlew projects
    ./gradlew :nativecockpit:lintDebug :nativecockpit:testDebugUnitTest :nativecockpit:assembleDebug --no-daemon --stacktrace
    python3 -m unittest discover -s tests -p 'test_native_phase0_policy.py' -v
    python3 -m unittest discover -s tests -p 'test_apk_secret_scan.py' -v
    python3 scripts/check_apk_secrets.py nativecockpit/build/outputs/apk/debug/nativecockpit-debug.apk

## Riskler ve geri alma
OSRM HTTPS sunucusu yapılandırılmadan rota oluşturulamaz. Bu, public demo sunucusunu üretimde kullanmama şartının sonucudur. Hatalı URL, bozuk profil, eski BMS SoC ve ağ kesintisi fail-closed kalmalıdır.
Donanım testleri (ID.4 Pure, ELM327 SPP, gerçek GPS, Android Auto) bu PR ile yapılmış sayılmaz. Gerçek cihaz doğrulaması ayrı kabul kapısıdır. Bu PR kapatılırsa main ve feature/ev-cockpit değişmez; birleştirme sonrası geri alma için ayrı git revert PR gereklidir.

## Sahibin yapması gerekenler
1. Kendine ait güvenilir HTTPS OSRM servisi ve kotasını sağla, ayarlara gir.
2. Araç net batarya kapasitesini, tepe DC gücünü ve tüketimini ölçüm/kaynakla teyit et ve onayla.
3. Eski TomTom anahtarını iptal ettir, üretim imzası sürekliliğini incele.
4. Lint, JVM, APK, Gitleaks ve APK gizli bilgi taraması aynı committe yeşil olmadan FAZ 2'ye onay verme.
