# EV Cockpit Drive Hub — Media Cockpit v0.28

## Neler değişti?

Bu sürüm, FermataX'ten kopyalama yapmadan, yeniden tasarlanmış **Media Cockpit** ana ekranını Android telefon uygulamasının gerçek başlangıç sayfası olarak kullanır.

- **Ses Oturumu:** Media3 radyo servisine bağlı olarak gerçek çalma/duraklama durumu ve kanal adı; uydurma araç bağlantı bilgisi gösterilmez
- **Uyarlanabilir kısayollar:** ekran genişliğine göre 1, 2 veya 3 sütun; Canlı TV, Radyo, Web Medya, Kitaplık, EV Cockpit Durum ve Uygulamalar
- **Kaldığın yerden:** önceki TV/radyo içerikleri, silinmiş kayıtları dışlayan ve tekrarları temizleyen yerel sırayla
- **Favoriler:** TV ve radyo favorileri ayrı kaynak URL'lerini kullanıcıya göstermeyen kartlar üzerinden açılır
- **TV program rehberi:** mevcut XMLTV önbelleğinden Şimdi ve program ilerleme çubuğu; listeler yüklenmediğinde boş durum
- **Araç tanılama:** telefon ve Android Auto ses işlevini birbirine karıştırmayan bağlantı raporu
- **İlk kurulum:** hiç kanal yoksa Kitaplık'a yönlendiren kaynak ekleme kartı

## Teknik uygulama

- `CockpitPlanner.kt`: önceki medya kimliklerini görüntüleme verisine dönüştüren, yan etkisiz model. URL/hesap kimlikleri modele taşınmaz.
- `CockpitDashboard.kt`: tamamen yerel Android View/LinearLayout arayüzü; ek Compose bağımlılığı ve ağ çağrısı gerektirmez.
- `MainActivity.renderHome()`: eski ana sayfa yerine cockpit oluşturur; eski radyo/video veri depolarını kullanır.
- `EpgScheduleIndex.kt`: TV kartları için program ve ilerleme verisi; kart başına yinelenen SharedPreferences yüklemesi yok.
- JUnit: silinmiş içerik, tekrar eden geçmiş, favoriler, XMLTV kimlikleri ve URL gizliliği.

Uygulama `com.evcockpit.mediahub` kimliğini korur. Kullanıcı kanalları ve favorileri silinmez; ancak başka imzayla oluşturulan debug APK önceki kurulumun üzerine yüklenmeyebilir. Böyle bir durumda silmeden önce M3U dışa aktarımı önerilir.

## Doğrulama

Telefon/Android 15 emülatöründe başlatma ve temel UI davranışları otomatik test edilir. Bu testler **gerçek 2024 electric vehicle Pure araç ekranında** görüntü/Android Auto yetenekleri için kanıt değildir.

Android Auto sürüş sırasında yalnızca güvenli radyo/ses deneyimi için hedeflenir. Sürüş halinde sürücünün görebileceği ekranda video güvenlik kısıtlarını atlatan bir işlev eklenmedi.

Kaynak fikri: FermataX pano kavramı, program rehberi ve medya devam etme UX yaklaşımı. FermataX GPL-3 kaynak dosyaları, native `libvlc.so` dosyaları, ikonlar, paketler veya varlıklar bu uygulamaya kopyalanmadı.
