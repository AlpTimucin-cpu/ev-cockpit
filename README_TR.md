# EV Cockpit — geliştirme sürümü

Kotlin, Jetpack Compose, MapLibre ve Android for Cars App Library 1.7.0
ile geliştirilen bağımsız elektrikli araç kokpiti. **Gerçek VW ID.4 üzerinde
Android Auto kabulü henüz doğrulanmadı.**

## 9 Kasım 2026 — gerçek durum

- `main` hâlâ eski Media Hub tabanıdır. Native kokpit değişiklikleri
  [Faz 12 PR #45](https://github.com/AlpTimucin-cpu/ev-cockpit/pull/45),
  [Tanılama PR #46](https://github.com/AlpTimucin-cpu/ev-cockpit/pull/46)
  ve [telefon/ID.4 protokolü PR #47](https://github.com/AlpTimucin-cpu/ev-cockpit/pull/47)
  üzerinden üst üste duran **taslak PR** dallarındadır. Henüz
  `main` dalına birleştirilmedi.
- Kullanıcının bilgisayarı yok; telefon ve park halindeki ID.4 ile test
  yapılacak. [A–H kabul protokolü](docs/IS2_TELEFON_ID4_KABUL_TESTI_TR.md)
  bu nedenle gerçek ekran fotoğrafı ve maskeli Debug raporu ister.
- Uygulamada **gerçek GPS** ve **gerçek OSRM** kullanılır. ETA, varış enerjisi
  ve hava verileri **TAHMİN** olarak etiketlenir. Veri yoksa
  `kullanılamıyor`.
- ELM327 yalnız izinli SAE Mode 01 ve UDS `0x22` okumalarına izin verir.
  **ID.4 Pure batarya SoC DID/adres/çarpanı doğrulanmadı.** ECU yazma
  ve üretim dışı sahte GPS/SoC/OBD kullanılmaz.
- Hava tahmini otomatik sorgusu, **Android Auto ön planda** ve gerçek rota/GPS
  eşleşmesi varken en fazla 30 dakikada bir denenir. Doze/host kopması
  veya internet kesilmesi zamanlamayı geciktirebilir; Debug olay tarihleriyle
  ölçülmelidir.
- **Sürüş ekranında video yoktur.** Telefon videosu sadece gerçek duruş
  ölçümü ve kullanıcının açık park onayıyla izinlenir.

## Sürüm ve paket

| Paket | Durum |
| --- | --- |
| `com.evcockpit.debug` · `1.0.8-DEBUG` | Test APK'sı; ayrı kurulur |
| `com.evcockpit` · `1.0.8` | Release kaynak sürümü; **yayımlanmadı** |

Debug Tanılama, Ayarlar'dan açılır. Yalnız uygulamanın kendi olaylarının
son **500 kaydını** RAM'de tutar; **Kopyala / Paylaş (.txt)** özelliği
vardır. Koordinatlar, gizli anahtarlar, MAC, VIN ve tam ECU cevabı
günlüğe alınmaz. Telefon/araç uygulaması üzerinde tüm işlevler henüz
doğrulanmadı.

Release varyantı `isDebuggable=false`, `allowBackup=false`,
`usesCleartextTraffic=false`, sistem CA'larıyla HTTPS ağ politikası,
R8 + resource shrinking ve **yalnız `arm64-v8a` ABI** içerir. Bu ayarlar
için ayrı CI **geçici imzalı derleme kontrolü** tanımlanmıştır; bu dosya
üretim yayını **değildir**. Önceden açık edilmiş gerçek imza
bilgileri nedeniyle `release.yml` kilidi kaldırılmadı.

## Derleme

Java 17, Android SDK 36 ve SHA-256 kontrollü Gradle Wrapper 8.14.3:

```bash
./gradlew :nativecockpit:lintDebug :nativecockpit:testDebugUnitTest :nativecockpit:assembleDebug
```

Depoda `:nativecockpit`, `:app` ve `:evlite` Gradle modülleri bulunur;
`media-engine/` kaynak üst katmanıdır, Gradle modülü değildir.
`main` içeriği bu güncel dal yapısından farklı olabilir.

## Kaynaklar

[A–H test listesi](docs/IS2_TELEFON_ID4_KABUL_TESTI_TR.md) ·
[34 açık PR için entegrasyon sırası](docs/INTEGRATION_PLAN_2026-11-09_TR.md) ·
[Eski ZIP ve GitHub profil planı](docs/REPO_PROFILE_AND_SOURCE_ARCHIVE_TR.md) ·
[Sürüm notları](CHANGELOG.md) ·
[English](README_EN.md) · [GPL-3.0](LICENSE)

EV Cockpit bağımsız bir projedir; Volkswagen, Android Auto veya
istasyon işletmecileriyle bağlantı iddiası yoktur.
