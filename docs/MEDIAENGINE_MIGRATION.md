# MediaEngine → EV Cockpit | Göç planı

## Uygulanan ilk aşama
Bu dal MediaEngine 3.2'yi kendi APK'sı içine **gömmez**; MediaEngine'ya kullanıcı onaylı
telefon bağlantısı sağlar. Projedeki IPTV, radyo, M3U, EPG, YouTube, katalog
ve favoriler korunmuştur.

- MediaEngine 3.2'nin APK içindeki paket adı: `me.app.fermataX.auto.mola32.debug`.
- Uygulama kuruluysa kullanıcı onayıyla telefonda açılır.
- TV için kullanıcının seçtiği tek bir HTTPS yayın bağlantısı aktarılır.
- Radyo için seçilen tek istasyonun HTTPS bağlantısı aktarılır.
- APK otomatik indirilmez veya kurulmaz.
- Android Auto video kilitleri ya da araç hareket bilgisini değiştiren
  bir yöntem kullanılmaz.

## Kaynak ve lisans
- MediaEngine kaynak deposu: https://github.com/moris545454/araba-
- Ana proje: https://github.com/AndreyPavlenko/Fermata
- MediaEngine/Fermata: GNU GPL-3.0. Kaynak kodu alınıp tek APK'ya
  birleştirilerek dağıtılırsa ilgili lisans/telif notları korunmalı,
  uygun GPL kaynak kodu erişimi sağlanmalıdır.
- Yüklenen `MediaEngine-3.2.apk` dosyasının, genel GitHub deposunun
  mevcut kaynak kodundan derlendiği **doğrulanamadı**.
- Depodaki 2.2.2 için hazırlanmış güvenlik raporu 3.2'yi kapsamıyor.

## Tek APK'ya gerçek kaynak taşıma için kalan aşamalar
1. MediaEngine 3.2'nin kaynak sürümü ve imzası geliştiricinin yayımladığı
   doğrulanabilir kaynak/release ile eşleştirilmeli.
2. MediaEngine'nın lisanslı TV/oynatıcı kodları, API ve Gradle bağımlılıkları
   analiz edilip mevcut Kotlin uygulamamızın mimarisiyle bütünleştirilmeli.
   Kaynak kod referansı kopyalanırken GPL şartlarına uyulmalı.
3. Aynı APK içinde Android Auto için gerekli servisler ve ayrı telefon
   faaliyetleri çakışmayacak şekilde yeniden düzenlenmeli. Testler
   gerçek cihazda yapılmalı.
4. POCO F5 Android 15 ve electric vehicle üzerinde, park halinde
   yayın, ekran izni, geri dönüş, kopma, ses ve hata senaryoları sınanmalı.
5. APK izinleri, internet hedefleri, paket imzası ve güncelleme
   güvenlik denetimleri yeni birleşik çıktıda yeniden doğrulanmalı.

## Aşama farkı
**Şu anda:** MediaEngine ayrı uygulama olarak açılır, medya bağlantısı aktarılır.
**Hedef:** Kaynak kodu doğrulanmış MediaEngine oynatma/Android Auto özellikleri
EV Cockpit'ın içinden çalışır. Hedefin gerçekleştiği henüz
iddia edilmez.

## Telefon denemesi
1. MediaEngine 3.2 kuruluysa EV Cockpit → Kitaplık → MediaEngine 3.2 aç.
2. TV sekmesinde MediaEngine'ya tek kanal aktarımını dene.
3. Radyo sekmesinde seçilen radyoyu MediaEngine'ya göndermeyi dene.
4. Gerçek EV Cockpit ekranında video gösterimi ayrıca sınanmalıdır.

Derleme testi ve fiziksel araç testi **yapılmadı**.

---

## MediaEngine 3.2 APK için somut envanter (yüklenen ikili dosyadan)
- Dosya: MediaEngine-3.2.apk
- Boyut: 160.097.591 bayt
- SHA-256: `2cf92adf8df595c97e8ac9b5c9e0d73f64e2e3ce8601756426773f14e50f3f47`
- APK içindeki toplam ZIP girdisi: 1.620
- Dalvik DEX sayısı: 12
- ARM64, ARMv7 ve x86_64 için libvlc.so ve libvlcjni.so içerir.
- Binary Manifest'te mikrofon, medya ses/video, APK kurulum isteme ve
  MediaProjection ön plan servisiyle ilişkili izin/simge dizeleri bulundu.
