# FAZ 3 — EPDK kaynak bütünlüğü, DC/CCS2 ve kayıt kanıtı
**13 Kasım 2026 — DRAFT; geliştirme / gerçek telefon-ID.4 kabulü bekleniyor**

## Yetki, veri kaynağı, değişiklik
- Kullanıcı Faz 3'e açıkça geçti; önceki Faz 0/1/2 fiziksel testleri
  geriye dönük başarılı ilan edilmedi. Faz 2 PR #56 taslak.
- Resmî yayımlanmış EPDK istasyon REST:
  https://apigateway.epdk.gov.tr/sarjIstasyonlari
  EPDK belge: https://www.epdk.gov.tr/Detay/Icerik/3-0-226/web-servisler
  EPDK Şarj@TR: https://www.epdk.gov.tr/Detay/Icerik/1-3428/serbest-erisim-platformu--sarj%40tr
- Kaynak, GitHub'daki tarihli EPDK-derived `.json.gz` yayın snapshot'ı.
  **Doğrudan canlı EPDK API çağrısı değil**. Gerçek yayın doğrulama tarihi
  ve **uygulamanın GitHub snapshot indirme saati ayrı**.
- 17 Ekim 2026 tarihli mevcut `data/charging/epdk_public_snapshot_meta.json`
  raporu **güncel değil**. Otomatik iş akışı main'de etkin değil;
  yeni gerçek kaynak yenileme kanıtı henüz elde edilmedi.
  Bu veri ile rota DC/CCS2 önerileri **gizlenir**.
- JSON bütünlük: resmî EPDK source URL, TR/complete (varsa),
  tam all/public/geocoded kayıt hiyerarşisi, tam dizi uzunluğu,
  5.000–100.000 kayıt, tüm kayıtlar HALKA_ACIK, tekil EPDK ID,
  Türkiye koordinatları, 24'ü aşmayan soket dizisi ve doğru
  socket_count. Tek yanlış kayıt bile atomik reddedilir.
- **72 saatten eski veya ileri tarihli** yeni snapshot kabul edilmez.
  AndroidX `AtomicFile` ile kesintiye dayanıklı disk yenileme.
  Network yoksa eski veri kaynak tarihiyle işaretlenir; eski
  veri **haritada ve rota tavsiyesinde kullanılmaz**.
- Debug Tanılama: son gerçek indirme denemesi, son başarılı dosya
  indirme zamanı, snapshot tarihi, doğrulanmış koordinatlı kayıt,
  halka açık ham kayıt sayısı, hata durumu. VIN/MAC/GPS/raw API yok.

## CCS2/güç/araç uyumluluğu
- EPDK istasyon adı, işletmeci, adres, koordinat, DC/CCS soketi
  gösterilebilir (kaynak güncelse). Yaklaşık 5 km rota koridoru
  ve Android Auto ConstraintManager limiti korunur.
- `soketGucu` ham rakam olarak geldiyse birimi **belirsiz**;
  kesin `kW` yazılmadıkça nominal kW çıkarılmaz.
- Kullanıcı araç profilinde DC tepe kW onayladıysa ve doğru soketin
  açık birimli kW gücü biliniyorsa TEORİK kabul üst sınırı:
  `min(soketKw, araçTepeKw)`. Bu gerçek şarj hızı değildir;
  batarya sıcaklığı/SoC/şarj paylaşımı daha düşük olabilir.
- Trafik/soket canlı müsaitliği bu snapshot'tan **çıkarılamaz**.
  Hız ve bekleme süresi uydurulmaz.
- Fiyat yalnız doğrulanmış lisanslı operatör kaynağından;
  istasyona özgü kaynak bulunmazsa **fiyat bilgisi yok**.
  Kullanıcı tarifeleri/operatör kapsamı ayrı kabul gerektirir.
- Tahmini varış SoC sadece doğrulanmış BMS+GPS, teyitli araç
  profili ve güncel rota-dışı mesafesiyle etiketli MODEL;
  diğer koşullarda “kullanılamıyor”. Otomatik durağa ekleme,
  kaynağın 72 saati geçmesi hâlinde engellenir.

## CI ve test
- `EpdkProvenancePolicyTest`: 72 saat, gelecekteki veri, kaynaksız
  kayıt, araç tepe gücü min.
- `EpdkSnapshotParserTest`: 5.000 istasyonlu tam metadata geçişi
  ve tek geçersiz kaydın atomik reddi.
- `CarChargingListPolicyTest`: eski snapshot rota listesi yok,
  güç birimsizse yok, araç tepe gücü teorik.
- Python statik regresyon + lint + JVM + assembleDebug + Gitleaks +
  R8 smoke; CI yeşil olması fiziksel test kanıtı değildir.

## Telefonda ve park hâlindeki ID.4'te kabul
1. Güncel HEAD Actions Debug ZIP'den `com.evcockpit.debug` kur.
2. Ayarlar → Debug Tanılama: EPDK son deneme/başarı, EPDK snapshot
   tarihi, kayıt sayıları, hata. 17 Ekim eski ise ESİKAYNAK.
3. Şarj ekranında kaynak tarih/indiriliş, DC/CCS ve birimi açık kW
   sayısı, fiyat kapsamı notu. Eski veri için harita/öneri yok.
4. Yenile: gerçekten yeni EPDK doğrulamalı snapshot indirilirse
   5 km koridor DC/CCS2 ad/operatör/konum/teorik üst güç göster.
5. Telefon şarj istasyonu detayında doğrulanmamış fiyat yerine
   kullanılamıyor, tek operatör genel tarifesi varsa kapsam/UTC yaz.
6. ID.4 Android Auto parkta PlaceList host limitleri, eski verinin
   reddi, EPDK kaynak zamanı, görüntü kayması ve rota çizgisi.
7. Gerçek doğrulanmış SoC yoksa varış SoC ve süre model
   uydurulmuyor; görüntüleri maskeli paylaş.

**Faz 3 onayı olmadan main merge/Release/Faz 4 YOK.**
