# v0.41 — REC TV kaynak ayrımı, Türkiye'de nerede izlenir?

## REC TV APK'da doğrulanmış veriler

İncelenen kullanıcı APK'sı: `rectv22.0.2-161mobilbox.apk`, Android paket kimliği `com.rectv.shot`. Flutter `lib/arm64-v8a/libapp.so` derlemesindeki okunabilir metinler üzerinden **statik** inceleme yapılmıştır.

- `https://api.themoviedb.org/3/trending/movie/week` ve `https://image.tmdb.org/t/p/w500` / `w1280`: **film bilgileri ve afişler**. TMDB video akışı sunmaz.
- `movie/weekly/popular/`, `movie/by/actor/`, `episode/latest/`, `subtitles/by/episode/`, `sources` vb. yollar: REC TV'nin **ayrı film/dizi kataloğu, bölüm ve oynatma kaynağı** mantığına dair işaretler.
- `rectv_api_url_enc`, `rectv_domains_enc`, `domain_rotator.domains.v1`: **uzaktan/yapılandırılan servis adresleri** ile ilişkili veriler. Bu, canlı servis adresinin ve hakların doğrulandığı anlamına gelmez.
- Firebase Remote Config/Realtime Database bileşenleri, BetterPlayer, HLS ve YouTube IFrame yapıları var. Uygulamanın servislerine kullanıcı oturumuyla bağlanılmadı, dinamik HTTP trafiği kaydedilmedi.
- APK içindeki gömülü token/key benzeri diziler ve şifrelenmiş bağlantılar **açığa çıkarılmadı veya projeye entegre edilmedi**. Yayın hakları ve izinsiz erişim durumu doğrulanmayan film/dizi/maç akışları aktarılmadı.

## EV Cockpit'a eklenen karşılık

- `TmdbCatalogActivity` içindeki film/dizi detayında **Türkiye'de nerede izlenir?** seçeneği.
- `TmdbMetadata.loadWatchProviders`: TMDB'nin resmî `/3/movie/{id}/watch/providers` ve `/3/tv/{id}/watch/providers` API'sine TLS üzerinden istek. Sadece **Türkiye (`TR`)** sonuçları gösterilir.
- Kullanılabilirlik: **abonelik, ücretsiz/reklamlı, kiralama, satın alma**. Kaynak **JustWatch üzerinden TMDB**; sağlayıcı bilgilerinin değişebileceği belirtilir.
- **İzleme seçeneklerini aç**: sabit resmî `www.themoviedb.org/.../watch?locale=TR` sayfasını Android'e açma isteği. Bu bir video akışı veya DRM çözme işlevi değildir.
- TMDB API Read Access Token hâlâ kullanıcı tarafından girilir, APK içine yazılmaz ve sadece çalışan oturum belleğinde kalır. Araç sürüşündeki video kısıtları değiştirilmez.
- Resmî sağlayıcıların linkleri doğrudan lisanslı servise yönlendirebilir; hesap/abonelik koşulları değişmez.

## Test ve sınırlamalar

- `TmdbWatchPolicy.kt` yalnızca doğrulanmış pozitif medya ID'lerini kabul eder, resmî API yolu ve TMDB izleme bağlantısı üretir.
- `MediaHubUnitTest` film ve dizi yollarını, Türkiye izleme sayfasını ve geçersiz ID reddini test eder.
- Otomatik birim testlerinin geçmesi, canlı TMDB verisinin ya da kullanıcı hesabındaki aboneliklerin başarıyla doğrulandığı anlamına gelmez.

TMDB API/Watch Providers dökümantasyonu:
- https://developer.themoviedb.org/reference/tv-series-watch-providers
- https://developer.themoviedb.org/reference/movie-watch-providers
- https://developer.themoviedb.org/docs/authentication-application

**Atıf:** This product uses the TMDB API but is not endorsed or certified by TMDB. Watch provider data provided by **JustWatch**.
