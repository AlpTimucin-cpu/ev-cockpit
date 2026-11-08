# Hata incelemesi — Hedefler ekranı, OSRM yolu hiç başlamıyor

**Tarih:** 8 Kasım 2026 (Türkiye). **Kaynak:** kullanıcı ekran görüntüsü; Hedefler → `Gerçek yol rotasını hesapla` → `Rota hesaplanamadı (OSRM): Araç veya hizmet ayarları kullanıcı tarafından teyit edilmedi...`

## Doğrulanmış kök neden

1. `OsrmRouteRepository.calculateVia()` doğrudan `appConfig.configuration.first().endpoints.osrmBaseUrl` okur. Bu alanın varsayılanı `null` olduğundan rota **hiçbir HTTP isteği gönderilmeden** reddediliyordu. Ekrandaki hata yalnızca bu durumun görünen sonucuydu; bu hata ile bir OSRM sunucu kesintisi kanıtlanmaz.
2. `CoreIssue.CONFIG_UNCONFIRMED` mesajı araç profili/batarya onayı ile servis adresini aynı hata metninde birleştirmişti. **Yol geometrisini oluşturmak için BMS SoC veya batarya kapasitesi onayı gerekmez**. Şarj ve varış SoC tahminleri ayrı güvenlik kontrollerinde kalır.
3. Kokpit alt menüsünün `Ayarlar` eylemi yanlışlıkla `ObdSettingsDialog` açıyordu. OSRM adresinin bulunduğu `SettingsScreen`, yalnızca ses ayarı kısayolundan açılabiliyordu. Kullanıcıya gösterilen hata ayarın nasıl yapılacağını söylemiyor ve uzun metin UI içinde kesiliyordu.

## Uygulanan düzeltme

- `OsrmEndpointSelector.choose(endpoints, BuildConfig.DEBUG)`: varsa yalnızca doğrulanmış kişisel HTTPS OSRM kök URL'sini seçer. Batarya onayı, SoC veya OBD bağlantısını koşul yapmaz.
- Özel adres yoksa kullanıcı **TEST APK'daki en üst Ayarlar bölümünden** `TEST: OSRM deneme rotasını aç` anahtarını kendisi açabilir. `debugDemoRoutingEnabled` DataStore'a `false` varsayılanıyla kaydedilir. **Release APK'da `BuildConfig.DEBUG=false` olduğundan bu değer her durumda etkisizdir.** Demo URL'si asla üretim URL alanına kaydedilmez, URL validasyonunun OSRM demo hostu engeli korunur.
- Demo `https://router.project-osrm.org/`: test amaçlı, geçici; otomatik tekrar deneme kapalı, 15 saniyede bir rota isteği, daha küçük yanıt (`alternatives=false`, hız anotasyonu yok), maksimum yanıt 6 MB, tanımlayıcı `User-Agent`. Harici/yüksek trafikli kullanım ve düzenli yeniden yönlendirme için güvenilir değil.
- Otomatik DC istasyonu sapması `OsrmRoadDetourRepository` üzerinden demo sunucusuna **asla** gönderilmez (birçok sunucu isteğine dönüşebilir); kendi HTTPS OSRM adresi gerekir.
- Kokpit menüsündeki `Ayarlar` artık gerçekten araç/hizmet ayarlarını açar. OBD teşhisi ayrı telemetri panelinde kalır.
- Hedefler hata alanına `Rota hizmeti ayarlarını aç` düğmesi eklendi. Kullanıcı ayarı etkinleştirip hedeflerine geri dönebilir; rota başlangıç/varış seçimi korunur. Hata metninin kırpılması azaltıldı.
- Hedefler ekranında OSRM/OpenStreetMap yol kaynağı ve ODbL lisans bildirimi görünür.
- Özel OSRM adresi girilmemiş ve TEST demo anahtarı kapalıysa yeni hata: `Rota servisi seçilmedi. Ayarlar bölümünden kendi HTTPS OSRM adresini girin veya TEST sürümünde deneme rotasını açın.`

## Kullanıcı test adımları (mobil)

1. Yeni **EV Cockpit TEST** Debug APK'yı kur. Eski kuruluma dokunulmaz (`com.evcockpit.debug`).
2. **Ayarlar** → en üstte **TEST: OSRM deneme rotasını aç** anahtarını etkinleştir. Bunu yaparken rota başlangıç/varış ekranındaki herhangi bir gerçek araç verisi değişmez.
3. **Hedefler** ekranında gerçek GPS başlangıcı veya arama ile seçilmiş başlangıç ve hedefi belirle. **Gerçek yol rotasını hesapla** düğmesine bas.
4. Deneme sunucusu yanıt verirse gerçek OSRM yol geometrisi/mesafe/manevralar görünebilir; başarısızsa OSRM HTTP/timeout durumu açıkça raporlanır. Bu, uygulama/batarya açma-kapatma problemi değildir.
5. Üretim için adres: kişisel/ticari kullanım hakkınız olan güvenilir HTTPS OSRM sunucusu. Demo anahtarını kapatıp sunucu URL'sini girebilirsin.

## Sınırlar / kanıtlar

OSRM proje topluluğu, herkese açık demo sunucusunun **sınırlı deneme amaçlı**, yüksek trafikte yasaklanabilecek ve hizmet güvencesi verilmeyen bir servis olduğunu bildiriyor. İstekler koordinatları sunucuya gönderir; kullanıcı testte gerçek başlangıç/varış noktalarının ağ üzerinden paylaşıldığını bilmeli. OSM/ODbL attribution UI'da sunulur.

- https://github.com/Project-OSRM/osrm-backend/wiki/Api-usage-policy
- https://github.com/Project-OSRM/osrm-backend/wiki/Demo-server

Yeni JVM testleri `OsrmEndpointSelectionTest` (opt-in, Release engeli, özel URL önceliği, demo hız sınırlaması) ve `tests/test_route_osrm_debug_optin.py` (servis kök sebep, UI yolu, sürüm sınırı, istasyon sapması engeli) CI'ye bağlandı.

**Gerçek mobil GPS/Android Auto/ID.4 sürüş testleri yapılmadı.** Bu düzeltme sürüş sırasında deneme sunucusunu profesyonel navigasyon olarak kullanmayı güvenli kılmaz. `main` ve imzalı üretim Release değişmedi. Tarihsel imzalama anahtarı güvenlik kararı ve bağımsız PR #32 hâlâ bekliyor.
