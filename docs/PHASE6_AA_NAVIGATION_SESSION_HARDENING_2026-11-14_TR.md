# Faz 6 — Android Auto navigasyon servis oturumu güvenliği

14 Kasım 2026 · Taslak · Faz 5 PR #60 taban alınmıştır.

## Kod
- `CarNavigationContinuity.serviceStarted` artık açıkça telefon işlemi gerektiren `PHONE_ACTION_REQUIRED` durumunda servis oturumu kabul etmez.
- Mevcut servis sahibi aktifken gecikmiş ikinci servis token'ının aynı rota için sahipliği devralması reddedilir.
- Kullanıcı tarafından yeni rota başlatıldığında eski servis, yeni rotayı engelleyemez; eski servis `serviceEnded` çağrısı yeni oturumu sonlandıramaz.
- Üç regresyon testi eklendi; mevcut sekiz test korundu.
- Android Auto için WebView/video eklenmedi; mevcut GPS doğrulama, gerçek rota ve host güvenlik sınırları korunur.

## Açık bağımlılıklar ve kabul
1. Faz 5 medya ekranı bağlantısı/izin yaşam döngüsü/uygulama kısayolları hâlâ tamamlanmadı.
2. Bu dalda Android lint, Kotlin/JVM testleri, Debug APK, R8 ve Gitleaks **henüz çalıştırılarak doğrulanmadı**.
3. POCO F5 üzerinde GPS kaybı/geri gelişi ve park hâlindeki ID.4 Android Auto gerçek host oturum testi henüz yapılmadı.
4. Yeni bir navigasyon servisi/medya erişimi arka planda izinsiz başlatılmamalı.
5. Sonraki faz, fiziksel kabul ve CI tamamlanmadan üretime alınmamalı.

**DRAFT / DO NOT MERGE / DO NOT RELEASE.**
