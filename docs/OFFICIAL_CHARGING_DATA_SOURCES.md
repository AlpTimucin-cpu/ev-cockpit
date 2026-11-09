# Türkiye Resmî Şarj Verisi • Kaynak ve Güncelleme Politikası

**Tarih:** 18.10.2026 · **Durum:** Kısmi entegrasyon; canlı soket API'leri henüz yetkilendirilmedi.

## İncelenen iki temel site

### 1. EPDK – resmî kaynak
- Resmî sorgulama: https://lisans.epdk.gov.tr/epvys-web/faces/pages/lisans/elektrikSarjAgiIsletmeci/sarjIstasyonuOzetSorgula.xhtml
- Kamuya ilan edilmiş istasyon REST servisi: https://apigateway.epdk.gov.tr/sarjIstasyonlari
- Swagger: https://apigateway.epdk.gov.tr/sarjIstasyonlari?swagger=
- Lisans REST servisi: https://apigateway.epdk.gov.tr/sarjAgiIsletmeciLisansiSorgula
- EPDK Şarj@TR: https://www.epdk.gov.tr/Detay/Icerik/1-3428/serbest-erisim-platformu--sarj%40tr

**Gerçek bulgu:** Swagger 2.0, GET `/` isteğinde **required JSON-string body**
örneği yayımlıyor. Alanlar `lisansNo, sarjIstasyonuNo, markaAdi,
yesilSarjIstasyonuMu, sarjIstasyonuAdi, hizmetSekli`.
Bu, istemci açısından alışılmadık bir sözleşme; JSON sonuç şeması
Swagger'da tanımlanmamış. Örnek body:
`{"lisansNo":"","sarjIstasyonuNo":"","markaAdi":"","yesilSarjIstasyonuMu":"","sarjIstasyonuAdi":"","hizmetSekli":""}`
Doğru sonuç şeması doğrulanmadan, tüm istasyonları gerçekten içe
aktardığımızı iddia etmeyiz. `scripts/epdk_station_probe.py` yalnızca
bir sorgu yapıp yanıtın **alan adlarını ve sayısını** yazdırır,
ham istasyon verisini veya kişisel bilgileri depolamaz.

EPDK Şarj@TR istasyon, soket tipi, güç, ücret ve müsaitlik gösterir.
Ancak tek başına kamuya açık bir ekran bulunması, uygulamanın bu
servislerinin belgesiz uç noktalarını serbestçe kullanma izni vermez.

### 2. Şarj Haritası – karşılaştırma ve potansiyel işbirliği
- Site: https://www.sarjharitasi.tr/
- Kaynak açıklaması: https://www.sarjharitasi.tr/hakkimizda/

Site kendisinin EPDK + operatör kaynaklarını birleştirdiğini;
günlük tarife kontrolü ve bazı operatörlerde anlık durumu desteklediğini
yayımlıyor. Ancak sitede programatik yeniden kullanımı onaylayan
açık bir API/lisans sözleşmesi doğrulanamadı.

**Karar:** Şimdilik harita içinde resmî dış bağlantı olarak bulunur.
Siteye özel JavaScript analiz ederek gizli uç noktalardan toplu veri
kopyalama veya oturum belirteci kullanımı yok. Sahibi izin verirse
belgelenmiş API/sözleşme üzerinden veri sağlayıcısı eklenebilir.

## Fiyat güncellemesi
İlk desteklenen **doğrudan resmî operatör kaynakları**:
- Trugo: https://www.trugo.com.tr/price
- ZES: https://zes.net/tr/fiyatlandirma

`scripts/update_official_charge_prices.py`:
- Her kaynak için izinli HTTPS alan adını sabit tutar.
- Trugo'nun lokasyon bazlı AC/DC aralıklarını, ZES'in DC-1 ve DC-2
  fiyatlarını ayrı tutar; tek ve yanlış fiyat yazmaz.
- HTML kalıbı değişirse yanlış fiyat tahmin etmeyip hata üretir.
- Son başarılı doğrulama zamanını saklar; başarısız kontrol sırasında
  eski tarifeye **yeni doğrulama zamanı vermez**.
- Anlık fiyatı olmayan istasyonlara ağ genel tarifeyi yapıştırmaz.
- Yerel para birimi TL/kWh ve veri kaynağı bağlantısını korur.
- Snapshot: `data/charging/operator_prices.json`.
- Uygulama: `OfficialChargingTariffs` sadece **36 saatten genç**
  kayıtları güncel olarak işaretler; diğerleri *ESKİ VERİ* görünür.
- GitHub Actions: `.github/workflows/official-charge-prices.yml`,
  her gün **06:15 Türkiye saati** (03:15 UTC).

**ÖNEMLİ:** GitHub scheduled workflows sadece varsayılan dal üzerinde
çalışır. Şu an geliştirme dalındaki zamanlayıcı, PR ana/dal ile
birleştirilene kadar **otomatik her gün çalışıyor sayılmamalı**.
Test amacıyla geliştirme dalında push/manual run tanımlanmıştır.

Diğer operatörler (Eşarj, WAT, En Yakıt, Otojet, Voltrun, Astor vb.)
ancak web tarifesi/izinleri ve fiyat yapısı doğrulandıktan sonra
kapsama alınacak. Yetkisiz bir tarife yenileme göstergesi yok.

## Soket bazında canlı durum
Gerçek zamanlı `AVAILABLE / OCCUPIED / OUT_OF_SERVICE / RESERVED`
durumları istasyon ve **soket kimliğine** bağlıdır.

- Sadece operatörün **izinli resmî API/OCPI** bağlantısından alınan
  yetkilendirilmiş kayıtlarla `CANLI` gösterilmelidir.
- Her durumun servis, soket ID, gözlem zamanı, son alım zamanı ve
  kaynak bağlantısı olmalı.
- Güncellik örneği: **120 saniye** aşan veri **BİLİNMİYOR** statüsüne
  düşmeli; son bilinen `AVAILABLE` yeşil pin olarak kalmamalı.
- Şarj Haritası veya mobil uygulama oturum belirteci kopyalamak,
  belgesiz API kazımak veya kimlik doğrulamayı atlatmak yok.
- EPDK Şarj@TR web sayfasına yönlendirme, canlı veri entegrasyonunun
  kendisi değildir.
- Henüz operatör onaylı ve test edilmiş soket-durum kimlik bilgisi
  bulunmuyor. Uygulama bu durumda bilinmiyor yazar.

## Veri modeli ve kalite güvencesi
Her istasyon için: `source_id, official_station_no, license_no,
operator_license, title, address, coordinates, socket_ids,
connector_kind, nominal_kw, public_or_private, updated_at, source_url,
confidence`; eksik alan **null**.

Her tarife için: `operator, price_scope, connector_band, price_try_per_kwh,
source_url, verified_at`, istasyon/soket özel indirimleri bağımsız.

Operatör/istasyon eşleştirme önceliği:
1. EPDK **resmî istasyon numarası** (varsa birebir)
2. Operatörün onaylanmış station_id + lisans numarası
3. Adres/koordinat fuzzy: **yalnızca inceleme adayı**, otomatik
   doğrulanmış birleşme yapılmaz.

İşlemler güvenlik, kişisel veri ve kullanım koşullarına uygun tutulmalıdır.
Tam Türkiye kapsamı, günlük yüzlerce operatör fiyatı veya anlık
müsaitlik şu anda doğrulanmamıştır.
