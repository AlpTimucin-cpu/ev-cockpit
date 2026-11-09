# v0.35 — MediaEngine temalı film, dizi, maç kütüphanesi

## Kurulan modüller

- MediaEngine'nın **genel koyu kart, büyük düğme, içerik sekmesi** tasarım yaklaşımından esinlenen özgün Android arayüzü.
- Ana ekran → **FİLMLER**, **DİZİLER**, **MAÇLAR** kartları → telefon içindeki yeni \`MediaLibraryHubActivity\`.
- Filtreleme: var olan özel M3U kütüphanesinde \`Filmler/Movie/VOD/Sinema\`, \`Diziler/Series\`, \`Spor/Maçlar/Football\` gibi grup adları. \`S01E02\` biçimli bölüm isimleri de dizilere ayrılır.
- Arama, favoriler, içerik kapakları varsa M3U \`tvg-logo\` görselleri ve yeni içerik ekleme.
- Yeni film/dizi/maç eklerken kullanıcı **hak sahibi olduğu HTTPS yayını** girer; kategoriye özel grup adıyla kaydedilir.
- \`PlayerActivity\` içinde Media3 ExoPlayer ile HLS, DASH ve formatı saptanabilen HTTPS video kaynakları açılır.
- Film ve dizi için sadece içerik ID'si ve son oynatma milisaniyesi yerelde tutulur; oturum sona erince 30 saniye kalmışsa ilerleme temizlenir. Maçlar için kaldığın yerden devam bilgisi tutulmaz.
- Mevcut M3U aktarımı, XMLTV program rehberi, kanallar, radyo ve YouTube/Netflix/Prime Video resmî uygulama kısayolları korunur.

## Kaynak ve lisans sınırı

Kullanıcının yüklediği \`rectv22.0.2-161mobilbox.apk\` dosyasındaki özel Flutter/Dart kodu, mobil uygulama görselleri, reklam yazılımları, kapalı API'ler, film/dizi/maç akış URL'leri, sunucu kimlik bilgileri, DRM anahtarları veya lisanslı içerikler **bu projeye kopyalanmadı**. REC TV, MediaEngine ile aynı uygulama değildir ve oradaki katalog/API adreslerine sahip olmadan hizmet içeriğini buraya taşıdığımız iddia edilemez.

Bu, **kullanıcının yasal erişimi bulunan kaynaklar için çalışan yerel katalog ve oynatıcıdır**. Bir IPTV M3U listesi film/dizi/sports kategorileri taşımıyorsa bunlar otomatik dolmaz. Yeni yayın bağlantısı oluşturulmaz veya internette korsan maç/film kaynakları aranmaz.

## Araç davranışı

Android Auto yalnızca mevcut sürücü güvenli ses/radyo servisinden yayın yapar. Video ekranı telefonda veya desteklenen park durumlarında kullanılabilir; araç hareketindeyken ön ekranda video kısıtlarını atlatma gerçekleştirilmez. Gerçek electric vehicle testi gereklidir.

## Dosyalar

- \`VideoLibraryClassifier.kt\` — bağımsız grup ayrıştırıcısı.
- \`MediaLibraryHubActivity.kt\` — MediaEngine temasına yakın katalog, arama ve kaynak ekleme.
- \`VodProgressStore.kt\` — ilerleme kaydı (sadece yerelde).
- \`PlayerActivity.kt\` — VOD kaldığın yerden devam ve DASH algılama.
- \`CockpitDashboard.kt\`, \`MainActivity.kt\`, \`AndroidManifest.xml\` — menü geçişleri.
- \`MediaHubUnitTest.kt\` — kategori sınıflandırması testleri.
