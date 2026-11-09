# FAZ 7 — Android Auto oturum dayanıklılığı ve Release öncesi kabul

## Kod değişiklikleri / teknik sınırlar

1. Android Auto navigation ownership artık **opaque Screen token** ile izlenir; eski Screen'in `onStop/onDestroy` çağrısı yeni Session'ı kapatamaz.
2. Fused GPS isteği her Car Screen başlangıcında yeni callback ve **epoch** ile açılır. Eski dinleyiciden geciken konum/hata cevabı yeni oturumun GPS'ini değiştiremez.
3. Kısa TTS konuşmasında bir ses odağı kayıp bildirimi alınırken **lease generation** kaydedilir. Önceki konuşmanın sırada bekleyen kayıp bildirimi yeni sesin odağını kesemez.
4. **Debug uygulama kimliği artık `com.evcockpit.debug`**, sürüm son eki `-TEST`, uygulama adı `EV Cockpit TEST` (Türkçe ve varsayılan). Mevcut `com.evcockpit` imzalı uygulamayla karıştırılmadan yan yana kurulabilir; üretim appId veya imzalama sistemi değiştirilmez.
5. ZIP kaynak denetimi: `docs/PHASE7_UPLOADED_ZIP_AUDIT.md`. Üçüncü taraf eski kod doğrudan aktarılmadı.

## CI doğrulama

```bash
python3 -m unittest discover -s tests -p 'test_native_phase0_policy.py' -v
python3 -m unittest discover -s tests -p 'test_phase7_safety_policy.py' -v
./gradlew :nativecockpit:lintDebug :nativecockpit:testDebugUnitTest :nativecockpit:assembleDebug --no-daemon --stacktrace
python3 scripts/check_apk_secrets.py nativecockpit/build/outputs/apk/debug/nativecockpit-debug.apk
```

GitHub Actions **son commit** için: Python güvenlik testleri, Kotlin/JUnit, Android lint, Debug APK, APK içi gizli anahtar taraması ve Gitleaks tamamı başarılı olmalıdır. `aapt dump badging` ile **yalnızca `com.evcockpit.debug` ve "EV Cockpit TEST"** kabul edilir.

## Mobil kullanım — test sırası

Bunlar **henüz fiziksel cihazda yapılmadı**. Araç sürülürken test, hata ayıklama veya video işlemi yapılmaz; önce güvenli şekilde park edin.

