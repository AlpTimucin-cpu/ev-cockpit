# REC TV 22.0.2 (161) — APK teknik envanteri ve bağımsız uyarlama

## İncelenen dosya

Kullanıcının paylaştığı `rectv22.0.2-161mobilbox.apk`, yaklaşık **69 MiB** Android paketi. Android manifestindeki görünen ad **REC TV**, uygulama kimliği `com.rectv.shot`.

### APK'dan doğrudan doğrulananlar

- Flutter motoru: `lib/arm64-v8a/libflutter.so`, `lib/x86_64/libflutter.so`; uygulamanın derlenmiş Dart AOT kodu `lib/*/libapp.so` içindedir.
- 17 adet `classes*.dex` Android bytecode parçası ve `assets/audience_network.dex`; bununla ilgili kaynak kodu veya lisanslar kullanıcı tarafından paylaşılmadı.
- `assets/flutter_assets/assets/images/` içinde uygulama ikonları, logo ve açılış görselleri var. **Orijinal grafik varlıkları EV Cockpit'a kopyalanmaz.**
- `assets/flutter_assets/packages/youtube_player_iframe/assets/player.html`: gömülü **YouTube IFrame** tabanlı oynatıcı kanıtı; resmî YouTube Android uygulamasının uygulamanın içine gömüldüğünün kanıtı **değildir**.
- Derlenmiş kodda `better_player_channel`, `better_player_channel/videoEvents`, `webview_flutter_android` ve `video_player` ilişkili sınıf/kanal işaretleri var; oynatma altyapısına dair ipuçları verir ama gerçek yayın ve abonelik sözleşmelerini garanti etmez.
- Manifestte `com.rectv.shot.DownloadForegroundService`, Firebase ve birden fazla reklam SDK'sına ait sınıflar; ayrıca kamera, mikrofon, bildirim ve paket yükleme gibi Android izin adları var. Bildirilen izinler çalışmanın gerçekleştiğine dair kanıt değildir.
- **APK, önceki ekran görüntülerindeki MediaEngine uygulaması değildir.** MediaEngine ekranı/altyapısı bununla çözümlenemez.
- APK içinde Netflix / Amazon Prime Video'nun kendi servisleri veya bu uygulamaların kaynak kodu bulunduğuna dair bir kanıt yok.

## EV Cockpit'daki karşılık

| İşlev | REC TV'den görülen | Bizde doğrulanan karşılık |
|---|---|---|
| Çoklu platform | Flutter Android uygulaması | Kotlin/Android (EV Cockpit) |
| Video oynatma | BetterPlayer / Flutter oynatma işaretleri | AndroidX Media3 ExoPlayer, HLS ve DASH |
| YouTube | IFrame/player HTML varlığı | Resmî YouTube uygulamasına kullanıcı başlatmalı geçiş; isteğe bağlı ayrı Web Medya |
| Netflix / Prime Video | İlgili native istemci kodu doğrulanamadı | Resmî kurulu Android uygulamasını ayrı açan kısayollar |
| Yayın kataloğu | Sunucu/uygulama bileşenleri mevcut; çalışma teyidi yok | Kullanıcının M3U kanalları, XMLTV rehberi, favoriler |
| İndirme | Foreground download servisinin varlığı | Telifli indirme/kopyalama veya gizli yayın akışı alınmaz |
| Reklam/izleme SDK'ları | APK içinde mevcut | Aktarılmadı |
| REC TV kullanımı | Android paketi `com.rectv.shot` | Kuruluysa Uygulamalar ekranından **REC TV'yi telefonda aç** |

### Uyarlama ilkesi

**Bir APK'dan orijinal Flutter/Dart kaynak kodu ve özel sunucu erişimleri 1:1 elde edilemez.** İzin/lisans olmadan üçüncü taraf APK'nin kaynaklarının, logolarının, videolarının, gizli API veya DRM sistemlerinin yeni APK'ya paketlenmesi yapılmaz. Bunun yerine gerekli iş akışları ve genel yerleşim anlayışı özgün kodla geliştirilebilir.

REC TV uygulaması **ayrı** açılır; onun görüntüsü EV Cockpit içine yerleştirilmiş, emüle edilmiş veya gizli bir arayüz üzerinden ele geçirilmiş değildir. Android Auto'nun sürüşte video kısıtlarına dokunulmaz. Araç ekranında videonun çalıştığı iddia edilmez.

## Güvenlik

Paylaşılan APK'nin bütünlüğünün veya güvenilir kaynaktan geldiğinin bağımsız doğrulaması yapılmadı. EV Cockpit üçüncü taraf APK'yi otomatik kurmaz, veri/çerez paylaşmaz, kurulu olup olmadığını Android `PackageManager` ile sorgular ve yalnızca kullanıcının dokunmasıyla başlatır.
