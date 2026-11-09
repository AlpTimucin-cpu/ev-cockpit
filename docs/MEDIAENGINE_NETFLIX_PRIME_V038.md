# v0.38 — MediaEngine içinde Netflix ve Prime Video ekranları

YouTube gibi tek uygulama içinden açılabilen iki yeni **web-katalog ekranı** eklendi:
- Ana sayfadaki **Netflix** ve **Prime Video** görsel kartları, resmî Android uygulamasını hemen açmak yerine \`MediaEngineSubscriptionActivity\` açıyor.
- Aynı yerleşim: **MediaEngineSessionHeader** ses/bağlantı başlığı, platform etiketi, arama alanı, ana sayfa, son açılan içerikler, geri, yenile ve ↗ uygulamada izle.
- Siteden erişilebilir film/dizi sayfaları \`StreamingHistoryStore\` içine platform bazında son 30 bağlantı olarak saklanıyor. Takip parametreleri, çerezler, parolalar ve oturum verileri **geçmişe kaydedilmiyor**.
- Netflix'in \`/tr/title/...\` ve \`/tr-TR/watch/...\` gibi yerelleştirilmiş adresleri de aynı içerik kimliğine normalleştiriliyor.
- Prime Video'nun \`/detail/...\` sayfaları ve belirli bölgesel Amazon \`/gp/video/detail/...\` bağlantıları tanınabiliyor.
- Arama resmî web sayfasına yönlendirir; sitenin arama URL'leri belgelenmiş uygulama API'si değildir ve sağlayıcı değiştirirse çalışmayabilir.
- **↗ Uygulamada izle** seçildiğinde geçerli sayfa bir içerik kimliğine karşılık geliyorsa yalnızca içerik bağlantısı kurulu resmî uygulamaya iletilmeye çalışılır. Platform bağlantıyı kabul etmezse resmî uygulamanın ana ekranı açılır. Kurulu değilse uyarı.
- WebView yalnızca HTTPS resmî Netflix / Prime Video / Amazon oturum sayfalarına ana sayfa geçişi yapabilir. Sertifika hataları iptal edilir; JavaScript köprüleri veya dosya erişimi etkin değildir.
- İki platformun siteleri hesap girişi ve çeşitli yardımcı uç noktalar nedeniyle Android WebView'de açılmayabilir. **Netflix ve Amazon Prime Video'nun DRM korumalı yayınlarının WebView'de oynayacağı garanti edilmez.**
- Sitenin içeriğini kopyalama, APK içine lisanslı film/veri gömme, DRM çözme veya Android Auto sürüş kısıtlarını atlatma yapılmaz.

Değişiklikler: \`MediaEngineSubscriptionActivity.kt\`, \`SubscriptionPortalPolicy.kt\`, \`StreamingPagePolicy.kt\`, \`InstalledMediaApps.kt\`, \`MainActivity.kt\`, \`StreamingServiceCards.kt\`, Android Manifest, birim testler ve sürüm numarası.

Kullanıcı arayüzü telefon içindir. Gerçek EV Cockpit araç ekranında video oynatımı bu değişiklikle doğrulanmış değildir.
