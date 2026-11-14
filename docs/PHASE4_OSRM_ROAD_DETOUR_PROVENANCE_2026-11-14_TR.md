# Faz 4 — OSRM yol sapması karşılaştırmasında rota kaynağı, yeniden kullanım ve Android Auto
14 Kasım 2026 · Taslak · fiziksel testler henüz doğrulanmadı.

## Kapsam
- Eski mekanizma (en fazla altı rota-koridoru DC/CCS adayını kullanıcıya ait HTTPS OSRM ile `origin;station;destination` üzerinden doğrulamak) korunur; telefon ve Android Auto aynı doğrulama deposunu paylaşır.
- Her OSRM rota alternatifi, doğrulandığı `osrmBaseUrl` ile kaydedilir. Sapma isteği yalnız rota kaynağı **ile aktif sunucu birebir eşleşirse** yapılır; anonim, başka sunucuda üretilmiş veya Debug demo rotasının sapması ölçülmüş gibi sunulmaz.
- OSRM yanıtında yol ağına snapping sadece istasyonda değil, başlangıç ve bitişte de en fazla 500 metre olmalıdır. JSON hata veya açıklanamayan iki-leg/toplam mesafe uyuşmazlığı halinde sonuç kullanılmaz.
- Telefon ve araç eşzamanlı kontrolleri `Mutex` ile sıralanır; aynı rota/sunucu/istasyon adayları için 30 dakika doğrulanmış sonuç tekrar kullanılabilir, tamamen başarısız sorgu 60 saniye boyunca tekrar edilmez. Bu koruma gerçek zamanlı yol veya fiyat garantisi vermez.
- Kaynak tarihi eski/doğrulanamayan EPDK istasyonları Android Auto'da otomatik OSRM sorgusu başlatmaz. Sunucu değişikliği sonrası önceki sapma verisi şarj enerji modelinde kullanılmaz.
- OSRM ek yol ve süre **sürüş modeli hesaplamasıdır**. Canlı trafik, soket doluluğu, istasyona fiziksel giriş, kapı erişimi, tarife ve kesin varış SoC değildir. Kullanıcı onaylamadıkça aktif rota değiştirilmez.

## Testler
`OsrmRoadDetourRepositoryTest` kaynak eşitliği ve demo kilidini;
`OsrmDetourResponseParserTest` uzak başlangıç/varış snapping'ini;
`tests/test_phase4_osrm_detour.py` statik güvenlik koşullarını denetler.
CI Debug lint/JVM/APK, R8 smoke (NO DISTRIBUTION), Gitleaks aynı HEAD üzerinde geçmelidir.

## Telefon ve ID.4 park hâli kabulü
1. Kendi HTTPS OSRM sunucunu Ayarlar'a kaydet ve gerçek rota oluştur. Demo rota veya sunucu değişikliği halinde sapma *kullanılamıyor* olmalı.
2. Rota üzerindeki DC/CCS durakları için iki-leg OSRM km/süre değerlerini sağlayıcının JSON yanıtıyla karşılaştır; doğrulanmayan istasyon 0 km varsayımıyla gösterilmemeli.
3. EPDK önbelleği eskiyken / internet yokken otomatik sorgu durmalı; Android Auto haritası ve sesli navigasyon bu kontrol yüzünden değişmemeli.
4. Kullanıcı 'rotaya ekle' eylemi dışında OSRM aktif navigasyon rotası korunmalı.
5. Gerçek API/araç kabulü, EPDK güncel durumu, şarj sağlayıcı canlı soket bilgisi henüz doğrulanmadı.

Bu taslak PR merge edilmeyecek, Release yayınlanmayacak, Faz 5'e geçilmeyecek.
