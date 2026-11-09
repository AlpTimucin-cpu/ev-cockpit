# EV Cockpit — FAZ 0 native doğrulama kaydı

Durum: Taslak PR. Üretim veya cihaz kabulü verilmedi.
Hedef: audit/phase0-native-validation-20261107 → feature/ev-cockpit.
main dalı ve önceki #16–#24 PR'ları değiştirilmedi.

## Doğrulanmış Gradle modülleri

- main: ID4MediaHub; yalnızca :app (medya uygulaması).
- feature/ev-cockpit: EvCockpit; :app, :evlite ve :nativecockpit.
- Gerçek Compose kokpit: :nativecockpit, applicationId com.evcockpit.
- Java 17, compileSdk 36, Gradle Wrapper 8.14.3.
- Gerçek minSdk 28; istenen minSdk 26 desteği doğrulanmadı.
- media-engine dizini Gradle include modülü değildir.
- Gerçek ./gradlew projects çıktısı Actions derlemesinde ayrıca kontrol edilir.

## FAZ 0 düzeltmeleri

1. .github/workflows/build-nativecockpit.yml: tam commit SHA ile sabitlenmiş
   Actions; varsayılan contents: read; PR cache salt-okunur; Java 17,
   SDK 36; lintDebug, testDebugUnitTest, assembleDebug; APK paket,
   checksum ve gizli bilgi kontrolü. Lint başarısızsa iş de başarısızdır.
   Debug APK ve rapor artifact saklama süresi 7 gün.
2. .github/workflows/security-secrets.yml: PR/push için Gitleaks; manuel
   tüm geçmiş kontrolü. pull_request_target ve imzalama sırrı yok.
3. scripts/check_apk_secrets.py ve tests/test_apk_secret_scan.py:
   APK içindeki ZIP üyelerini açarak riskli anahtar kalıplarını denetler.
   Örnek testler sahte değer kullanır. Bu tarama eksiksizlik garantisi değildir.
4. nativecockpit/src/main/res/values-tr/strings.xml: Eksik 129 Türkçe
   arayüz metni çevrildi; ilk native lint çalışmasındaki 129 hatanın
   tamamının türü MissingTranslation idi. Ayrıca 187 uyarı vardı.
5. Elm327Session: Mod 01 ve salt-okunur UDS 0x22 dışındaki sorguları
   reddeder; negatif Kotlin/JUnit testleri eklendi.
6. MebUdsParser: doğrulanmamış DID yanıtlarından BMS SoC veya sıcaklık
   sayısı türetilmez; sadece ham yanıt sunulur.
7. Elm327VehicleRepository: otomatik üretici DID sorgu döngüsü kaldırıldı.
   Yalnızca SAE Mod 01 hız yoklaması kalır. Aday MEB DID sorguları ancak
   kullanıcı açıkça başlatırsa ham yanıt olarak işlenir.
8. tests/test_native_phase0_policy.py: Türkçe XML, biçim argümanları,
   gerçek native modül, checksum ve salt-okunur sorgu sınırlarının kaynak
   kontrolleri. Kaynak kontrolleri gerçek OBD/araç testi yerine geçmez.

## Riskler: Doğrulanmadı

- Topluluk kaynaklı geçmiş CAN ID, DID ve eşlemeler araç üzerinde henüz
  doğrulanmadı. Kodda aday adresler hâlâ bulunabilir; bunlardan
  gerçek ölçüm sonucu veya batarya sağlığı çıkarılamaz.
- Eski TomTom anahtarının iptal/rotasyonu, eski üretim imzasının
  sertifika sürekliliği ve Git geçmişi incelemesi hâlâ açıktır.
- Android Auto, GPS, ELM327, ses odağı ve arka plan servisleri gerçek
  telefon/araç üzerinde kabul testine girmedi.
- API 26 desteklenmiyor. Şimdiki minSdk 28; düşürmek için ayrı teknik
  uyumluluk matrisi gerekir.
- Eski kaynak ZIP dosyalarının lisans/sır denetimi tamamlanmadı.
- Yayımlanmış Release APK'larının sahibi tarafından imzalandığı
  doğrulanmadı; yeni production release ortamı onaysızdır.

## Komutlar

    git switch audit/phase0-native-validation-20261107
    ./gradlew --version
    ./gradlew projects
    python3 -m unittest discover -s tests -p 'test_native_phase0_policy.py' -v
    python3 -m unittest discover -s tests -p 'test_apk_secret_scan.py' -v
    ./gradlew :nativecockpit:lintDebug :nativecockpit:testDebugUnitTest :nativecockpit:assembleDebug --no-daemon --stacktrace
    python3 scripts/check_apk_secrets.py nativecockpit/build/outputs/apk/debug/nativecockpit-debug.apk
    gh run list -R AlpTimucin-cpu/ev-cockpit --branch audit/phase0-native-validation-20261107 --limit 10

Beklenen: Gradle 8.14.3, EvCockpit ve üç gerçek modül;
lint, JVM testleri, Debug APK, Gitleaks ve APK sızıntı kontrolünün
aynı commit için başarılı olması. Bir hata varsa FAZ 0 kapısı kapalıdır.

## Sahip ve geri alma

Sahip eski TomTom anahtarını iptal etmeli, şüpheli imzalama anahtarını
incelemeli, GitHub production-release Environment korumasını bağımsız
onaylayıcıyla kurmalı ve APK'nın telefonda/Android Auto'da gerçek
kabul testlerini belgelemelidir.

Taslak PR kapatılırsa main/feature değişmez. Birleştirildikten sonra
geri alma, yeni gözden geçirilen git revert PR'ı ile yapılmalıdır.

FAZ 1'e geçiş için aynı kaynak commitinde tüm teknik CI kapıları yeşil
olmalıdır; bu koşul cihaz ve üretim sertifikası onayının yerini tutmaz.
