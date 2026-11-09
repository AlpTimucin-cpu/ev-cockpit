# v0.36 — MediaEngine video akışına göre özgün Android arayüz

**Referans:** Kullanıcının 68,8 saniyelik `30681.mp4` ekran kaydı. Bu kayıt bir MediaEngine kullanıcı deneyimi videosudur; kaynak kod, ağ API'leri ve üçüncü taraf medya yayın lisanslarını içermez.

## Gerçek video akışından uyarlananlar

- Ana ekran: üstte kalıcı medya oturumu ve durumu; gerçek Media3 radyo oturumundan gelen başlık, oynat/duraklat, önceki/sonraki. AA bağlantısı canlı olarak doğrulanmadığı sürece **bağlı** gösterilmez.
- Ana sayfanın kaydırılabilir kart akışı: **TV**, **YouTube**, **Ekran Yansıtma** bağlantı seçenekleri, **Radyo**, **Podcastlar**; sonrasında **Film/Dizi/Maç**, Netflix/Prime Video, kısayollar, favoriler ve son içerikler.
- TV akışı: önce **M3U grup kategorileri ve gerçek adetleri**, sonra seçilen kategorinin kanalları; arama, favoriler, ızgara/liste geçişi ve yerel Media3 oynatma. Kaynak listesi boşken katalog uydurulmaz.
- Kendi web bağlantılarının adını düzenleyerek kısayol ekleme, uzun dokunmayla silme; yalnızca HTTPS.
- Podcast akışı: RSS aboneliği ekleme, podcast kaynak listesi, bölüm listesi, oynatma; yerel OPML içe/dışa aktarma. RSS, güvenli XML ayrıştırma ve azami 2MB boyut ile alınır. Bu sürümde podcast sesleri telefonun Media3 oynatıcısında çalar.
- Mevcut IPTV, radyo, program rehberi, film/dizi/maç kaynakları, YouTube/Netflix/Prime Video resmi telefon uygulamalarına geçiş ve yerel geçmişler korunur.

## Sınırlar

- MediaEngine'nın telifli görselleri, kapalı uygulama kaynak kodu, gizli sunucuları veya SDK'ları kopyalanmaz. Görseller uygulama içinde özgün Kotlin/Canvas bileşenleridir.
- Ekran videosunda YouTube MediaEngine arayüzü içinde görünür; resmî Android YouTube uygulaması **başka uygulamanın faaliyet ekranının içine gömülemez**. Kullanıcının önceki “tarayıcı değil doğrudan uygulama” tercihi doğrultusunda YouTube kartı kurulu resmî Android uygulamasını başlatır. Netflix/Prime Video da benzer çalışır; DRM/hesap veri aktarımı yoktur.
- Ekran yansıtma kartı bağımsız bağlantı/uygulama merkezine götürür; vites, hız, park işareti veya Android Auto ön ekranının sürüş-video kısıtları bypass edilmez.
- Kullanıcı M3U veya RSS eklemeden hayali 252 TV kanalı, korsan film, maç veya podcast listesi sunulmaz.
- Android 15 emülatör doğrulaması, gerçek electric vehicle Pure araç testinin yerine geçmez.

## Kaynak dosyalar

- `MediaEngineHomeDashboard.kt`, `MediaEngineAudioHeader.kt`: kalıcı oynatıcı ve ana sayfa.
- `MediaEngineTvPlanner.kt`, `MediaEngineTvBrowseActivity.kt`: TV kategorileri/kanallar.
- `PodcastSources.kt`, `PodcastBrowserActivity.kt`: RSS ve OPML.
- `UserQuickLinks.kt`: kişisel bağlantılar.
- `MainActivity.kt`, `AndroidManifest.xml`, `StreamingServiceCards.kt`: ekran bağlantıları.
- `app/src/test/java/com/evcockpit/mediahub/MediaHubUnitTest.kt`: kategori/RSS/OPML testleri.
