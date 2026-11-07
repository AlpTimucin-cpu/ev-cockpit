# FAZ 8 — Yeni `IdYolArkadasi.zip` yeniden denetimi

**Denetim tarihi:** 8 Kasım 2026. Kullanıcının yeni mesajında yeniden yüklediği dosya, önceki FAZ 7 kaynağından **farklıdır**.

- **Bu yüklemenin SHA-256 değeri:** `9f66d0b1302789b7377ae82b94545daad198a02003c69625f7cf5ab4e413e62a`
- Arşiv boyutu: **15.916 byte**
- ZIP CRC: **geçerli** (`ZipFile.testzip() == None`)
- **14 arşiv girdisi, 8 normal dosya** (diğerleri dizin girdisi)
- `README.md` başlığı: **`ID.4 Yol Arkadaşı v0.2 (derlenmemiş)`**
- Kaynak paket/uygulama kimliği: **`tr.yolarkadasi`**; Gradle `minSdk=26`, `targetSdk=35`.
- Arşivde **APK, Gradle Wrapper, birim testleri ve Android Auto CarAppService bulunmuyor**. Kaynakların derlendiği veya cihazda test edildiği iddia edilmez.
- Önceki `docs/PHASE7_UPLOADED_ZIP_AUDIT.md` raporunun SHA-256 değeri **`6288e16262bf1725e0cf75edbbceb794006acf6601ec6336774a26f5fdcd285e`** idi. FAZ 7'nin eski ZIP bulguları bu yeni dosyaya doğrudan kopyalanmamalıdır.

## Araç bilgisi — önceki rapordan önemli fark

**Bu yeni ZIP, 77 kWh ID.4 Pro diye sabit kodlanmamıştır.** Hem README satır 7, hem `MainActivity.kt` CarTab bölümü, hem `Core.kt` Car nesnesi, **VW ID.4 Pure / 125 kW (170 PS) / 52 kWh net** bilgisini gösteriyor.

Yine de `Core.kt:133` içinde `NET_KWH=52.0`, `DC_MAX_KW=145`, `AC_KW=11` sabit kodlu. Bu değerlerin uygulanabilirliği araç kılavuzu/şasi ve kullanıcı tarafından teyit edilmeden doğrulanmış ölçüm kabul edilemez. Güncel EV Cockpit kullanıcı onaylı ve nullable bir araç profili kullanır.

## Güvenlik ve veri bütünlüğü riskleri

