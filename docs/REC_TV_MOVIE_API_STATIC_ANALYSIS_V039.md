# REC TV 22.0.2 (161) — Film ve dizi sunucu kaynak analizi

## İncelenen materyal
Kullanıcının sağladığı **rectv22.0.2-161mobilbox.apk** dosyası. Flutter AOT verisi `lib/arm64-v8a/libapp.so`, yerleşik Flutter varlıkları ve uygulama manifesti incelendi. Statik analiz, uygulamanın sunucusuna giriş yapılmadan yürütüldü; etkin oturum veya sunucu yetkilendirmesi elde edilmedi.

## Doğrudan görülen teknik izler

1. **TMDB / The Movie Database**: Derlenmiş yazılımda `https://api.themoviedb.org/3/trending/movie/week` ile `https://image.tmdb.org/t/p/w500` ve `w1280` yolları bulunuyor. Film/dizi adları, afişler ve içerik keşfi için bu veri kaynağının kullanıldığı anlaşılıyor. **TMDB izlenebilir film yayını sağlamaz**; içerik bilgisi ve görsel veri sağlar.
2. **Özel yayın kataloğu**: Uygulama derlemesinde `movie/weekly/popular/`, `movie/by/actor/`, `movie/by/filtres/`, `series_progress`, `moviePlayback` ve film/dizi katalog isteklerine ait yöntem isimleri var. Bazı yollar sabit anahtar/kimlik benzeri değerler içeriyor; bu tür değerler rapora ve projeye taşınmadı.
3. **Firebase Remote Config**: `rectv_api_url_enc` adlı uzak yapılandırma anahtarı derlenmiş metinlerde bulunuyor. Adından özel sunucu adresinin uzaktan ve kodlanmış biçimde ayarlandığı düşünülebilir; ancak bu **çıkarım**, anahtarın canlı değerinin veya şifrelemesinin doğrulanmış çözümü değildir.
4. **Oynatıcı**: `better_player_channel`, `video_player`, `index.m3u8`, `master.m3u8`, `youtube_player_iframe` bileşenleri mevcut. Bunlar medya oynatma mimarisini gösterir; belirli yayınların hakkına veya çalışır URL'lere erişim kanıtı değildir.

## Bizim uygulamadaki karşılık

- **MediaEngine tarzı film/dizi kartları** korunur.
- Resmî **TMDB API** ile, yalnızca kullanıcı tarafından uygulama içinde girilen TMDB Read Access Token kullanılarak film ve dizi adı, yıl, puan, özet ve afiş getirilebilir. Anahtar kod içinde ve yerel depolamada tutulmaz.
- **TMDB keşif** ekranında popüler başlıklar ve arama yer alır. Listede seçilen başlığın **kullanıcının mevcut video kütüphanesindeki** kaynakları aranır; varsa Media3 ExoPlayer ile oynatılır.
- İçerik için kendi yayın kaynağı yoksa **oynatılabilir kaynak bulunamadı** denir; bozuk veya izinsiz bağlantılar uydurulmaz.
- REC TV'nin özel servisine yetkisiz giriş, kripto/DRM çözme, film/dizi akışlarını veya gizli erişim kimliklerini yeniden kullanma işlemi yapılmadı.
- Film/dizi bilgilerini gösterirken TMDB atıf metni ve resmî TMDB atıf sayfası bağlantısı bulunur. Geliştirici TMDB'nin marka/atıf ve kullanım şartlarını ayrıca doğrulamalıdır.

## Nasıl kullanılır?

1. TMDB hesabı açıp API ayarlarından kendi **API Read Access Token** değerini edin.
2. EV Cockpit → **Filmler** veya **Diziler** → **TMDB film / dizi keşfet** → **API anahtarı**.
3. Filmi/diziyi ara; içerik bilgisi ve afişi aç.
4. **Kütüphanemde ara** ile kendi M3U/HTTPS kaynaklarında eşleşen filmi kontrol et.

**Sınır:** Bu işlem REC TV içeriğini, Netflix/Prime Video hesaplarını ya da özel yayın kütüphanelerini kopyalamaz. Kendi kaynakların dışında filmi doğrudan izleme özelliği vaat edilmez. Araç ön ekranında sürüş sırasında video kısıtları değiştirilmez.
