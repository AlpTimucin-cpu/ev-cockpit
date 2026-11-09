# EV Cockpit — Uygulama birleştirme mimarisi (v0.24)

## Tasarım prensibi

Bu proje başka geliştiricilerin APK dosyalarını birleştirip yeniden paketlemez. Dış uygulamaların kaynaklarından yalnızca belgelenmiş entegrasyon arayüzleri veya açık kaynak mimari bilgileri alınır. Uygun olmayan lisanslı/kapalı kod kopyalanmaz. Android Auto araç ekranında video ve sürüşte ön ekran oynatımı garanti edilmez.

| Araştırma kaynağı | Media Hub içindeki katkısı | Gerçek uygulama durumu |
|---|---|---|
| Fermata Auto | Media3 oynatıcı, M3U/HLS, kanal navigasyonu ve ses kataloglarını inceleme | Harici Fermata'nın kurulu paket sürümünü saptama ve telefonda açma |
| APTV | M3U tvg-id, XMLTV EPG, Now/Next ve kategori önizleme yaklaşımı | Apple/CarPlay uygulaması; Android'e taşınmadı |
| AABrowser | Çok sekmeli HTTPS tarayıcı; seçili bağlantıyı ACTION_VIEW ile AABrowser'a gönderme | Gerçek araçta YouTube sayfası kullanıcının bildirdiği tek video-web kanıtı |
| ScreenOnAuto | Resmî START_MIRROR / STOP_MIRROR Intent arayüzü | İsteğe bağlı yüklü ayrı uygulama; görüntünün araçta çıkması ayrı test |
| MirrorMobile | MediaProjection, AndroidX Car Surface ve bağlantı durum yönetimi konusunda kaynak incelemesi | GPL-3.0 kodu kopyalanmadı; kaynak proje arşivli ve sürüşte yansıtmayı durdurur |
| AAAD | Kurulu paket ve sürüm tespiti | APK kurma, gizli Android Auto yükleme veya sahte installer kaynağı yok |
| Shizuku | pingBinder + checkSelfPermission salt-okunur tanı | Shell/privilege yükseltme komutu gönderilmez |
| Screen2Auto | Bilinen orijinal ve AAAD paketini algılama, telefonda açma denemesi | Araç video projeksiyonunu Media Hub üzerinden garanti etmez |
| CarStream | Android Auto web/video yöntemine ilişkin geçmiş inceleme | Paket kontrolü, özel kod/servis entegrasyonu yok |
| DiPlay | Ekran aktarımı denemeleri ve UX fikirleri | Doğrulanmış paket/kamu API'si olmadığı için teknik referans |
| SportzX | Telefonda önceden kuruluysa uygulama kısayolu | Yetkisiz yayın listesi ve APK dahil edilmez |
| Android Auto | MediaLibraryService / MediaBrowser radyo arayüzü | Gerçek araç ses oturumu ayrıca doğrulanmalı |

## Uygulama içinde nerede?

- Ana Sayfa → Tüm uygulamaları ve bağlantıları yönet → tek panelden sürüm/durum ve kamuya açık eylemler.
- Kitaplık → Tüm Uygulamalar • Ortak Entegrasyon Merkezi.
- TV → Media3 HLS oynatıcı; Kitaplık → XMLTV program rehberi; Web Medya → AABrowser'a geçiş.
- Araç bağlantı raporu ve ses kitaplığı API testi telefonda kontrol içindir; gerçek EV Cockpit fiziksel testinin yerine geçmez.

## Test ve güvenlik sınırları

- Her paket için yalnızca açıkça bildirilen manifest query girişi kullanılır; geniş QUERY_ALL_PACKAGES izni istenmez.
- HTTPS URL'leri ve IPTV kullanıcı bilgileri rapor ekranına kopyalanmaz; durum raporu yalnızca paket sürümleri ve genel kapasite içerir.
- Her açılış kullanıcı tetiklidir. Hız, vites, park, Android Auto kategori/doğrulama ve DRM mekanizmaları değiştirilmez.
- EPG XMLTV için uzaktan DTD/harici varlık okuma kapalıdır; indirme, gzip açılım ve kayıt sayısı sınırlıdır.
- Google'ın araç ekranına video kabul politikalarını Media Hub değiştiremez. Hareket halindeki ses/radyo desteği ile park halindeki video denemeleri ayrı tutulur.

## Doğrulanmış kaynaklar

- https://github.com/AndreyPavlenko/Fermata
- https://github.com/kododake/AABrowser
- https://github.com/slzn/ScreenOnAuto-releases/wiki/Control-Mirroring-from-Another-App
- https://github.com/chenxiaolong/MirrorMobile
- https://github.com/shmykelsa/AAAD
- https://shizuku.rikka.app/
- https://aptv.app/
