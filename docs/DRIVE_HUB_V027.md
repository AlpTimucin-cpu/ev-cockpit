# EV Cockpit Drive Hub — v0.27 tasarım ve mimari

**Uygulama:** EV Cockpit Drive Hub (`com.evcockpit.mediahub`). Yüklenen FermataX APK'sındaki fikirlerden ve açık kaynak FermataX modüler arayüz mimarisinden esinlenen, **tamamen yeni yazılmış** Android telefon arayüzü. FermataX'in telifli ikonları, APK bileşenleri, native VLC ikilileri veya GPL kod dosyaları bu uygulamaya kopyalanmadı.

## Görsel dil

- **Koyu lacivert** ana arka plan; okyanus camgöbeği ve açık amber vurgu.
- Sade **DRIVE HUB / ID⁴** görsel kimliği ve özgün medya + otomobil simgesi.
- Android 15 üzerindeki sistem çubuklarına uyumlu tek sayfalı scroll düzeni.
- Birincil navigasyon: Ana Sayfa, Canlı TV, Radyo, Kaynaklar.
- Sabit hesap/tanılama durumları: batarya, SoC, OBD ya da gerçek araç bağlantısı **ölçülmeden varmış gibi gösterilmez**.

## Ana ekran bileşenleri

1. **Media Control hero:** kayıtlı TV, radyo ve favori sayıları, uygulamanın gerçek yerel verilerinden.
2. **Şimdi dinleniyor / Kaldığın yerden:** Media3 radyo oturumu oynuyorsa radyo adı ve gerçek duraklatma düğmesi. Aksi hâlde kaydedilmiş son TV/radyo ve tek dokunuşla devam.
3. **4 ana medya kartı:** Canlı TV → mevcut Media3/EPG oynatıcı; Radyo → ses kataloğu; Web Medya → HTTPS tarayıcı; Kitaplığım → M3U, EPG ve kaynaklar.
4. **Hızlı bağlantılar:** YouTube Web ve yüklüyse resmi Netflix uygulaması.
5. **Son açılanlar:** en fazla 5 doğrulanmış ve tekrar etmeyen yerel medya geçmişi girişi. Silinmiş/eski ID'ler atlanır, URL ve erişim belirteçleri gösterilmez.
6. **EV Cockpit Bağlantı Merkezi:** gerçek Android Auto ve medya katmanı tanılama raporuna bağlantı; FermataX/AABrowser/ScreenOnAuto durumlarını gösteren mevcut Ortak Entegrasyon Merkezi.

## Kararlı arka plan mimarisi

- Android uygulama paket kimliği **değişmedi**: `com.evcockpit.mediahub`. Böylece önceki sürümle aynı imza kullanılırsa kullanıcı kayıtları korunur.
- `DriveHomePlanner.kt`: saf Kotlin, tekrarsız ve güvenli "son açılan" eşlemesi.
- `DriveDashboard.kt`: temaya bağlı, özgün Android View arayüzü.
- `MainActivity.kt`: mevcut TV/radyo/Web Media modüllerini ve gerçek MediaController durumunu yeni arayüze bağlıyor.
- `AudioLibraryService.kt`: sürücü ekranının kabul ettiği ses kataloğunda "EV Cockpit Drive Hub" başlığı.
- `app_icon.xml`: araç önü ile medya simgesini birleştiren özgün vektör ikon.
- Uzun süren video oynatma, telefon görünür değilken devam etmez; Android Auto'da sürüş için radyo/ses kataloğu ayrıdır.

## Fiziksel araç doğrulaması

Bu tasarım telefondaki kullanıcı arayüzüdür. Aracın Android Auto görüntü katmanının yenilenmesi, TV/Netflix/Web videolarının EV Cockpit ekranında açılması ya da hareketli aracın sürücü ön ekranında video gösterilmesi **doğrulanmış değildir**. Güvenlik kilidini aşacak hız/vites/sahte kategori/DRM kodu eklenmedi.

## Testler

`gradle :app:testDebugUnitTest :app:assembleDebug`: proje üzerinden GitHub Actions build job'u.

Android 15 smoke test: `DRIVE HUB` ana ekran etiketi ve kayıtlı kanallar sayacı, Ortak Entegrasyon Merkezi, IPTV performans paneli, TV → Web Medya açılışı, harici AABrowser yokken hata yönetimi ve iki ayrı ses medya tarayıcısı.

Kaynak analizi: `docs/FERMATAX_222_APK_INCELEME.md`
