# EV Cockpit — Sürüm Notları

Bu belge güncel kullanıcıya açık uygulamanın değişikliklerini özetler. Eski teknik deneyler ve bunların özgün kayıtları Git sürüm geçmişinde korunur. Telif sahipliği ve üçüncü taraf lisans bildirimleri [LICENSE](LICENSE), [NOTICE.md](NOTICE.md) ve [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) dosyalarındadır.

## 9 Kasım 2026 — İş 3: yayın güvenliği hazırlığı (DRAFT)

- `nativecockpit` kaynak sürümü Release için `1.0.8`, ayrı Debug
  kimliği için `com.evcockpit.debug` / `1.0.8-DEBUG` olarak tanımlandı.
  Eski `-TEST` eki yeni derlemelerde yok. Release **yayımlanmadı**.
- Release: mevcut `isDebuggable=false`, `allowBackup=false`,
  `usesCleartextTraffic=false`, R8 ve kaynak küçültme korunuyor.
  Sistem CA'larıyla HTTPS-only ağ yapılandırması ve `arm64-v8a` ABI
  sınırı eklendi. Yayın anahtarı yerine geçici CI anahtarıyla **yalnız
  Release smoke derlemesi** tanımlandı; APK dağıtılmıyor.
- Eski `YoutubeAdFilter` sınıfı için uygulama kaynak ve Debug DEX
  taraması eklendi. Ayrı eski medya APK'sının da temiz olduğu
  **bu kontrollerden çıkarılamaz**.
- Open-Meteo'nun gerçek GPS/OSRM rota kanıtı arayan `30 dakika`
  foreground yenileme sınırı doğrulama kapsamına alındı.
  Doze/ağ kopması/zamanlayıcı ertelemesi nedeniyle tam dakikada
  çalışma **garanti edilmez**.
- 34 açık PR'ın bağımlılık haritası ve main çakışma riskleri belgelendi.
  Repo açıklaması/Topics henüz uygulanmadı; eski kök ZIP bir
  Releases asset'i olarak doğrulanmadığından **silinmedi**.
- Eski ifşa edilmiş imza bilgisi nedeniyle `release.yml` üzerindeki
  `if: false` kilidi kaldırılmadı. Android Auto/ID.4 cihaz kabulü
  ve güvenilir imzalı Release **DOĞRULANMADI**.

## 9 Kasım 2026 — İş 1 ve İş 2 (taslak kaynaklar)

- [Tanılama PR #46](https://github.com/AlpTimucin-cpu/ev-cockpit/pull/46):
  Debug 500 satırlık maskeli uygulama günlüğü ve paylaşım; otomatik
  CI geçti, gerçek araç doğrulaması yok.
- [Telefon/ID.4 UAT PR #47](https://github.com/AlpTimucin-cpu/ev-cockpit/pull/47):
  A–H protokol belgesi; manuel araç kabulü henüz tamamlanmadı.

## 26 Ekim 2026 — Tesla esintili ABRP planlayıcı geliştirmesi

- Yatay kokpitin harita payı %75 olarak ayarlandı; batarya kartları ve manevra kartı daha sade yerleşime alındı. **Tarayıcı görsel testi hedefleniyor; gerçek araç ekranında doğrulanmadı.**
- Rota kaynaklı tüketim, sıcaklık ve DC şarj eğrisi ayrı ve birim testli JS modülüne taşındı. Sonuçların tamamı **model tahminidir**.
- EPDK rota koridoru 5 km olarak daraltıldı. Kayıt tarihi korunur. Gerçek sürüş sapma süresi, canlı istasyon müsaitliği ve fiyatı **doğrulanmadı**; hiçbirini uydurmaz.
- Doğrulanmış GPS/OBD geldiğinde batarya renkli rota çizgisi hesaplanabilir; aksi hâlde sahte pil göstergesi oluşturulmaz.
- OBD veri yaş eşiği 10 saniye olarak düşürüldü; Java köprüsü eski geriye dönük callback’lere ek olarak zaman damgalı `evcockpit:obd`, `evcockpit:route`, `evcockpit:gps`, `evcockpit:stations`, `evcockpit:weather` olayı gönderir.
- Türkçe yerel TTS yönlendirmesine yaklaşık 1 km/300 m/100 m/şimdi eşikleri ve kısa süreli ses odağı eklendi. **Cihaz üzerinde anons kesinliği ve ses odağı doğrulanmadı.**
- Android Auto, gerçek şarj istasyonu erişilebilirliği, GPS yol eşlemesi ve SoC PID kalibrasyonu cihaz testine kadar **doğrulanmadı** kabul edilir.

## 26 Ekim 2026 — Entegrasyon ve marka yenilemesi

- Görünen Android uygulama adı ve kokpit başlığı **EV Cockpit** olarak güncellendi.
- Özgün elektrikli otomobil/rota uygulama simgesi ve daha sade medya kartları hazırlandı.
- Harita üzerinde tek Leaflet örneği korunarak gerçek OSRM GeoJSON rota geometrisi ve mevcut açık harita altlığı kullanıldı. Var olan güzergâh/istasyon fonksiyonları değiştirilmedi.
- Android'deki doğrulanmış Bluetooth OBD ölçümleri, mevcut yerel olay köprüsü üzerinden arayüze aktarılmaya devam ediyor. Sahte SoC, voltaj, hız veya kalan menzil gösterilmiyor.
- Rota ilerleme çubuğu yalnız yeni ve yeterince doğru GPS konumunu rota geometrisine izdüşürerek güncelleniyor. Araç ikonu çubuk sınırları içinde tutuluyor.
- Harita üzerindeki hava etiketleri ve alternatif rota çizgileri daha okunaklı olacak şekilde sınırlandırıldı; veri ayrıntıları korunuyor.
- Trafik katmanının gerçek yükleme durumu gösteriliyor; trafik verisi doğrulanmamışsa canlı durum etiketi kullanılmıyor.
- Kişisel trafik servis anahtarının cihazda şifreli saklanması ve eski yerel ayarlardan güvenli taşınması eklendi.
- Yayın öncesi kaynak, görünür marka, sürüş güvenliği ve APK paketleme kontrolleri güçlendirildi.
- Teknik entegrasyon: [Harita, araç verisi ve rota ilerleme rehberi](docs/ELEKTRIKLI_YOL_ARKADASIM_ENTEGRASYON.md).

## 14–20 Ekim 2026 — Medya ve rota geliştirmeleri

- Canlı TV, radyo, podcast, yerel medya ve web özellikleri için modüler kullanıcı arayüzü.
- Var olan medya oynatıcı oturumlarının korunması; ağ kopması, oynatıcı yeniden bağlanma ve kullanıcı dostu hata açıklamaları.
- Rota arama, alternatif güzergâh, istasyon önerisi ve örnek veriden ayrılan enerji tahminleri.
- Ekran yönü, telefon görünümü ve araç ekranındaki kart yerleşimlerinin iyileştirilmesi.
- Harita, GPS, telemetri ve bağlantı izni hatalarında çökmeyi önleyen korumalar.

## Yayın / kullanım notu

Sürüm **test aşamasındadır**. Otomatik kaynak ve derleme testlerinin geçmesi, gerçek cihazda canlı trafik ya da OBD bağlantısının doğrulandığı anlamına gelmez. Sürücü dikkatini dağıtabilecek görsel medya için hareket hâlindeki kullanım kısıtlamaları kaldırılmamıştır.
