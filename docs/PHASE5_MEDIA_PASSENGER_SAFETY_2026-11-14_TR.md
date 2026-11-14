# Faz 5 — Medya ve Yolcu Modu (TASLAK)

Taban: Faz 4 `phase4/osrm-detour-provenance-20261114`, PR #59.

## Amaç ve tamamlanan ilk adım
- `PassengerMediaSafetyPolicy` ile **telefon-only video kabul politikası** eklendi.
- Android Auto projeksiyonunda video **her koşulda kapalı**.
- Ön planda olma ve açık kullanıcı onayı zorunlu.
- Gerçek, taze (en fazla 3 saniye) doğrulanmış OBD hızına veya doğru (en fazla 20 m konum hassasiyeti), taze GPS hızına dayanır.
- Her iki kaynaktan herhangi biri hareket (>1 km/sa) bildirirse oynatma reddedilir. Veri bilinmiyor/eskiyse reddedilir.
- JUnit testleri eklendi.

## Henüz yapılmayanlar
- Bu politika mevcut Cockpit/MediaWebView arayüzündeki `playbackAllowed` kapısına henüz bağlanmadı.
- Yolcu onayı yaşam döngüsü, ekran kilidi ve anlık hız akışı henüz entegre edilmedi.
- Tüm resmî uygulama paketleri / mağaza yönlendirmeleri incelenmedi.
- `YoutubeAdFilter` dosya/atıf temizliği ve Android Auto video yüzeyi statik incelemesi tamamlanmadı.
- Bu HEAD üzerinde CI/JVM/APK/R8/Gitleaks henüz doğrulanmadı.
- POCO F5 üzerinde gerçek kullanım ve park hâlindeki ID.4 Android Auto UAT yapılmadı.

## Kabul
1. Hareket, hız belirsizliği, konum kaybı, uygulama arka planı veya Android Auto bağlantısında video hemen durmalı ve tam ekran kapatılmalı.
2. Yalnızca telefon, doğrulanmış park hâli ve açık yolcu onayı koşullarında oynatma açılmalı.
3. Resmî uygulamalar için yalnız yüklü paketler açılmalı, yüklü değilse mağaza seçeneği güvenli biçimde sunulmalı.
4. Android Auto üzerinde hiçbir WebView/video template veya video intent'i olmamalı.
5. Faz 4 rota/şarj/trafik kodları değişmemeli.

**DRAFT · DO NOT MERGE · NO PRODUCTION RELEASE.** Fiziksel test yapılmadan güvenli kabul edilmez.
