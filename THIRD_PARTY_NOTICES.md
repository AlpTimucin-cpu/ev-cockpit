# Üçüncü taraf bileşenler ve lisans notları

## VideoLAN LibVLC
- Bağımlılık: `org.videolan.android:libvlc-all:3.7.2`
- Proje: https://code.videolan.org/videolan/libvlcjni
- Lisans: GNU Lesser General Public License 2.1 (LGPL-2.1)
- Bu bağımlılığın Android oynatıcı kütüphanesi olarak yeniden dağıtımı
  LGPL yükümlülükleri, bildirim ve kaynak edinme hakları çerçevesinde yapılır.
  Bağımlılık Maven Central'den derleme sırasında alınır. Yüklenen MediaEngine-3.2.apk
  içindeki ikili `libvlc.so` dosyaları buraya kopyalanmamıştır.
- Lisans ve kaynak: https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

## MediaEngine / Fermata
- Referans depo: https://github.com/moris545454/araba-
- Önceki kaynak: https://github.com/AndreyPavlenko/Fermata
- Referans lisans: GNU GPL-3.0
- Bu geçiş dalının ilk aşamasında MediaEngine'nın doğrudan kaynak dosyaları
  veya 3.2 APK DEX/kütüphaneleri kopyalanmamıştır. Açık kaynak projenin
  yayınlanan arayüz fikirleri ve özellikleri için yeni uygulama kodu
  oluşturulmuştur.
- MediaEngine kaynaklarının daha sonra içeri alınması/dağıtılması halinde
  uygun kaynak kodu, telif ve GPL-3.0 hükümleri ayrıca uygulanmalıdır.
- Genel GitHub deposunda 2.2.2 görünen kaynaklar ile kullanıcıdan
  gelen MediaEngine 3.2 ikilisinin birbiriyle tam eşleştiği doğrulanamadı.

## Sorumluluk
Bu proje, Volkswagen/Google/Netflix/Amazon tarafından onaylanan resmî
bir uygulama değildir. Telifli yayın kütüphaneleri veya hesap bilgileri
herhangi bir APK'dan izinsiz alınmaz; kullanıcı kendi izinli kaynaklarını ekler.
