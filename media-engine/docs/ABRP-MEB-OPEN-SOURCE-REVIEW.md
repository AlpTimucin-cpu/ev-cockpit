# ABRP ve açık kaynak EV rota/OBD bileşenleri — EV Cockpit

İncelenme: 2026-10-25. Durum: **kaynak incelemesi; gerçek araç doğrulaması veya ABRP API bağlantısı değil**.

## Kaynak durumu ve lisans

1. ABRP'nin resmi çeviri deposu, uygulamanın kaynak kodunun **kapalı** olduğunu açıkça belirtiyor:
   https://github.com/Iternio-Planning-AB/abrp-translations
2. ABRP'nin resmi açık OBD PID koleksiyonu (Apache-2.0, alt dizin lisans istisnaları olabilir):
   https://github.com/Iternio-Planning-AB/ev-obd-pids
   VW MEB profili:
   https://github.com/Iternio-Planning-AB/ev-obd-pids/blob/main/volkswagen/MEB.json
3. Bu MEB listesinin atıf yaptığı topluluk araştırması:
   https://github.com/spot2000/Volkswagen-MEB-EV-CAN-parameters
   Araç yılı/yazılımı uyumluluğu otomatik olarak kanıtlanmış sayılmaz.
4. ABRP'nin resmi ücretli rota API'si:
   https://web.abetterrouteplanner.com/resources/api
   https://api.iternio.com/swagger-ui/
   /plan çağrıları ücretli olabilir; uygulamanın anahtarını kazımak/kopyalamak veya özel
   uç noktalarını yetkisiz kullanmak yerine Iternio'dan resmi geliştirici anahtarı alınmalı.
5. ABRP'ye bağlanan **üçüncü taraf**, ABRP motorunu barındırmayan örnekler:
   - MIT: https://github.com/Casperjuel/abrp-mcp
   - MIT: https://github.com/tillsteinbach/CarConnectivity-plugin-abrp
   - Apache-2.0: https://github.com/iternio/autopi-link
6. Bağımsız EV şarj durağı grafı / zaman minimizasyonuna örnek MIT prototip:
   https://github.com/giraldeau/evnav
   Dijkstra grafında kaynak, varış ve şarj istasyonları; kenar maliyeti yolculuk
   + şarj süresi + durağın ek yüküdür. Kaynak kodu prototip, ölçek ve hava/yükseklik eksikleri var.

## MEB OBD kıyaslaması

ABRP'nin `MEB.json` profili bir başlatma protokolü tanımlar:
`ATZ`, `ATE0`, `ATL0`, `ATSP7`, `ATBI`, `ATSH FC007B`,
`ATCP 17`, `ATCAF0`, `ATCF 17FE7`, `ATCRA17FE007B`.

Çerçeveli, salt-okunur istek örnekleri:
- `0322028C55555555`: batarya SoC, formül `1.12*A/2.5-7.16`.
- `03221E3B55555555`: HV gerilim.
- `03221E3D55555555`: HV akım.
- `0322744855555555`: şarj / DCFC durum biti.

Mevcut `EvCockpitLiveTelemetry.java` sırayla
`ATSH17FC007B`, `ATCRA17FE007B` ve `22028C` vb. kullanıyor.
Bu aynı başlık/çerçeveleme değildir. Her ikisi de bazı adaptörlerle çalışmayabilir.
İçerisindeki `EvCockpitMebDecoder.java` SoC ölçeği `A/2.5` olup ABRP listesi
ile **farklıdır**. Ayrıca `VEHICLE_PROFILE_VALIDATED=false` bilinçli güvenlik
frenidir. Bu bayrak, sırf GitHub'da bir liste var diye açılmamalı.

### Güvenli doğrulama planı

- Kullanıcının eşleştirdiği ELM327 ile yalnızca *kullanıcı başlatmalı*,
  durgun/park halindeki araçta ve diğer teşhis uygulaması kapalıyken çalıştırılmalı.
- İki adresleme seçeneği için önce yalnızca pozitif `62xxxx` yanıtı, süre aşımı,
  negatif `7F` yanıt ve kaynak CAN adresleri kayıt altına alınmalı.
- ELM327 donanımının 29-bit, extended CAN ve çoklu çerçeve yeteneği doğrulanmalı.
- Formüller, aynı anda başka bir referanstan alınmış **bağımsız, bilinen** SoC
  ve gerilim ölçümleriyle birden fazla seviyede karşılaştırılmalı.
- Ölçümlerin eskime süresi, kaynak türü, güven seviyesi ve arıza ayrımı kullanıcıya
  açıkça gösterilmeli. Uyumsuz PID kayıtları ölçüm sanılmamalı.
- Sadece okuma: ECU reset, yazma, kodlama, güvenlik erişimi, DTC silme YOK.
- SoC doğrulanana dek kullanıcının seçtiği **%80 / %100 senaryosu** gerçek
  araç ölçümü olarak gösterilemez.

## Rota planlaması için uygulama tasarımı

**İki bağımsız planlama yolu**:
1. Ücretsiz/yerel model: OSRM/Valhalla gerçek geometri + EPDK lisanslı
   istasyon kaydı + Model 16–24 kWh/100 km + batarya %15 rezerv +
   kullanıcının %80/%100 senaryosu ya da onaylı BMS SoC. Alternatifler için
   istasyon koridoru yetmez: gerçek **sapma mesafesi/süresi**, duraklarda
   enerji transferi, şarj hızı/eğrisi ve mediaengineların sırası gerekir.
2. Opsiyonel yetkili ABRP API: kullanıcı/operatörün **kendi API anahtarı**,
   açık maliyet/çağrı onayı, gizli anahtarın APK'da sabitlenmemesi, resmi
   limitlerin korunması. ABRP resmi planın kaynağı olarak ayrıca gösterilir.

**Öncelikli geliştirme sırası**:
- Şarj sekmesinde ayrı Activity açıp rota kaybettirmeden mevcut haritanın
  EPDK şarj katmanını açma ve %80/%100 anında hesaplama.
- Rota/şarj istasyonu harita eşleştirmesi; gerçek yol sapması ve güç sınıfı.
- İstasyon başına fiyat (yalnızca resmi güncel tarifeler) ve enerji/yol maliyeti ayrımı.
- SoC hesaplarının şarjlarla segment bazında tekrarlanması; varış SoC rezerv kontrolü.
- ABRP MEB PID'lerinin yukarıdaki test planıyla laboratuvar/saha doğrulaması.
- Trafik/hava/hız limiti/sabit kamera bilgileri yalnızca kaynak ve kapsama durumu ile.

**Dürüst sınırlama:** Buradaki kaynakların okunması, EV Cockpit'te ABRP canlı veri,
kamera kapsamı, ücretli API yetkisi veya navigasyon fonksiyonunun çalıştığını kanıtlamaz.
