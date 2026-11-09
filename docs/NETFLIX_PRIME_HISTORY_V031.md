# EV Cockpit Drive Hub v0.31 — Netflix ve Prime Video içerik geçmişi

## Özellikler

- **Media Cockpit** ana ekranında Netflix ve Prime Video web kısayolları.
- **Web Medya** ekranında `V◷` (Netflix geçmişi) ve `P◷` (Prime Video geçmişi), YouTube `YT◷` yanında yer alır.
- Netflix'te `/watch/{id}` ve `/title/{id}`; Prime Video'da `/detail/{id}` ve belirli Amazon `/gp/video/detail/{id}` bağlantıları, ayrı son 30 içerik listesine eklenir.
- Yalnızca **HTTPS ve tanınan tam alan adları**. Takip parametreleri, çerezler, kimlik doğrulama verileri, oturum anahtarları ve hesap bilgileri bu yeni içerik geçmişine yazılmaz.
- Site açıldığında veya SPA adresi değiştiğinde geçerli içerik adresleri güncellenir. İki platformun geçmişi birbirinden ve YouTube'dan bağımsız silinebilir.
- İçerik kaydına dokunmak aynı sayfayı Web Medya'da yeniden açar. Hizmetin resmi Android uygulamasına geçiş seçeneği de sunulur.
- Netflix ve Prime Video resmi telefon uygulamaları zaten kuruluysa açılabilir; yoksa uyarı gösterilir. Uygulama kurulumu, otomatik oturum veya hesap transferi yapılmaz.

## Sınırlar

**Bu bir izleme geçmişi/bağlantı hatırlama özelliğidir, oynatma konumu senkronizasyonu değildir.** Netflix/Prime Video hesaplarının gerçek *Continue Watching* verileri, korumalı video akışları veya DRM anahtarlarına erişmez. Servislerin WebView'de videoyu oynatacağı garanti edilmez. Bazı hizmetlerin giriş ve içerik rotaları kayıt için tanınmayabilir; bu durumda geçmişe eklenmez.

Araçtaki Android Auto medya kataloğu yalnızca radyo ve ses içindir; sürücünün görebileceği ön ekranda sürüş sırasında video kısıtlamasını atlatan hiçbir özellik eklenmedi. Android 15 emülatörünün açılması gerçek electric vehicle ekranındaki destek anlamına gelmez.

## Kod

- `StreamingPagePolicy.kt` — salt Kotlin, alan adı ve medya sayfası kimliği ayrıştırma.
- `StreamingHistoryStore.kt` — her hizmete özel, cihaz içi sınırlı JSON geçmişi.
- `MediaWebActivity.kt` — hizmet kısayolları, geçmiş diyalogları, WebView sayfa yaşam döngüsü.
- `InstalledMediaApps.kt` — kullanıcı seçimiyle resmî Android uygulamasını başlatma.
- `CockpitDashboard.kt` — telefon ana ekranı kısayolları.
- `MediaHubUnitTest.kt` — ayrıştırma ve güvenlik testleri.

Uygulamanın Android paket kimliği ve mevcut TV/radyo/YouTube geçmiş dosyaları değişmedi. Eski kurulumun üzerine yükleme yine aynı imzaya bağlıdır.
