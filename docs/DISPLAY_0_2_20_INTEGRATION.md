# DiPlay 0.2.20 APK İncelemesi ve EV Cockpit Entegrasyonu

**İnceleme tarihi:** 15 Ekim 2026. **Girdi:** Kullanıcının paylaştığı `DiPlay-0.2.20.apk`.
**APK SHA-256:** `f4d96cf469c5df12350946404a43e737ccc779a30e3b492c29f9c2338806f098`.

## 1. Gerçek APK'dan gözlenen bileşenler

APK ZIP arşivi 358 giriş içeriyor. Dört DEX dosyası (`classes.dex`…`classes4.dex`) bulunuyor. Dört mimaride (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) yerel kütüphaneler var: `liblocal_hotspot_radio.so`, `libspeex_echo.so`, `libxcertplay_i2c.so` ve `libandroidx.graphics.path.so`. 57 `assets/` girişi arasında BYD HUD ikonları, yerel ayar yardımcı HTML'leri, ses test kaydı ve iPhone/CarPlay kimlik materyali bulunuyor.

DEX tanımlayıcıları DiPlay tarafında ağ oturumu/yeniden bağlantı ve oynatım tamponu izleme gibi birimler olduğunu gösteriyor. Bu yöntem **sadece yapı ve davranış odaklı statik incelemedir**; DiPlay'ın gerçek araç ekranında çalıştığına veya APK'nın güvenilir olduğuna dair çalışma testi değildir. Gizli kimlik materyali, imza anahtarı ve üçüncü taraf görselleri/kütüphaneleri **kopyalanmadı, dağıtılmadı ve uygulamaya eklenmedi**.

DiPlay 0.2.20 sürüm notlarında USB bağlanma kurtarma, müzik ses saati sapması telafisi, çağrı sesi yönlendirmesi, Wi-Fi hotspot ve yerel tanılama alanları bulunur. Bunlar Apple CarPlay alıcısı için tasarlanmıştır, standart Android Auto medya istemcisi için değil.

## 2. Lisans ve uyumluluk sınırı

- Kaynak projenin CarPlay uygulama altyapısı GPL-3.0; DiAuto'dan türetilen arayüz AGPL-3.0 bilgisi veriyor. Kaynağı/binary'yi doğrudan kopyalamak, dağıtım ve atıf yükümlülükleri doğurabilir. **Bu projede DiPlay kopyası, çatallanması, yerel kütüphaneleri, MFi erişimi veya DiAuto arayüzünün kodu kullanılmadı.**
- DiPlay özellikle **BYD Android head unit üzerine kurulur**; 2024 electric vehicle ekranı aynı türde dışarıdan APK kabul eden bir BYD Android tablet değildir.
- Telefon **POCO F5 Android 15 → EV Cockpit Android Auto** yolu DiPlay'ın **iPhone → BYD CarPlay** yoluyla eşdeğer değildir.
- Android Auto sürüş sırasında video ekranı veya DRM erişim kısıtları değiştirilmedi.
- İnceleme üçüncü taraf akışlarını veya imzalı URL'leri güncellemez; süreli M3U bağlantıları sunucu tarafında geçersiz olabilir.

## 3. Fiilen entegre edilenler: v0.53

Var olan `PlaybackDiagnostics` ve `StreamRetryBudget` sınıfları zaten **canlı TV oynatıcıda** kullanılıyordu. DiPlay'ın güvenilir bağlantı ve teşhis yaklaşımı temel alınarak **bağımsız Kotlin koduyla** bu sınıflar film/video oynatıcısına da entegre edildi:

1. **Film/VOD tanı raporu:** Media3 üzerinden ilk görüntüye kadar geçen süre, tamponlama sayısı/süresi, decoder, çözünürlük, düşen video kareleri ve otomatik tekrar sayısı izlenir. Kullanıcı düğmeye basarsa Android paylaşım menüsüyle metin raporu gönderilir. Rapor URL, yol, kullanıcı adı, token, HTTP başlıkları veya film adlarını içermez. Otomatik telemetri gönderimi yok.
2. **Geçici hata kurtarma:** ağ zaman aşımı, bağlantı kopması, bazı HTTP 5xx durumlarında yalnızca **iki deneme** (1,5 sn ve 4 sn); yeni bir oturumda sıfırlanır. Kullanıcı yeni bir kaynağa geçtiğinde veya uygulama arka plana alındığında deneme iptal olur.
3. **Kalıcı hata sınıflandırması:** HTTP 401/403 (erişim reddi), 404/410 (kaldırılmış kaynak), 429 (sınır), DRM, decoder ve video formatı hatalarında sonsuz otomatik yeniden bağlanma yapılmaz; kullanıcıya anlaşılır açıklama gösterilir.
4. **Yeniden dene:** kullanıcı tarafından başlatılan elle yeniden deneme sayacı sıfırlar ve film oynatıcıyı hazırlar. Var olan kaldığın yerden devam etme davranışı korunur.
5. **Birim testleri:** HTTP hata sınıflandırması, sınırlı tekrar politikası, DRM'nin tekrar edilmemesi ve hassas bağlantı verisi taşımayan Media3 performans raporu.

