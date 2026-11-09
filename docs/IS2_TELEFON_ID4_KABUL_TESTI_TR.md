# EV Cockpit — İş 2: Telefon + VW ID.4 kabul testi

**Durum: TEST EDİLMEDİ.** Bu doküman gerçek araç test raporu değil, telefondan doldurulacak protokoldür.
**APK:** 1.0.8-TEST, `com.evcockpit.debug`. **Temel commit:** `e66fe8a368129d1529c56901305194477fbded01` (İş 1 / PR #46).
**Test ortamı:** Bilgisayar, adb, logcat ve DHU yok; yalnız Android telefon, Android Auto, araç ekranı ve uygulamanın Debug Tanılama bölümü kullanılabilir.

## 0. Mevcut kanıt / ön koşul (9 Kasım 2026)

Sahibin paylaştığı raporda Debug Tanılama ekranı açıldı; günlük **7/500** olay içeriyor. Konum (hassas/yaklaşık), bildirim ve Bluetooth izinlerinin tamamı **verilmedi**. GPS, Android Auto Car Screen, harita yüzeyi, son hava verisi, EPDK kaynak zamanı ve OBD ham yanıtı **kullanılamıyor**. Foreground servisler kapalı, OBD **DISCONNECTED**, yeniden bağlanma sayısı **0**.

Bu verilerle araçta bir arıza kanıtlanmadı. Dört EPDK durum değişikliği kaydı, **güncel istasyon verisinin başarıyla indirildiğinin kanıtı değildir**. Hava otomatik aralığının “30 dk” gösterilmesi de yalnız kod sabitini doğrular; gerçek yenileme zamanlarını değil.

**İlk yapılacaklar (araç park halinde):**
1. Android **Ayarlar → Uygulamalar → EV Cockpit TEST → İzinler**: Konum için **Kullanırken izin ver** + **Hassas konum**, Yakındaki cihazlar/Bluetooth için **İzin ver**; bildirim iznini de kontrol et. Yalnız test için gereken izinleri ver. Bluetooth izni olmadan OBD testi yapılamaz.
2. Konumu, Bluetooth'u ve interneti aç; Debug Tanılama'yı yeniden aç. **İzinler** ve **Son gerçek GPS** durumlarını kopyala. Başka cihazın/başka uygulamanın konumunu kullanma.
3. Aracı güvenli biçimde park et, Android Auto'yu normal kablo/bağlantısıyla aç. Car Screen aktif görünene kadar bekle. **Bağlı görünmemesi = otomatik uygulama arızası değil**; Android Auto'da uygulamanın görünürlüğünü ve telefon izinlerini ayrıca kontrol et.
4. OBD testine geçmeden önce yalnız kendine ait, önceden eşleştirilmiş ELM327 adaptörünün bağlı olduğundan emin ol. Servis yazma testi için **hiçbir ECU'ya yazma isteği gönderme**.

**Doldurma kuralı:** Her satırdaki `Bekliyor` sonucunu **Geçti / Kaldı / Test edilemedi** olarak değiştir. `Kaldı` için tam semptom ve zamanı yaz. Koşul/izin yoksa **Test edilemedi** yaz; **Geçti** kabul etme. Her kod numarası için fotoğraf, ekran kaydı veya maskelenmiş Debug raporu sakla.

| Ortam bilgisi | Test sahibi dolduracak |
| --- | --- |
| Tarih / yer (yaklaşık il yeterli) | … |
| APK sürüm / Git commit / SHA-256 | 1.0.8-TEST / İş 1 PR #46 / … |
| Telefon Android sürümü / Android Auto sürümü | … / … |
| Araç ve ekran | VW ID.4 Pure / 12,9 inç (piksel ölçüsü tanılamadan) |
| Debug raporu / fotoğraf isimleri | … |
| Test eden / yolcu-gözlemci | … |

## A — 12,9" ekran ve görünürlük

| Adım | Beklenen sonuç | Geçti/Kaldı | İstenen kanıt |
| --- | --- | --- | --- |
| A1. Park halinde gündüz navigasyon haritasını aç. | Harita kullanılabilir alanı doldurur; siyah boşluk, kırpılma ve aşırı büyüme yok. | Bekliyor | Foto A1 + Debug Surface **genişlik×yükseklik / dpi / insets** |
| A2. Aynı ekranı gece/karanlık temada kontrol et. | Yazılar, rota çizgisi ve araç işareti okunur; üst üste binme yok. | Bekliyor | Foto A2 |
| A3. Manevra kartı, kalan mesafe ve ETA'yı kontrol et. | Kartlar birbirini ve haritanın zorunlu bölümlerini örtmez. ETA gerçek trafik ölçümü değil **OSRM tahminidir**. | Bekliyor | Foto A3 + varsa tutulan Debug durumu |

## B — Navigasyon ve ses

| Adım | Beklenen sonuç | Geçti/Kaldı | İstenen kanıt |
| --- | --- | --- | --- |
| B1. Telefondan gerçek yer araması ve rota seç; Android Auto'ya bak. | Doğru hedef ve gerçek OSRM rotası görünür; rota yoksa uydurma ETA/manevra yok. | Bekliyor | Telefon/araç foto B1; özel adresi maskele |
| B2. Güvenli rotada yolcu gözlemciyle **650 m** ve **90 m** eşiklerini dinle. | İlgili gerçek OSRM manevrası için birer Türkçe anons; tekrar spam'i yok. | Bekliyor | Yolcunun notu B2 + anons zamanı; sürücü kayıt yapmaz |
| B3. Güvenli ve yasal bir alternatif yola girilirse davranışı gözlemle. | Beş ayrı hassas GPS ölçümüyle 150 m üzeri sapma doğrulanınca uyarı/yeniden planlama gereksinimi; **sessiz otomatik rota değiştirme yok**. Yeni rota telefondan seçilir; duraklar silinmez. | Bekliyor | Yolcu notu B3 + ekran foto |
| B4. Rota hedefinde güvenli park yap, varışı gözle. | Oturum sonlanması veya açık kullanıcı durdurma kontrolü; **otomatik kapanış doğrulanmış değildir**, kapanmıyorsa sonucu Kaldı yaz. | Bekliyor | Parkta ekran B4 + Debug olayları |

## C — Servis ve bağlantı sürekliliği

| Adım | Beklenen sonuç | Geçti/Kaldı | İstenen kanıt |
| --- | --- | --- | --- |
| C1. Navigasyonu başlatıp telefonu kilitle / başka uygulamaya geç. | İzinli konum foreground servisi ve bildirim görünür; Android'in kısıtı varsa **telefon üzerinden başlat** uyarısı çıkar, gizli başlatma olmaz. | Bekliyor | Telefon bildirim foto C1 + “Foreground konum” alanı |
| C2. **Park halinde** telefon GPS/konumunu 30 saniye kapatıp aç. | Eski GPS canlıymış gibi kullanılmaz; konum yeniden sağlandığında rota kanıtıyla toparlanır veya açık hata verir, çökmez. | Bekliyor | Önce/sonra Debug GPS saati ve kayıt C2 |
| C3. **Park halinde** USB bağlantısını çıkarıp yeniden tak. | Eski Android Auto oturumu ayrılır; gerçek rota + yeni hassas GPS varsa yeniden bağlanır, yeniden bağlanma sayısı yalnız başarıda artar. | Bekliyor | Önce/sonra Debug “Android Auto / yeniden bağlanma” + foto C3 |

## D — Yol üzeri saatlik hava TAHMİNİ

| Adım | Beklenen sonuç | Geçti/Kaldı | İstenen kanıt |
| --- | --- | --- | --- |
| D1. Gerçek GPS'li rotada Hava ekranını aç. | Nokta bazında **tahmini geçiş saati**, sıcaklık/yağış/rüzgâr; her veri **TAHMİN** olarak etiketlenir. | Bekliyor | Foto D1 + son başarılı hava zamanı |
| D2. Ekran/Android Auto etkin ve GPS/rota eşleşmişken 31–35 dk izle. | Yenileme girişimleri arasında en az 30 dk; gerçek istek sonucu zaman damgasıyla kanıtlanır. **Ekrandaki “30 dk” yazısı tek başına geçmez.** | Bekliyor | İki ayrı zaman damgalı Debug raporu D2; izin/ağ yoksa Test edilemedi |
| D3. Anlamlı hava riski/uyarı değişimi oluşursa dinle. | Tek risk için tekrarlayan uyarı yok; manevra sesini kesmez. **Doğal risk değişimi oluşmazsa Test edilemedi.** | Bekliyor | Yolcunun uyarı zaman notları D3 |
| D4. Parkta interneti kapatıp aynı hava ekranını aç. | Önceki aynı rota tahmini varsa “X dk önce / yenileme başarısız” görünür; **yeni veri uydurulmaz**. | Bekliyor | Foto D4 + Debug yenileme durumu |

## E — EPDK şarj istasyonları

| Adım | Beklenen sonuç | Geçti/Kaldı | İstenen kanıt |
| --- | --- | --- | --- |
| E1. OSRM rotasında Hava / Şarj → Rota şarjları. | Yalnız veri kaynağındaki **DC/CCS** adayları ve gerçek konumları; hiç veri yoksa “kullanılamıyor”. | Bekliyor | Araç foto E1 + EPDK kaynak zamanı |
| E2. İstasyon satırını aç: konektör, güç, fiyat. | Konektör DC CCS; kW ancak birimi açıkça doğrulanmışsa; fiyat bilinmiyorsa **“Fiyat: kullanılamıyor”**. Canlı doluluk tahmini yok. | Bekliyor | Foto E2 |
| E3. Varış SoC ve uzun istasyon listesini kontrol et. | ID.4 BMS SoC doğrulanmadığı için **“Varış SoC: kullanılamıyor”**; host satır/eylem sınırı korunur, öğe taşması yok. | Bekliyor | Foto E3 + Debug OBD durumu |

## F — Selam ve motor sesi (yolcu gözlemcisi)

| Adım | Beklenen sonuç | Geçti/Kaldı | İstenen kanıt |
| --- | --- | --- | --- |
| F1. Uygulamayı / Android Auto'yu normal şekilde başlat. | “Merhaba yol arkadaşım. İyi yolculuklar.” Türkçe duyulur; tekrar spam'i yok. | Bekliyor | Yolcu notu F1 |
| F2. Parkta Ayarlar'da **beş** ses profilini seç/önizle. | Beş seçenek erişilebilir; kullanıcı kapatınca ses hemen kesilir. Eksik dosya varsa açık sessizlik/hata, sahte oynatma iddiası yok. | Bekliyor | Telefon ekran kaydı F2 |
| F3. Gerçek ve güncel OBD hız ölçümüyle **yolcu** ses değişimini gözlemlesin. | Gerçek OBD hızıyla tempo/perde değişimi; OBD ölçümü yoksa otomatik dinamik ses **başlamaz**. | Bekliyor | Yolcu F3 notu + maskeli Debug OBD; sürücü dokunmaz |
| F4. Ses açıkken navigasyon TTS anonsunu dinle. | Motor/radyo sesi TTS sırasında kısılır (**ducking**), anons bitince geri gelir; kapatma anında etki eder. | Bekliyor | Yolcu notu F4 |

## G — Bluetooth ELM327 / salt-okunur OBD

| Adım | Beklenen sonuç | Geçti/Kaldı | İstenen kanıt |
| --- | --- | --- | --- |
| G1. Park halinde eşleştirilmiş adaptöre bağlan. | İzinli Bluetooth bağlantısında **CONNECTED**; yoksa DISCONNECTED / açık hata, BMS SoC **kullanılamıyor**. | Bekliyor | Telefon foto G1 + Debug OBD |
| G2. Park halinde OBD bağlantısını kesip tekrar bağla. | Uygulama çökmez; durum geçişleri doğru, eski hız ölçümü güncelmiş gibi kullanılmaz. | Bekliyor | Önce/sonra Debug G2 |
| G3. Yalnız mevcut otomatik hız okuması veya parkta kullanıcı seçimiyle izinli UDS `0x22` oku. | Maskeli `410Dxx` ya da izinli UDS yanıtının kısa öneki; VIN, adres, tam ECU yükü paylaşılmaz. DID/çarpan **doğrulanmadı**. | Bekliyor | Maskeli Debug G3 |
| G4. ECU yazma koruması. | `0x2E`, `0x31`, `0x11`, `0x27` vb. tel katmanında **reddedilir**. **Araçta yazma komutu DENEME**; kanıt GitHub CI'daki `ObdWireCommandPolicyTest` + `DiagnosticsHubTest`tir. | Bekliyor | CI URL ve test sonucu G4; araçta yazma testi yapılmaz |

## H — Video ve Android Auto sürüş güvenliği

| Adım | Beklenen sonuç | Geçti/Kaldı | İstenen kanıt |
| --- | --- | --- | --- |
| H1. Araç parkta ve video uygulamasını telefondan aç. | Açık kullanıcı park onayı **ve** gerçek güncel OBD 0 km/sa ölçümü olmadan video **engellenir**. Sadece onay kutusu yeterli değildir. | Bekliyor | Telefon ekran kaydı H1 |
| H2. Android Auto içindeki EV Cockpit ekranlarını dolaş. | **Video/WebView oynatıcısı veya video şablonu yok**; yalnız navigasyonun izinli Car App şablonları var. | Bekliyor | Parkta foto H2 |
| H3. Hareket halinde video güvenliği. | **Sürücü asla video açmayı veya test yapmayı denemez.** Yolcu varsa yalnız gözlemci olarak engelleme davranışını not eder; OBD hız ölçümü yoksa video zaten kapalı kalır. | Bekliyor | Yolcu notu H3; uygunsuz koşul varsa Test edilemedi |

## Sonuç / teslim

| Başlık | Sonuç |
| --- | --- |
| Geçen test sayısı | 0 (henüz doldurulmadı) |
| Kalan / başarısız | Tümü bekliyor |
| Kritik engeller | 09.11.2026 ilk raporda konum, bildirim, Bluetooth izni verilmemiş |
| Ekran fotoğrafları / kayıtlar | Yok — bekleniyor |
| Maskeli tanılama raporları | İlk başlangıç raporu sağlandı |
| Telefon testi | DOĞRULANMADI |
| ID.4 / Android Auto testi | DOĞRULANMADI |
| İmzalı Release kararı | **HAYIR** — kritik gerçek araç kabul kanıtları eksik |

**Kanıt gönderme:** Test ID'siyle birlikte (ör. `C3`) “Geçti / Kaldı / Test edilemedi”, kısaca gözlem ve maskeli rapor/fotoğraf paylaş. Görselleri ve raporu göndermeden önce plaka, özel hedef, ev/iş koordinatı, VIN, MAC, kullanıcı hesapları ve diğer kişisel bilgileri gizle. Paylaşım tamamen senin kontrolündedir.

**Önemli ayrım:** `main` ve Release bu dokümanla değişmez. İş 3'e ancak İş 2 kabulünden ve ayrı onaydan sonra geçilecek.
