# REC TV 22.0.2 ve medya kütüphanesi — v0.42 bağımsız inceleme

Tarih: 14 Ekim 2026. Kullanıcının sağladığı dosya: `rectv22.0.2-161mobilbox.apk`.

## Tekrar incelenen dosyanın ölçümleri

- Dosya boyutu: 72.219.379 bayt.
- ZIP/APK girişleri: 2.934.
- SHA-256: `00c666d984fe84fc819df6a0f66c37066f5e233e0fccd631aafd58e88fefc217`.
- Flutter AOT izleri: `lib/arm64-v8a/libapp.so` (9.896.880 bayt), `libflutter.so`.
- Yöntem: APK ZIP girdileri ve arm64 Flutter AOT modülünde statik metin imzası araması. Kurulum, uygulama oturumu veya canlı ağ trafiği analizi **yapılmadı**.

## Doğrulanabilen teknik ayrım

| İmza / alan | Yorum | EV Cockpit kararı |
| --- | --- | --- |
| `api.themoviedb.org`, `image.tmdb.org` | Film/dizi bilgi ve görsel API'si; video stream değil | Resmî TMDB API'si ve TMDB/JustWatch Türkiye sağlayıcı bilgileri |
| `movie/weekly/popular/`, `movie/by/actor/`, `episode/latest/` | REC TV'nin ayrı film/dizi/episode katalog rotalarına işaret ediyor | Özel route veya içeriği kopyalama |
| `subtitles/by/episode/`, `sources`, `m3u8` | Bölüm altyazısı/kaynak seçimi ve HLS mantığına işaret ediyor | Yalnızca kullanıcı izinli HTTPS/M3U kaynakları |
| `rectv_api_url_enc`, `rectv_domains_enc`, `domain_rotator.domains.v1` | Uzak ayar ve/veya şifreli konfigürasyon izleri | Deşifre etme, gizli anahtar aktarma yok |
| Firebase Database / Remote Config izleri | Uzak veri ve yapılandırma bileşeni, hak veya çalışan servis kanıtı değil | Bağımsız uygulama durumu ve resmi sağlayıcı bağlantıları |
| `BetterPlayer`, `youtube_player_iframe` | Flutter video bileşenleri mevcut | Özgün Android Media3 oynatıcı ve WebView/kullanıcı başlatımı |

İmzalarla dinamik HTTP isteklerinin varlığı veya başarısı kanıtlanamaz. Özel REC TV URL, access token, API kimliği, film akışı veya kaynak kodu bu depoya yazılmamıştır.

## v0.42 uygulama karşılığı

1. Kullanıcının eklediği kaynaklar, `VideoLibraryClassifier` ile Film/Dizi/Maçlar olarak sınıflanır.
2. `MediaLibraryBrowsePlanner` kategori gruplarını ve gerçek sayıları hesaplar; kategori, arama ve favori filtrelerini birleştirir.
3. `OfficialMediaFavorites` yalnızca resmî sağlayıcı kartlarının güvenli ID'lerini yerel SharedPreferences içinde saklar.
4. `RecentStore` ve `VodProgressStore` birleştirilerek gerçek ilerleme bilgisi olan son açılmış 8 film/dizi listelenir. Kullanıcının M3U tokenları katalog kimliği dışında kaydedilmez.
5. Blender açık film kartları kayıtlı ilerleme varsa etikette belirtir; medya sunucusundan bağlantı çözümlenemezse oynatım başarısı iddia edilmez.
6. Netflix ve Prime Video WebView'ları lisanslı DRM oynatıcısı değildir; kullanıcı talebiyle ilgili resmî Android uygulaması açılır. Araçta video için sürüş güvenlik kilidi kaldırılmaz.

## Test matrisi

- Gradle `:app:testDebugUnitTest` ve `:app:assembleDebug`: GitHub Actions çalışmasının sonucuyla ayrı doğrulanır.
- API 35 emulator: GitHub Actions smoke-test artefaktları ve loglarıyla ayrı doğrulanır.
- POCO F5 Android 15: fiziki cihaz erişimi olmadığından elle kurulum/ekran açma testi **yapılmadı**.
- electric vehicle Pure: fiziki araç bağlantısı ve ön ekran videosu **test edilmedi**.
- Canlı TMDB, Netflix, Prime, Blender, IPTV veya spor kaynakları: bu statik APK incelemesinde canlı yayın testi **yapılmadı**.

Entegrasyonlar yalnızca kullanıcı kaynaklarına veya resmî erişim/abonelik koşullarına tabidir; tüm kullanıcı verileri yereldir.