## 4. İncelenen / kopyalanmayan parçalar

- DiPlay'ın `libxcertplay_i2c`, `liblocal_hotspot_radio`, `libspeex_echo` kütüphaneleri, CarPlay USBMUX, AirPlay, Siri mikrofon kontrolü, BYD gösterge/HUD entegrasyonu, CarPlay kimlik bilgileri ve `assets/offline-mfi` materyali **uygulanmadı**.
- DiPlay'ın `settings-backup.html`, `settings-code.html` arayüz dosyaları, ikonlar ve BYD tasarımları **kopyalanmadı**.
- Android Auto araç ekranında film gösterimi veya gerçek film oynatma **doğrulanmış değil**. Oynatılabilirlik için lisanslı ve hâlen erişilebilir URL gerekir.

## v0.55 — Ek uygulanan sınırlı HLS/TV tamponlama kurtarması

Statik APK'dan çıkartılan **güvenilir bağlantı teşhis yaklaşımı** temel alınarak, aynı kodu taşımadan `PlaybackStallGuard.kt` adlı saf Kotlin bileşeni oluşturuldu. Android Media3 filmi ya da canlı TV yayınını `BUFFERING` durumda 25 saniye bıraktığında, Media3'ün otomatik hata üretmediği **sessiz takılma** vakalarını tanır ve var olan 1,5 sn / 4 sn yeniden bağlanma bütçesini kullanır (en fazla iki tekrar). Sonuç alınmazsa kullanıcıya açık hata gösterir. Her ses/video oturumu için sıfırlanır, duraklat/çıkışla durdurulur. Hiçbir ağ adresi veya medya kimliği saklamaz.

`PlaybackStallGuardTest.kt` normal süre sınırını, tekrar alarmlarını, READY temizliğini ve monoton saati test eder. Bu, Android Auto video yetkilendirmesi, CarPlay entegrasyonu veya kapalı akışların DRM çözümü değildir.

## 5. Nasıl test edilir?

1. `gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug` başarılı olmalı.
2. Telefonda yetkili, bilinen çalışan HLS/MP4 örnek bağlantısıyla **Film / Oynatıcı** açılmalı; ilk görüntü, tamponlama ve duraklat/devam test edilmeli.
3. Ağ kapatılıp açıldığında otomatik tekrar **en fazla iki** kez olmalı. Süresi geçmiş adresle 403/404 geldiğinde tekrar döngüsü olmamalı.
4. **Tanı raporunu paylaş** ile oluşturulan raporda geçerli/özel video URL'si, query, token, Referer, User-Agent veya film başlığı olmamalı.
5. Telefonda/araçta gerçek bağlantı, görüntü ve ses performansı ayrıca doğrulanmalı; CI derlemesi buna denk değildir.

## Kaynaklar

- DiPlay resmi/açık kaynak deposu: https://github.com/shihabal3amri/DiPlay
- DiPlay 0.2.20 sürüm ayrıntıları: https://diplay.org/versions/?lang=en
- Bizim proje: https://github.com/AlpTimucin-cpu/ID4-Media-Hub

## 6. v0.54 — Yeni kaynak ve ağ denetimi

- DiPlay'ın bağlantı gözlemi yaklaşımından esinlenilen, tamamen yeni Kotlin implementasyonu: `SourceHealthProbe.kt`. Netflix/Prime korumalarını ya da CarPlay yetkilendirmesini aşmaz.
- Oynatıcıdaki `Kaynağı ve interneti kontrol et` düğmesi, kullanıcının kendi HTTPS medya adresinin sınırlı ilk 1 KiB'ını kontrollü GET ile alır, içerik türünü inceler; kontrol otomatik başlatılmaz.
- HTTP 401/403/404/410/429/5xx ve HTML/JSON gibi yanlış yanıtları kullanıcıya açıklar, cihaz ağ türünü kontrol eder. Karşı sunucunun dönüşü gerçek video izlenebilirliği anlamına gelmez.
- Kimlik/token/yönlendirme hedefi/film adı/hata exception text raporda görünmez; bağlantı denetimi için arka plan işçisi kullanılır.
- `SourceHealthProbeTest.kt` erişim ve yanıt formatlarını sınar; v0.54 beta build ve cihaz testleri ayrıca takip edilir.
