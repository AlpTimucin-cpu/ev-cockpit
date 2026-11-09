# EV Cockpit — GitHub depo profili ve kaynak arşivi

**9 Kasım 2026:** GitHub API okumasında repo açıklaması **boş**, Topics listesi **boş**.
Mevcut iki yayımlanmış tag `v1.0.0` ve `v1.0.2` eski APK asset'lerine sahip;
bunların mevcut `nativecockpit` Faz 12 / İş 1 / İş 2 ile aynı kaynak olduğu
**doğrulanmadı**. Yeni production Release güvenlik nedeniyle **yasak**.

## Önerilen depo açıklaması (Settings → General → Description)

> EV Cockpit: Kotlin/Jetpack Compose, MapLibre and Android Auto navigation,
> real-GPS OSRM routing, Open-Meteo forecasts, EPDK charging data, and read-only ELM327 diagnostics. Development preview; vehicle validation pending.

**Önerilen Topics:** `android`, `kotlin`, `jetpack-compose`,
`android-auto`, `maplibre`, `electric-vehicles`, `osrm`,
`open-meteo`, `bluetooth-obd`, `ev-charging`.

Bu öneriler **GitHub'da henüz uygulanmadı**. Kullanılabilir bağlı GitHub
yazma araçları, repo açıklamasını veya Topics listesini değiştirmiyor.
Repo sahibi telefonda GitHub web → repo → **About** bölümündeki dişli
→ Description/Topics alanlarından kaydedebilir. `main` içeriğini değiştirmez.

## Eski kaynak ZIP için güvenli arşiv planı

- Kökteki mevcut dosya: `EV_Cockpit_v0.8_Source.zip`.
- Git ağacında bildirilen boyut: **49.559 bayt**. Bu, dosyanın SHA-256
  doğrulaması **değildir**. Eski `main` / bugünkü faz kaynaklarıyla
  eşdeğer olduğu varsayılmayacak.
- Hedef: **kaynak arşivi olarak** ayrı ve açıkça `LEGACY` etiketli bir
  GitHub Releases asset'i. Bir üretim APK'sı veya yeni sertifikalı Release
  gibi tanıtılmamalı.
- İstenen işlem sırası: (1) ZIP'i telefona indir; (2) GitHub Releases'te
  *legacy source archive* olarak draft kaynak yayını hazırla;
  (3) byte boyutu + SHA-256 karşılaştırarak yüklenen asset'i doğrula;
  (4) ancak bundan sonra ZIP'i kaldıran **ayrı dal/taslak PR** aç;
  (5) yeni commitin CI'sını yeşile döndür. Eski Git geçmişi silinmez.
- Kullanılabilir GitHub bağlantısı şu anda `create_release` veya
  `upload_release_asset` yazma işlemlerini sağlamıyor; bu nedenle
  **yükleme yapılmadı, kök ZIP silinmedi**.
- **Güvenlik:** `release.yml` üzerindeki `if: false` kilidini açma;
  eski imzalama anahtarını yeniden kullanma; arşive `.keystore`,
  `local.properties`, kişisel koordinat/API anahtarı ekleme.

## Sürüm etiketleri

- `nativecockpit` Release sürüm adı **`1.0.8`** olarak tanımlıdır;
  **yayımlandı demek değildir**.
- Debug'in benzersiz kurulum kimliği `com.evcockpit.debug` korunur;
  yeni Debug gösterim adı `1.0.8-DEBUG` olup yanlışlıkla stable
  `1.0.8` sanılmasını önler.
- Gerçek ID.4 ekranı, GPS/USB toparlanması, OBD ve Android Auto ses
  kabul testleri **doğrulanmadı**.
