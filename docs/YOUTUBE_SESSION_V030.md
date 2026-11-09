# EV Cockpit Drive Hub v0.30 — YouTube oturum ve video geçmişi

## Kaynak ve sınırları

Kullanıcının 13 Ekim 2026'da paylaştığı 653 satırlık metin, FermataX'in derlenmiş uygulamasına ait **sınıf ve metot isimleri** envanteridir. Tam Kotlin/Java kaynak kodu veya uygulama davranışının doğrulanması değildir.

Envanterde `YoutubePlaybackSession`, `YoutubeItem`, `YoutubeNavigationCoordinator`, `YoutubeRetentionPolicy`, `YoutubeFullscreenCoordinator`, `YoutubeWebView`, `YoutubeSessionEngine` ve `YoutubeWebAudioBridge` gibi yapılar bulunuyor. İsimler tek başına işlevi kanıtlamaz; hiçbir JavaScript enjektörü veya FermataX APK ikilisi kopyalanmadı.

## Somut olarak gerçekleştirilen özgün geliştirme

- `YoutubePagePolicy.kt`: YouTube `/watch?v=`, `/shorts/`, `/live/`, `/embed/` ve `youtu.be` linklerini doğrular; aynı videoya ait paylaşımları tek, güvenli `https://www.youtube.com/watch?v=XXXXXXXXXXX` kimliğine indirger.
- Yalnızca gerçek YouTube ana makine adları, HTTPS, geçerli 11 karakterli video ID kabul edilir. Sahte `youtube.com.evil.example` alan adları, URL kullanıcı bilgisi, gereksiz portlar, yanlış şemalar ve kısaltılmış geçersiz ID'ler reddedilir.
- `YoutubeVideoStore.kt`: 30 videoya kadar yalnızca telefonda video başlığı ve kanonik video bağlantısı tutar. Takip `si`, `t`, `feature`, oynatma listesi kimliği, oturum çerezleri veya başka sorgu parametreleri bu ayrı video geçmişine alınmaz.
- `MediaWebActivity.kt`: YouTube tek sayfa uygulamasında (SPA) videoların URL değişimlerini `doUpdateVisitedHistory` ile takip eder; başlık güncellendiğinde geçmiş bilgisini yeniler. Eski `onPageFinished` akışı da korunur.
- Web Medya'daki **YT◷** düğmesinden izlenen videoları seçebilir ve yalnızca bu geçmişi ayrıca onay vererek temizleyebilirsin. TV/radyo kütüphaneleri ve genel web favorileri değişmez.
- Bu, Youtube uygulamasını veya video oynatımını Android Auto ana ekranına aktarma özelliği değildir; yalnızca telefon tarafındaki web kullanımı içindir.

## Test yaklaşımı

- YouTube ve youtu.be adresleri aynı video kimliğine eşlenir; paylaşım parametreleri silinir.
- Sahte alan adları, protokol hataları, gömülü hesap bilgileri, geçersiz video kodu ve beklenmedik port test edilir.
- Android 15 emülatör smoke testi, Web Medya'da **YT◷** düğmesini kontrol eder.
- Daha önceki emülatör testi uygulamayı başlatmış ve EV Cockpit Media Cockpit'i açmıştır; sonrasında TV performans düğmesi otomasyonda tek kaydırmayla bulunamadığı için başarısız olmuştu. Bu sürümde görünür UI içinde birkaç kaydırmayla arama eklendi.

## Sürüş ve telif kapsamı

Araç hareket halindeyken sürücünün ön ekranında video kilidini kaldıran bir değişiklik yok. YouTube reklam atlatma, DRM çözme, video indirme/ayrıştırma, resmî olmayan medya çıkarma veya başka uygulamanın kaynak kodunu kopyalama yapılmadı. EV Cockpit araç ekranında oynatma ayrıca doğrulanmalıdır.