- [ ] **Yalnızca Debug APK:** PR'ın başarılı GitHub Actions kaydındaki `EV-Cockpit-Native-Debug-APK` artifact ZIP içinden APK'yı çıkarın. Paket ismi `com.evcockpit.debug` ve ikon altı `EV Cockpit TEST` olmalı. Üretim uygulamasını silmeyin; eski uygulamanın verileri etkilenmemeli.
- [ ] **Android 28+ test cihazı:** İlk çalıştırma, Türkçe UI, izin reddi/yeniden verme, uçak modu, app kapanış/açılış; veri uydurulmamalı.
- [ ] **OSRM:** Kendi doğrulanmış HTTPS OSRM adresi kullanıcı Ayarlar'dan girilmeden rota "kesin" gibi sunulmamalı. Gerçek Niğde–Antalya rotasında GPS izni, uzunluk ve maneuvralar test edilmeli.
- [ ] **Android Auto:** Uygulama uygun Android Auto geliştirici/test yapılandırmasıyla açılabilirse gerçek kablolu/kablosuz bağlantıda araç ekranına yalnızca harita ve sürüşe uygun navigation template gelmeli; video/WebView açılmamalı. Test uygulaması bir otomotiv uygulaması olarak Google tarafından onaylanmış varsayılmamalı.
- [ ] **Android Auto tekrar bağlantısı:** Rota açıkken bağlantıyı kesip yeniden kurun; eski oturum kapanınca yeni oturumun haritası veya TTS'si durmamalı. Bu testi araç park hâlindeyken yapın.
- [ ] **GPS oturumları:** Konum iznini geri çekip verin, GPS'i kapatıp açın, ekranı yeniden başlatın. Geç kalmış önceki konum geçerli GPS gibi gösterilmemeli. Başlatmak için taze GPS şart.
- [ ] **Yönlendirme:** 650 m/90 m komutları, Türkçe ses, takip eden dönüş ve rota değişimlerinde önceki komutların tekrar/çift ses vermemesi; otomatik off-route davranışı fiziksel konumla denetlenmeli.
- [ ] **Telefon/araç sesleri:** Radyo veya Media3 müzik çalarken TTS, odağın geçici alınması, gelen çağrı, ses odağı kaybı ve sonraki konuşmada medya sesinin düzgün dönmesi; kulaklık/BT ile de ayrı test.
- [ ] **OBD/şarj:** ELM327 bağlantısı kesildiğinde SoC/SoH tahminleri gerçekmiş gibi kalmamalı. MEB DID ham yanıtı otomatik SoC hesaplamasını açmamalı. EPDK fiyat ve doluluk **canlı veri değildir**.
- [ ] **Telefon videosu:** Android Auto bağlandığında, OBD bilinmediğinde veya hız >0.5 km/sa olduğunda kapalı. Araç sıfır hızdayken bile vites/Park doğrulaması bulunmadığı için sürücü video izlememeli.
- [ ] **Rollback:** Sadece `EV Cockpit TEST` kaldırılabilir. Eski imzalı `EV Cockpit` uygulamasını veya verilerini değiştirmeyin.

### Hata kaydı

GitHub issue veya sohbet mesajı için yalnızca hata saatini, Android/AA sürümünü, "cihaz bağlandı/ayrıldı", hata adımını ve mümkünse **kişisel adres, rotalar ve Bluetooth cihaz kimlikleri gizlenmiş** ekran görüntüsünü paylaşın. Özel mesajlarda bile keystore/şifre/Token/kişisel GPS geçmişi paylaşmayın.

## Release — P0 (BLOCKED)

- **Önemli:** FAZ 6/7 geliştirme dalındaki `release.yml` `if: false` ile güvenlik beklemesinde olsa da bu değişiklik **henüz `main` dalına uygulanmış değildir**.
- `main` üzerindeki eski manuel `release.yml` kayıtsız kalmamalıdır. **Güvenlik PR #32** (`security/hold-release-from-main-20261108`) ana dala **incelenip birleştirilince** iş akışı açık hata ile bloklanır. PR açılması tek başına koruma değildir. Geçmiş workflow sürümünün yeniden çalıştırılması da ayrıca gözden geçirilmelidir.
- Geçmiş imzalama anahtarının ifşa kapsamı, anahtar/sertifika rotasyonu, Play App Signing, mevcut kullanıcıların sertifika devamlılığı ve güvenli sır saklama yöntemleri doğrulanmadan **imzalı stable Release üretilmez**.
- Emulator/DHU ve gerçek ID.4 Android Auto oturumları; Play Store kalite/driver distraction kuralları; telefon Android 15+ AudioFocus koşulları (top app/FGS) ayrı gerçek kabul gerektirir.
- Harici video/hareket hâlinde yolcu modu ve ZIP'in doğrulanmamış MEB PID ölçekleri **tasarıma geri alınmayacak**.

### Kaynaklar

- Android for Cars App Library: https://developer.android.com/training/cars/apps/library
- Navigation apps: https://developers.google.com/cars/design/create-apps/app-types/navigation
- Audio focus lifecycle: https://developer.android.com/media/optimize/audio-focus

Bu aşama en fazla **CI doğrulamalı Debug** sürümü çıkarabilir. Fiziksel araç testi ve sertifika/sürüm dağıtım onayı bu yanıt/PR kapsamında yapılmış değildir.
