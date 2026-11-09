# FAZ 9 — Gerçek ELM327/OBD teşhis güvenliği ve fail-closed veri geçerliliği

**Temel:** FAZ 8 PR #34 son başarılı commit `5e714f12a4379482f7c51339ec8c1c86323a67e0`. Bu PR'ın hedefi FAZ 8 geliştirme dalıdır; `main` ve imzalı Release değiştirilmez.

## Giderilen riskler

### 1. Hızın güvenilirliği

- Eski kodda geçersiz/yanıtsız `010D` sorgusu, `VehicleTelemetry` hızını silerken `EngineeringSnapshot.speedKmh` ve `ObdConnectionState.lastSpeedAtMs` alanlarında eski ölçümü tutabiliyordu.
- Artık geçersiz veri veya hata halinde `clearSpeedObservation()` canlı hız telemetrisini, teknik hız değerini, türetilmiş mühendislik metriklerini ve son hız damgasını birlikte sıfırlar. OBD hızına erişilmemesi **0 km/sa ölçümü değildir**.
- Gerçek `010D` yanıtı bulunduğunda değer ve aynı zamandaki `lastSpeedKmh` + `lastSpeedAtMs` kaydedilir. Bağlantı kesildiğinde / ERROR durumunda kalıntı hız bırakılmaz.

### 2. Manuel UDS (MEB) ham sorgularına ikinci güvenlik kapısı

- `ObdDiagnosticSafetyPolicy` yalnızca bağlantı `CONNECTED`, son hız sorgusu başarılı, gerçek PID 010D hızı `0.0..0.5 km/sa` ve ölçüm **en fazla 3 saniye** eskiyse, teşhis yapılmasını kabul eder. Saat geri giderse, hız NaN/bilinmiyorsa, işlem zaten sürüyorsa sorgu reddedilir.
- `requestMebDiagnostic` ilk anda; worker `runSession` ise UDS/CAN komutları **gönderilmeden hemen önce** yeniden kontrol eder. Yeni PID 010D ölçümü alınamazsa kuyruktaki istek iptal edilir. Kullanıcıya Türkçe durum iletilir.
- Bu bir **risk azaltma kapısıdır**, vitesin `P` konumunu kanıtlamaz. Gerçek araçta sürüş sırasında herhangi bir teşhis sorgusu yapılmamalı; kullanıcı güvenle park etmelidir.
- Yalnızca `MebUdsParser.ALLOWED_REQUESTS` listesindeki, tek seferlik, kullanıcı tarafından tıklanan `22` ReadDataByIdentifier sorguları geçerlidir. ECU'ya `2E` yazma, rutin başlatma (`31`) veya arıza silme (`14`) gönderilmez. DID başlıkları **kullanıcı aracında henüz doğrulanmadı**.

### 3. CAN / ELM327 ayarları geri yüklenmeden yeni PID okumamak

- Eski kod, MEB sorgusu sonrası `ATFCSM0, ATCRA, ATSP0, ATH0` geri yüklemesini `try/catch` ile sessizce başarısız sayabiliyor ve buna rağmen hız sorgusuna devam edebiliyordu. Bu, değişmiş CAN filtresi altında yanlış/kayıp `010D` cevabını canlı kabul ettirebilirdi.
- `ObdWireCommandPolicy` tam komut listesi, CR/LF içeren, uzun, beklenmeyen AT ve ECU yazma komutlarını fiziksel sokete yazılmadan reddeder.
- `ObdProtocolRestorer` tüm dört geri alma komutu için **kesin OK cevabı** ister. Olumsuz ACK, zaman aşımı, socket kopması veya iptal durumunda soket kapatılır. `ObdProtocolRestoreException` üst katmana iletilip aktif bağlantı sonlandırılır ve canlı değerler temizlenir.
- Bazı ucuz ELM327 klonları `ATCRA` gibi komutları desteklemeyebilir. Böyle cihazlarda tanı sorgusunun sonlanması beklenen **güvenli davranıştır**; bunu otomatik devam ettiren kestirme eklenmeyecek.

### 4. BMS ve mühendislik gerçekliği

- `22028C`, `221E0E`, `221E3D` vb. adresler **topluluk kaynaklı, bu ID.4 üzerinde doğrulanmamış adaylar** olmaya devam eder.
- `MebUdsParser.parse` ham yanıtı raporlar; ham did değerlerinden hiçbir otomatik batarya SoC/SoH, menzil veya şarj planlaması üretilmez.
- `VerifiedBatterySocPolicy` sadece aynı zaman damgalı kanıtlanmış `VERIFIED_OBD/MEASUREMENT` ölçüme izin verir. Henüz gerçek BMS doğrulaması yapılmadığından pano SoC bilinmiyor kalır.

## CI kabul ve mobil UAT

Kontroller:
- `ObdDiagnosticSafetyPolicyTest`: bağlantı / hız yaş eşiği / NaN / hareket / tekrar istek;
- `ObdWireCommandPolicyTest`: tam komut allowlist, CR/LF enjeksiyon engeli, adapter reset ACK kontrolü;
- `ObdProtocolRestorerTest`: sırayla dört reset, başarısız ACK / timeout / iptalde fiziksel bağlantıyı kapatma;
- `test_phase9_obd_safety.py`: canlı hızın temizlenmesi, ham-only ölçüm, CI kaynak güvenliği.

```bash
python3 -m unittest discover -s tests -p 'test_phase9_obd_safety.py' -v
./gradlew :nativecockpit:lintDebug :nativecockpit:testDebugUnitTest :nativecockpit:assembleDebug --no-daemon
python3 scripts/check_apk_secrets.py nativecockpit/build/outputs/apk/debug/nativecockpit-debug.apk
```

Gerçek VW ID.4 + eşleştirilmiş ELM327 testleri **henüz yapılmadı**. Araç güvenli P konumunda ve hareket etmiyorken; bağlantıyı kurun, gerçek `010D` mevcut/missing senaryolarını gözlemleyin, ham DID sorgusunu yalnızca ilgili güvenlik bilgilerini kabul ederek deneyin, beklenmeyen `?` veya timeout halinde bağlantının kapandığını ve SoC'nin uydurulmadığını doğrulayın. Geçici testlerde gizli VIN, rota/GPS, Bluetooth MAC, API anahtarı veya kişisel bilgiler içeren ham loglar paylaşılmamalı.

**Sürüm kuralı:** `com.evcockpit.debug` / **EV Cockpit TEST** yan yana kurulabilir Debug APK; Release imzalama güvenlik beklemesi sürüyor. `main` üzerindeki eski yayın akışı PR #32 birleştirilmeden engellenmiş sayılmaz.
