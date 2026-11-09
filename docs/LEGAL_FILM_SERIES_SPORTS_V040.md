# v0.40 — Boş Film, Dizi, Maç ve TV ekranlarının kaynakla doldurulması

## Kullanıcı videolarından tespit edilen fark

- `30856.mp4` içinde **EV Cockpit** ekranlarında `Filmler · 0 içerik`, `Diziler · 0 içerik`, `Maçlar · 0 içerik` yazıyor. Bu önceki mimarinin `VideoStore` içinde yalnızca kullanıcının elle eklediği M3U/HTTPS adreslerini göstermesinden kaynaklanıyor.
- `30857.mp4` içinde **REC TV** afiş, isim, puan, yıl, bölüm, dizi, film, TV kanalları ve canlı rozetleriyle dolu katalog gösteriyor. REC TV'nin özel/şifreli film-dizi sunucusu veya ücretli spor akışları bu APK'dan yetkili yayın izniyle elde edilmiş değildir. REC TV'nin doğrudan kaynaklarına erişildiği iddia edilmez.

## Ekranda hemen görünecek resmî kaynaklar

- **Filmler:** Blender Open Movies'ten `Big Buck Bunny` (Creative Commons BY), Blender'ın PeerTube örnek/ayrıntı API'sinden açılacak açık filmler; ayrıca tabii'nin film arşivine resmî uygulama bağlantısı.
- **Diziler:** Blender'ın `Caminandes` açık animasyon serisi; tabii içindeki `Gassal`, `Yeşil Deniz Milenyum` ve genel dizi kitaplığına resmî uygulama bağlantıları. tabii kartları ilgili bölümü doğrudan oynatmaz, tabii ana uygulamasını açar.
- **Maçlar:** TRT Spor ve tabii resmî uygulamaları, TRT Spor Yıldız resmî yayın akışı. Karşılaşmaların erişimi güncel yayın hakkı/abonelik/bölgeye bağlıdır; `beIN Sports`, `S Sport` gibi ücretli yayınların video akışı entegre edilmiş **değildir**.
- **TV:** MediaEngine tarzı kanal ekranı boşsa `TRT Haber` açık yayını ekleme düğmesi; resmî TRT Spor ve tabii uygulamaları. `iptv-org` spor kategori listesi kullanıcı izni ve içerik hakkı hatırlatmasıyla içe aktarılabilir; her kanalın çalışması veya lisansı garanti değildir.

## Nasıl çalışır?

- `OfficialMediaDirectory.kt`: resmî sağlayıcı ve açık film seçeneklerinin ağsız, anında görüntülenen listesi.
- `BlenderMovieFeed.kt`: internet varsa, `video.blender.org` halka açık PeerTube arama API'sinden Blender Open Movies video adlarını ve küçük kapaklarını sorgular. Yanıtın kaynak kanalını doğrular, isteğe bağlı görüntüler; sunucu kapalıysa statik kartlar yine görünür.
- `BlenderOpenVideo.kt`: yalnızca `video.blender.org` resmi API'deki Blender Open Movies video kimliğini çözer; varsa HLS/MP4 adresini kullanıcı dokunduğunda Media3'e verir. API, ağ, CDN veya format değiştiğinde oynatım hatası gösterilir ve resmi Blender sayfasına kullanıcı onayıyla geçiş önerilir.
- `MediaLibraryHubActivity.kt`: resmî kaynak kartlarını kişisel M3U içeriğinden ayrı olarak gösterir; kullanıcıya `X kendi yayın • Y resmî seçenek` sayacı sunar; resmî sağlayıcı veya açık film girişini başlatır.
- `MediaEngineTvBrowseActivity.kt`: boş TV ekranına resmî kaynak ve isteğe bağlı açık kanal seçeneklerini ekler.
- TMDB film-dizi metadata keşfi hâlâ kullanıcı tarafından sağlanan kişisel API anahtarı gerektirir; bu yeni ücretsiz kaynak ekranını kullanmak için TMDB anahtarı gerekmez.

**Önemli sınırlar:** Film, dizi ve maç listeleri artık seçim olmadan da kaynak seçenekleri sunar, ancak REC TV içindeki telifli katalog/videoları birebir içermez. Resmî uygulama açmak abonelikli içeriği uygulamanın kendi oynatıcısında oynatmak değildir. Blender oynatma adresinin, bütün cihaz ve coğrafyalarda çalışacağı garanti edilemez. Araç hareketinde Android Auto ön ekran video engelleri aşılmaz.

## Referanslar

- https://peach.blender.org/about/
- https://durian.blender.org/about/
- https://video.blender.org/videos/watch/bf1f3fb5-b119-4f9f-9930-8e20e892b898
- https://www.tabii.com/tr
- https://play.google.com/store/apps/details?id=com.trt.tabii.android
- https://play.google.com/store/apps/details?id=com.trt.trtspor
- https://www.trt.net.tr/yayin-akisi
