# Adım 11 — DHU doğrulama hazırlığı + Bluetooth ELM327 (okuma)

## Neler eklendi
- Google Android Auto **Desktop Head Unit** için gerçek telefon/Windows ADB test betiği: `scripts/android_auto_dhu_test.ps1`. Bu dosya DHU'yu kendisi simüle etmez; gerekli kablo, telefon, Android Auto head unit server ve SDK paketi olmadan otomatik test sonucu üretmez.
- Android 12 ve sonrası `BLUETOOTH_CONNECT` izni, önceden eşleştirilmiş Bluetooth Classic SPP adaptörlerinin listelenmesi, kullanıcı seçimi, bağlantıyı kesme ve hata durumları.
- ELM327 **salt-okuma** haberleşmesi: `ATZ`, `ATE0`, `ATL0`, `ATS0`, `ATH0`, `ATSP0` yapılandırması; yalnız **SAE J1979 010D araç hızı** isteği. Bunun aracınızda mevcut olduğu *garanti değildir*. Kontrol verisi veya ECU yazma komutu gönderilmez.
- Yanıt parser'ı `41 0D XX` dışında hız ölçümü üretmez. Hatalı/çelişkili yanıtlar ve kopan bağlantı `--` gösterir. Sonuçlar 10 saniye sonra bayatlar. GPS ile yapay eşitleme yoktur.
- `Car Scanner` ile eşzamanlı aynı Bluetooth ELM327 bağlantısı beklenmez: önce diğer uygulamanın bağlantısını kapatın.

## Telefon üzerinden OBD bağlantısı
1. ELM327 Bluetooth Classic cihazını Android ayarlarından eşleştirin (BLE-only adaptör desteklenmez).
2. **EV Cockpit** telefon uygulamasını açıp **Ayarlar** sekmesinden OBD Bağlantısı'nı açın.
3. Android 12+ Bluetooth yakındaki cihaz bağlantısı izni istenirse izin verin. **Eşleştirilmiş cihazları yenile** düğmesini kullanın.
4. Kendi ELM327 adaptörünüzü seçin. **010D** destekleniyorsa ölçülmüş **hız** görünür. Yanıt `NO DATA` ise hız bilinmiyor olarak kalır.
5. Kullanım bittiğinde **OBD bağlantısını kes**; uygulama arka plana geçerse de oturum kapatılır.

## Batarya SoC / SoH
Önceki PID keşiflerinde `221E0E` dönüşünü `sohCapNew=112.80`, `221E3D` dönüşünü `current=0.90`, `221E2D` değerini `accDischarge` olarak adlandırmıştınız; bunlar **çekiş bataryası SoC yüzdesinin güvenilir kaynağı olduğunun kanıtı değildir.** Bu adımda UDS PID tahmin edilmemiştir, HV SoC ve SoH hâlâ boş gösterilir. Çoklu ECU sorguları, güvenlik yazma komutları veya yetkisiz Can bus işlemleri eklenmedi.

## Android Auto DHU testi
Google resmî dokümanı: https://developer.android.com/training/cars/testing/dhu

- Telefon Android Auto geliştirici ayarlarını açın ve **Start head unit server** seçin.
- USB ADB bağlantısıyla PowerShell'de: `./scripts/android_auto_dhu_test.ps1 -Serial <cihaz_serisi> -LaunchDHU`
- Araç ekranı simgesi, rota, GPS, manevra, ses, gündüz/gece ve kullanıcı etkileşimleri DHU içinde ayrı ayrı doğrulanmalıdır. DHU/araç testi burada yapılamaz; GitHub Actions **birim testlerini, derlemeyi ve statik manifest kontrollerini** yapar.
- Google Play test kanalı gerekebilir; lokal APK'nın Android Auto'da görünmesi garanti değildir.

## Derleme
`gradle :nativecockpit:testDebugUnitTest :nativecockpit:assembleDebug --no-daemon`

Önceki `:app` ve `:evlite` modüllerini değiştirmiyoruz. Adım 10 Android Auto navigasyon kodu korunmaktadır.
