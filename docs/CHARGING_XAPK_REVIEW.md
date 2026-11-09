# Şarj@TR 1.1.8 XAPK – Statik Envanter ve Güvenli Entegrasyon

İnceleme tarihi: 18 Ekim 2026. Bu belge kullanıcı tarafından sağlanan
**Şarj@TR_1.1.8_APKPure.xapk** paketi ile EPDK'nın herkese açık
belgelerine dayanmaktadır. **Uygulama APKPure üzerinden sağlanmıştır;
resmî Google Play imzasıyla eşleştiği doğrulanmamıştır.**

## Dosya kimlikleri
- XAPK SHA-256:
  \`0a7c28ce68666914b8d7d661f88b7c0dc49965b931ed5f2acabc942aa24dceb3\`
- Base APK SHA-256:
  \`ad9bde8500a473b94195e861e2b7db3de6889cdfabe8ff7028bdf01bf4a88607\`
- Paket: \`tr.gov.epdk.sarjetTR\`; sürüm \`1.1.8\`; code \`27\`
- Ana APK + \`config.arm64_v8a\`, \`config.en\`, \`config.es\`, \`config.hdpi\`.
- Flutter: \`libapp.so\` + \`libflutter.so\`. Statik resim/çeviri
  kaynakları, harita görünüm ayarları mevcut; **ülke çapındaki canlı
  istasyon veritabanı veya anlık soket durumu XAPK içinde yok**.
- Paket manifestinde INTERNET, ACCESS_FINE_LOCATION,
  ACCESS_COARSE_LOCATION, POST_NOTIFICATIONS ve Firebase bildirimiyle
  ilgili izinler yer alıyor. Bunlar Şarj@TR uygulamasının kendi izinleri;
  **EV Cockpit'a aktarılmıyor**.

## APK'da doğrulanan işlev haritası

| İşlev | XAPK kanıtı | EV Cockpit planı |
|---|---|---|
| Müsait/kullanımda/rezerve/arıza/bakım/mesai dışı | tr.json çevirileri | Durum enum'u; veri yoksa BİLİNMİYOR |
| Type 2, CCS, CHAdeMO | Yerel soket simge dosyaları | Resmî EPDK soket tipinden filtre |
| İl, ilçe, marka, operatör, mesafe, fiyat aralığı | Çeviri + Flutter sınıf adları | İlk aşama operatör / adres / soket; diğerleri faz 2 |
| Saatlik değişebilen fiyat | TimedPrice sınıf adı; price details/start/end | Zaman aralıklı tarife modeli ve 30 dk veri güncelliği koruması |
| Ödeme yöntemleri, rezervasyon | Flutter sınıf adları, çeviri | Resmî veri/operatör bağlantısı gerektiğinde |
| Mobil şarj istasyonları | MobileStation DTO, çeviriler | Ayrı resmî veri kaynağı bulunmadan liste uydurulmaz |
| Navigasyon | Açıklamalar ve map launcher paketleri | Mevcut Google Maps aktarımı |
| Arıza bildirimi | Çeviri ve arayüz | Yetkili operatör destek linki eklendiğinde |
| Canlı soket müsaitliği | Resmî EPDK uygulaması açıklaması | Onaylı canlı API henüz bağlı değil |
| İstasyon listesi / koordinat / soket gücü | EPDK resmî REST servisi | **13.118 halka açık kaydın resmi snapshot'u zaten eklendi** |

## Teknik bulgu ve erişim sınırı
Derlenmiş Flutter kodunda \`sarjtr.epdk.gov.tr\` üzerinde Şarj@TR
uygulamasına ait bir arka uç adresi referansı bulundu. Ancak yalnızca
bir URL'nin APK içinde olması onu üçüncü taraf istemciler için
**belgelenmiş, sınırsız kullanıma izinli API** yapmaz.
Bu arka uç için uygulamanın özel kimlik bilgileri, korumalı oturum
belirteçleri veya gizli bağlantı yöntemleri kullanılmamıştır.
Yetki olmadan canlı soket bilgisi çekilmemiştir.

**EPDK'nin zaten yayımladığı ve doğruladığımız veri yolu**:
\`https://apigateway.epdk.gov.tr/sarjIstasyonlari\`.
Bizim resmî kaynağımız:
\`data/charging/epdk_public_stations.json.gz\` (13.118 halka açık istasyon,
konum ve soket metadatası; özel 3.908 kayıt hariç).
\`data/charging/epdk_public_snapshot_meta.json\` doğrulama ve kapsama
bilgilerini içerir. Bu dosyalarda anlık doluluk veya sokete özgü güncel
tarife **yoktur**.

## Güvenlik ve lisans
Şarj@TR'nin görselleri, kodu, fontları veya APK ikilileri kendi
uygulamamıza kopyalanmadı. Sadece doğrulanabilir EPDK kamu verisini ve
genel şarj kullanıcı deneyimi gereksinimlerini temel alan
**özgün Kotlin uygulama kodu** yazıldı. XAPK'nın orijinal geliştirici
imzası ayrıca kontrol edilmedi. APKPure paketinin kurulumunu
önermiyoruz; resmî Şarj@TR uygulaması için Google Play'den doğrulama
yapılabilir.

## Güncel erişim seviyesi
- EPDK lisans, halka açık istasyon, adres, koordinat, soket tipi/güç alanı: **MEVCUT**.
- Trugo ve ZES yayımlanan referans AC/DC tarifeleri: **MEVCUT**.
- Türkiye'deki bütün operatörler için soket bazlı anlık doluluk: **YOK**.
- Saatlik/soket bazlı kesin fiyat ve rezervasyon: **YOK**.
- EPDK/operatörlerin yetkilendirdiği canlı servis entegrasyonu: **BEKLİYOR**.

EPDK resmî bilgi:
https://www.epdk.gov.tr/Detay/Icerik/12-1040/enerji-donusumu
Google Play:
https://play.google.com/store/apps/details?id=tr.gov.epdk.sarjetTR