- Aynı manifestte READ_SMS, READ_CONTACTS, ACCESS_FINE_LOCATION,
  SYSTEM_ALERT_WINDOW veya BIND_ACCESSIBILITY_SERVICE izin adı
  görülmedi. Bu tek başına zararlı kod bulunmadığı garantisi değildir.
- DEX incelemesinde XtreamAccount ve StalkerAccount tanımları,
  WebBrowserAddon ve MediaProjection ile ilgili sınıflar bulundu.
  Bir kısım Android location API referansı bulunması, konum izni
  ve gerçek GPS takibi bulunduğu anlamına gelmez.
- Dağıtılmış 3.2 ikilisini genel kaynak depodaki 2.2.2 commitinden
  yeniden derleyerek doğrulamadık; ikiliyi uygulamamıza gömmüyoruz.

## Yerel entegrasyon kapsamı (aşağıdaki duruma göre)
| Bileşen | EV Cockpit durumu | Ayrıntı |
|---|---|---|
| M3U/M3U8 içe alma | Mevcut | HTTPS URL ve SAF dosya seçimi |
| IPTV / canlı TV | Mevcut | Media3 video + kalite/altyazı |
| Alternatif VLC motoru | **Yeni kaynak kodu eklendi** | LibVLC 3.7.2, telefonda kullanıcı seçimi |
| Xtream Codes içe alma | **Yeni kaynak kodu eklendi** | HTTPS, kullanıcı yetkisi, M3U çıktısı |
| URL/abonelik verisi koruma | **Yeni kaynak kodu eklendi** | Keystore AES-256 GCM, eski verileri kademeli dönüştürme |
| XMLTV/EPG | Mevcut | Program eşleme |
| Radyo ve Radio Browser | Mevcut | Ses kitaplığı, favoriler |
| Podcast | Mevcut | Telefon kataloğu |
| YouTube | Mevcut | HTTPS WebView arayüzü; oynatma siteye bağlı |
| Film/dizi kataloğu | Mevcut | Kullanıcının kaynakları ve yasal kataloglar |
| Netflix / Prime Video | Kısıtlı | Resmî uygulamaya aktarım, DRM ayrı |
| Stalker/Ministra portalı | **Henüz yok** | Hesap kimliği/MAC doğrulaması ve güvenlik testleri gerekiyor |
| Dahili MediaProjection | **Henüz yok** | Şu an ayrı ScreenOnAuto bağlantısı var |
| Android Auto görüntü servisi | **Henüz yok** | Yalnızca araçta ses kataloğu; otomobilde video doğrulanmadı |
| MediaEngine 3.2 ikili kodunu gömme | **Yapılmadı** | Kaynak/sertifika uyuşmazlığı ve GPL denetimi nedeniyle |

## Kaynak telif ve güvenlik sınırları
MediaEngine/Fermata'nın kaynak lisansı GPL-3.0; aynen alınan kaynak kodu
ile birleştirilen dağıtımların kaynak ve telif koşullarını karşılaması
gerekir. Buradaki Xtream kullanıcı arayüzü ve URL şifreleme katmanı
EV Cockpit için yeni yazıldı. LibVLC Maven bağımlılığı
`org.videolan.android:libvlc-all:3.7.2`, VideoLAN'ın LGPL-2.1
lisanslı bağımsız kütüphanesidir. Bu sürüm MediaEngine'nın açık Gradle
kataloğuyla eşleşir; doğrudan MediaEngine-3.2.apk içinden kopyalanmadı.

Telefonun hassas banka verileri için gerekli SMS, rehber, konum veya
erişilebilirlik izinleri eklenmez. Uygulama yalnızca kullanıcının
seçtiği yayın adreslerini kullanır. Xtream hesabı HTTPS dışına
yönlendirildiğinde isteği reddedecek şekilde koruma eklendi.

## Gözden geçirilecek önemli davranışlar
1. Keystore anahtarı kaybedilirse şifreli liste adresleri çözülemez.
   İçe aktarmadan önce kendi listenizin güvenli yedeğini saklayın.
2. Bazı IPTV sağlayıcıları düz HTTP üzerinden çalışır; bu uygulama
   hesap güvenliği için onları kabul etmez.
3. Büyük oynatma listeleri mevcut M3U ayrıştırıcı sınırına takılabilir
   (15.000 kayıt). Tam katalog için ileride SQLite tabanlı kaynak
   indekslemesi gereklidir.
4. Video için VLC/Media3 seçmek, araç ekranı Android Auto kurallarını
   kendiliğinden değiştirmez.
5. Derleme, ağ testi, Play Protect/banka uyumluluk testi veya
   POCO F5/EV Cockpit üzerinde fiziksel doğrulama **henüz tamamlanmadı**.
