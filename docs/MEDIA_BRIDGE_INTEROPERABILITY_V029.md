# FermataX 2.2.6 manifest bağlantısı — EV Cockpit Drive Hub v0.29

**Kaynak:** Kullanıcının sağladığı 2.2.6 (332) AndroidManifest.xml metni (`Yapıştırılan markdown(1).md`). Bu, kaynak kodu veya yeni APK'nin ikili analizi değildir. Daha eski 2.2.2 APK'dan ayrı değerlendirilir.

## Doğrulanan bağlantı sözleşmesi

- Paket `me.app.fermataX.auto.debug`, Android minSdk 28, targetSdk 36.
- `me.aap.fermata.ui.activity.MainActivity`: `android:exported="true"`; `ACTION_VIEW` filtresinde HTTPS, HTTP, `video/*` ve `audio/*` bildirimleri var.
- `ParkedCarActivity` CAR_LAUNCHER tanımı, `AutoFermataMediaService`, `CarService`, `ProjectionActivity` ve `ProjectionService` manifestte var. Bu beyanlar gerçek araçta işlevin doğrulandığı anlamına gelmez.

## Yeni çalışan bağlama (kaynak düzeyi)

1. Drive Hub → **Canlı TV** → kanal seç veya **Radyo** bölümünde bir istasyon başlat.
2. Oynatıcının altında **FermataX 2.2.6 ile telefonda aç** düğmesine dokun.
3. Uygulama `me.app.fermataX.auto.debug` paketini kontrol eder. Kurulu değilse başka APK kurmaz.
4. Aktarılacak bağlantı HTTPS değilse reddedilir. Şifreyi URL'nin `username:password@` bölümünde taşıyan adres reddedilir.
5. Onay ekranında abonelik URL'sinin erişim anahtarı içerebileceği açıklanır.
6. Onay verilirse `ACTION_VIEW` çağrısı, *yalnızca* doğrulanmış açık telefon etkinliğine `setClassName()` ile yönlendirilir. URI adresi sadece kullanıcı onayından sonra gönderilir; kaynak URL'si günlüklere veya tanılama raporlarına eklenmez.
7. Android bağlantıyı reddederse güvenli hata mesajı gösterilir; mevcut kanal kitaplığı değişmez.

**Gerçek cihaz testi gerekli:** Alıcı FermataX sürümünün bu URL'yi oynatacağını manifest tek başına garanti etmez. GitHub Android emülatörlerinde FermataX kurulu olmadığında yalnızca uygulama tarafındaki davranış gözlenebilir.

## Kullanılmayan manifest parçaları

- `com.google.android.gms.car.category.CATEGORY_PROJECTION`, `CAR_LAUNCHER` veya `appCategory="game"` Media Hub'a kopyalanmaz.
- `MediaProjection` izinleri veya ayrıcalıklı `REQUEST_INSTALL_PACKAGES` eklenmez.
- Hız/vites/park sinyali değiştirme ya da hareket halinde sürücünün ön ekranında video güvenlik kilidi aşma uygulanmaz.
- Üçüncü taraf GPL veya kapalı APK'nın kaynak kodu, simgeleri ve yerel `libvlc.so` dosyaları kopyalanmaz.

## CI notu

v0.28 emülatör smoke testi uygulama nedeniyle değil, **GitHub runner disk alanı yetersizliği** nedeniyle başlayamadı (Emulator FATAL: userdata partition 7372.80 MB gerekli, 7122.84 MB boş). v0.29 test akışına `-partition-size 2048` seçeneği eklendi ve yeni pano kontrolü güncellendi. Yeni test sonucuna göre ayrıca doğrulama yapılmalı.