| Seviye | Dosya / kanıt | Bulgular | Karar |
|---|---|---|---|
| P0 | `Core.kt:136` | `Hub.soc` başlangıçta **`80f`**; canlı BMS ölçümü izlenimi doğurabilir. `MainActivity.kt:128-132` menzili bu değerden hesaplıyor. | **Aktarılmadı**. Geçerli ölçüm kanıtı olmadan canlı SoC/menzil yok |
| P0 | `MainActivity.kt:120, 136-143` | `028C` / `7E5` / `7ED` varsayılanları **e-Golf MQB örneğinden**; `Obd.value(b,1,1,2.5)` yanıtı otomatik `Hub.soc` olarak yazılıyor. MEB için doğrulanmamış. | **Aktarılmadı**; ELM ham cevap hiçbir zaman doğrulanmış SoC sayılmaz |
| P0 | `MainActivity.kt:165-198` | Kalıcı `passenger` ayarı araç hareketindeyken video başlatmaya izin veriyor. GPS yoksa `Hub.kmh=0f` başlangıcı güvenli hareket tespiti sağlamıyor. | **Aktarılmadı**; AA araç ekranında video yok, telefonda hareket/OBD bilinmiyorsa oynatma kilitli |
| P0 | `Obd.kt:33-37` | `query()` yalnızca `startsWith("01") || startsWith("22")` yapıyor. İsteğe eklenebilir `header`/`filter` ve komutun CR/LF/hex uzunluk denetimi yok; ELM komut girişine sınır konmamış. | **Aktarılmadı**; güncel uygulamada sabit tam DID allowlist ve read-only servis |
| P1 | `Core.kt:79-85` | ETA zamanını saatlik seride bulamazsa `?: 0` ile **ilk tahmin saatini** döndürüyor; wrong-hour forecast riski. | **Aktarılmadı**; FAZ 8'de en yakın UTC saat 45 dakikayı aşıyorsa reddet |
| P1 | `Core.kt:64-76` | Rota demo sunucusu `router.project-osrm.org` sabit; ETA OSRM profil tahmini, canlı trafik değil. | **Aktarılmadı**; kendi HTTPS OSRM adresi yapılandırılmalı |
| P1 | `Core.kt:47-52, 88` | HTTP yanıtı `readText()` ile boyut sınırı ve `disconnect()` olmadan okunuyor. OpenChargeMap anahtarı URL query parametresine ekleniyor; `MainActivity.kt:108` SharedPreferences'e yazılıyor. | **Aktarılmadı**; güvenilir erişim/saklama politikası gerekir |
| P1 | `AndroidManifest.xml:17-19; Core.kt:37-45` | Dışa açık `GreetReceiver` Bluetooth bağlantısını “kontak” varsayıyor. ACL bağlantısı kontak açıldığı kanıtı değildir; ses yaşam döngüsü sıkı yönetilmiyor. | **Aktarılmadı**; mevcut EV Cockpit kendi bağlantı/AA oturumlarıyla selamlıyor |
| P1 | `MainActivity.kt:150-158; Core.kt:140-151` | Manuel “Test hızı” ile gerçek GPS hız alanı değiştirilebiliyor. Simülasyon ve gerçek telemetri karışabilir. | **Aktarılmadı**; üretimde sahte hız ve GPS yok |
| P2 | `Core.kt:97-130, 135+` | Motor sesi `Thread/AudioTrack` üretimi ve global `Hub` durumuyla yapılıyor; ses odağı, tekrar başlama ve kapanış davranışı üretim kalitesinde test edilmemiş. | **Aktarılmadı**; mevcut Media3/AudioFocus mimarisi korunur |

## Korunmaya değer ürün fikirleri (kaynak kod değil)

- Büyük okunabilir hız/sonraki manevra gösterimi;
- Rota boyunca **yaklaşık 40 km** aralıklarla geçiş saatine göre hava tahmini;
- Yalnızca önemli hava değişimlerinde Türkçe uyarı;
- Tüketim profiline bağlı, açıkça **MODEL** etiketli mesafe/enerji hesabı;
- Seçilebilir iç mekân motor sesi ve ayrı medya sekmesi.

Bunlar büyük ölçüde EV Cockpit'in mevcut fazlarında zaten uygulanmıştır. Yeni ZIP'den dosya veya doğrulanmamış PID/paket kimliği taşınmadı.

## FAZ 8'de gerçekten değiştirilen kısım

- `HourlyForecastParser` ETA ve Open-Meteo UTC saatinin farkını **en fazla 45 dakika** kabul ediyor; eşleşmeyen tahmini göstermez. ZIP'deki `?:0` yanılgısının karşıtı.
- `WeatherChangeAlertGate` artık sadece **güncel, aynı rotaya ait, GPS ilerlemesiyle aracın önünde kalan, ETA'sı geçmemiş ve 3 saat içinde karşılaşılacak** riskleri değerlendiriyor; geçilmiş/çok ileri nokta uyarısı vermiyor.
- Navigasyon sesli uyarı çağrısı mevcut rota kimliği ve `validProgress()` şartıyla bu kapıyı kullanır.
- Eski/uymayan hava uyarısı için sesli yönlendirmeye öncelik verilmez; önceki FAZ 6 ses/yol sahipliği kuralları korunur.

## Yapılmayanlar

ZIP Android Studio/emülatörde derlenmedi; dosya kaynak denetimine alınmıştır. Gerçek VW ID.4/ELM327, Android Auto cihaz/host, Play Store ve imzalı Release testleri yapılmadı. Kaynak audit'i kötü amaçlı yazılımın bulunmadığını garanti etmez.

Sürüm dağıtımı hâlâ **sadece Debug** aşamasında tutulur. `main` dalındaki açık Release güvenlik açığı için bağımsız **PR #32** ayrıca gözden geçirilip birleştirilmelidir. FAZ 8 kodu PR #33 (FAZ 7) üzerine taslak olarak eklenir.
