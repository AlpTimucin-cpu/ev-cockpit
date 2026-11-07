# FAZ 7 — `IdYolArkadasi.zip` kaynak denetimi

**Denetim tarihi:** 8 Kasım 2026. **İncelenen dosya SHA-256:** `6288e16262bf1725e0cf75edbbceb794006acf6601ec6336774a26f5fdcd285e`.

Bu rapor kullanıcı tarafından sohbete yüklenen ZIP'in **statik içerik incelemesine** dayanır; ZIP depoya kopyalanmadı, çalıştırılmadı, içine gizli anahtar girilmedi.

## Kapsam ve bütünlük

- ZIP bütünlüğü: Python `ZipFile.testzip() == None` (CRC hatası yok).
- **14 arşiv girdisi, 8 normal dosya**, yaklaşık 32,9 KB sıkıştırılmamış kaynak. Gradle Kotlin yapılandırması, `README.md`, manifest ve Kotlin `MainActivity.kt`, `Core.kt`, `Obd.kt` var.
- Binary APK, dex, native .so, JAR, Gradle Wrapper, GitHub Actions, birim test veya doğrulanmış Play Store paketi yok.
- Basit belirteç taramasında yaygın GitHub/OpenAI/Google anahtarı ve `BEGIN PRIVATE KEY` kalıbı bulunmadı. Bu **kötü amaçlı yazılım bulunmadığının kanıtı değildir**, sadece incelenen kaynak/arşiv kapsamının sonucudur.
- README bu eski `tr.yolarkadasi` paketli prototip için açıkça **"v0.2 (derlenmemiş)"** diyor. Bu nedenle APK'nın çalıştığı veya üretime hazır olduğu varsayılamaz.

## Kritik bulgular — kesinlikle aktarılmamalı

| Öncelik | Kaynak | Sorun | Güncel EV Cockpit politikası |
|---|---|---|---|
| **P0** | `MainActivity.kt / CarTab` | "VW ID.4 Pro • 77 kWh • WLTP ~520 km" sabit kodlanmış; kullanıcı aracı için yanlış profildir. | Kullanıcı onaylı Pure araç profili; kapasite ve şarj gücü doğrulanmadan sabitlenmez |
| **P0** | `CarTab` | SoC başlangıç değeri `80f`; menzil `soc * 5.2` gibi kaba ve yanlış modele bağlanmış. | Doğrulanmış güncel BMS SoC gelene kadar canlı SoC ve varış SoC bilinmiyor |
| **P0** | `CarTab/Obd.kt` | Varsayılan `028C/7E5/7ED` MEB için doğrulanmamış; `Obd.value(b, 1, 1, 2.5)` sonuçları SoC'ye yazıyor. | Ham DID yanıtı ayrı; gerçek BMS kaynağı kanıtlanana kadar ölçüm sayılmaz |
| **P0** | `MediaTab` | Kaydedilen `passenger` anahtarı ile hareket hâlinde video engeli kaldırılabiliyor. GPS yoksa `Hub.kmh=0` varsayımı da engeli atlatabilir. | Araç ekranında video yok; telefon videosu gerçek güncel OBD hızı ve park onayı olmadan açılmaz |
| **P0** | `Obd.kt` | `cmd.startsWith("01") || cmd.startsWith("22")` yalnızca öneki denetliyor. Formatted DID/header/filter alanlarında karakter ve uzunluk sınırı yok; `\r` girdisi ELM komut zinciri enjeksiyonuna dönüşebilir. | Yalnızca tam eşleşen Mode 01 / UDS 22 biçimleri; ham kullanıcı girdisinden ECU'ya kontrolsüz komut yok |

## Diğer sorunlar

- **P1:** `Api.route()` doğrudan `router.project-osrm.org` topluluk/demo sunucusunu kullanıyor. Gerçek şarj yönlendirmesi için güvenilir, ayarlanabilir HTTPS OSRM gereklidir.
- **P1:** `Core.kt / weatherAt()` ETA için saatlik veri eşleşmesi bulunamadığında dizinin **ilk saatine** düşebiliyor. Bu saat, gerçekte ETA değildir.
- **P1:** `OpenChargeMap` anahtarı SharedPreferences içinde ve API URL sorgusunda tutuluyor; operatör fiyatı ve erişimi doğrulanmıyor.
- **P1:** `GreetReceiver` manifestte `exported=true`; Bluetooth ACL bağlanma olayı **kontak açılma kanıtı değildir**. Harici yayınlar/tekrarlı ses tetiklemeleri için daha sıkı kaynak ve oturum koruması gerekir.
- **P1:** HTTP yanıtı `readText()` ile üst sınır/garanti edilmiş bağlantı kapanışı olmadan toplanıyor; ağ kesintisinde hata ve bellek yönetimi zayıf.
- **P2:** `Hub` global Compose durumu, sürekli coroutine, yerel ses üretimi ve test hız kontrolü üretim yaşam döngüsüne uygun izole edilmemiş.
- **Uyumsuzluk:** ZIP `minSdk 26`, Compose/Gradle ve `osmdroid` kullanıyor; güncel `nativecockpit` modülü `minSdk 28`, MapLibre, Hilt/MVVM/Flow, EPDK ve Car App Library ile farklı mimaride.

## Karar

**Hiçbir ZIP kaynak dosyası mevcut `nativecockpit` üzerine kopyalanmadı.** Geniş hız göstergesi, ETA hava çipleri, kadın Türkçe ses ve motor sesi gibi ürün fikirleri güncel projede zaten kendi doğrulamalı sürümleriyle bulunmaktadır. Statik ZIP kodunun doğrudan derlenebilirliği doğrulanmadı; kaynak görüntüsü bazında değerlendirildi.

Bu rapor, kullanıcı tarafından yüklenen bu **belirli SHA-256** sürümüne aittir. Başka ZIP için sonuçları yeniden kullanmayın.
