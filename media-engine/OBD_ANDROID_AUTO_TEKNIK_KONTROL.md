# EV Cockpit — OBD / Car Scanner / Android Auto kontrolü (23 Ekim 2026)

## OBD bağlantısı: mevcut uygulamada gerçekten ne çalışıyor?

- Kullanılan kod: `EvCockpitLiveTelemetry.java` → eşleştirilmiş klasik Bluetooth
  **RFCOMM/SPP** `00001101-0000-1000-8000-00805F9B34FB`.
- Adaptör bağlantısı için `ATZ, ATE0, ATL0, ATS0, ATH0, ATSP7,
  ATSH17FC007B, ATCRA17FE007B` gönderiliyor. `ATSP7`
  ISO 15765-4 CAN **29 bit, 500 kbaud** anlamındadır.
- Ardından salt-okunur UDS Mode 22 DID `028C` (SoC), `1E3B`
  (voltaj), `2A0B` (ana sıcaklık), `7448` (şarj durumu),
  `189D` (soğutma) isteniyor.
- Bu adresler ve dönüşüm katsayıları **kullanıcının 2024 VW EV Cockpit Pure**
  ECU yazılımında araç üzerinde henüz doğrulanmadı.
  Boş/geçersiz yanıt `—` kalmalı; SoC üretip pil hesaplamasına
  aktarılmamalı. `EvCockpitMebDecoder.java` yalnız `62+DID` pozitif
  yanıtını yorumlar ve voltaj/sıcaklık gibi alanlarda makul aralık
  denetler.
- Kullanıcının Car Scanner uygulaması **bizim uygulamaya doğrudan
  canlı veri aktarmıyor**. Car Scanner'ın kayıtları CSV olarak dışa
  aktarılabilir. CAN/UDS custom PID açıklamaları Car Scanner
  belgelerinde vardır; bu özellik gerçek zamanlı yerel yayın API'si
  ile karıştırılmamalı.
- Aynı ELM327 Bluetooth soketini Car Scanner ve EV Cockpit uygulaması aynı
  anda güvenilir şekilde paylaşamaz. Bu sebeple `OBD ayır` eklendi.
  Bu, soketi kapatıp, `manual_disconnect=true` saklayarak Activity
  tekrar açıldığında otomatik yeniden ele geçirmeyi durdurur.
  Yeniden `OBD bağla` ile adaptör seçildiğinde saklanan engel kalkar.
- Klasik Bluetooth SPP dışındaki **BLE ve Wi-Fi ELM327** bu modülde
  yoktur. POCO F5 telefonun eşleştirilmiş aygıt listesinde görünmeyen
  BLE adaptörü destekleniyor diye varsayılmaz.
- **Güvenli doğrulama sırası (araç park halinde):** Car Scanner'ı
  tamamen bağlantıdan çıkar → Bluetooth sistem eşleştirmesi →
  uygulamada OBD bağla → SoC, sıcaklık, voltajı oku →
  Car Scanner'la karşılaştır → PID teyit edilmediyse model tahminini
  gerçek gösterge olarak kabul etme. Varsa Car Scanner CSV/ELM loguyla
  yalnız parametrelerin adı/zaman damgası/yanıt uyumu karşılaştırılır.
- UDS belleğe yazma, güvenlik erişimi veya DTC silme **yapılmıyor**.

## Android Auto bağlantısı

- `patch_mediaengine.py` MediaEngine medya uygulamasına **telefon içi**
  `EvCockpitNavigatorActivity` ekler, `android:exported=false`.
- MediaEngine başlangıcında EV Cockpit navigasyon giriş düğmesi
  `getRuntimeHostMode().usesAutomotivePresentation()` koşulu
  doğruysa **bilerek gizleniyor**.
- `CarAppService`, `androidx.car.app.category.NAVIGATION`,
  `androidx.car.app.NAVIGATION_TEMPLATES` ve harita
  `SurfaceCallback` bu sürümde **yok**. Bu nedenle
  telefondaki WebView tam ekran arayüzü Android Auto
  hostunda navigasyon olarak ilan edilemez.
- Gerçek Android Auto için bağımsız, sürücü güvenliği kurallarına
  uygun Car App navigation implementasyonu, uygun bağımlılıklar,
  service bildirimi ve host simülatörü/DHU + araç testleri gerekir.
  Yalnız manifest etiketi eklemek bu açığı gidermez.
- Trafik haritası ve ekrandaki butonlar **telefon görünümüne**
  aittir; Android Auto doğrulama raporu değildir.

## Son kullanıcı ekranı kararlılığı

- Kalın siyah `-webkit-text-stroke` iptal edildi, font boyutları
  dar panellere göre küçültüldü. Sürüş süresi hem etikette
  'trafiksiz' hem değer içinde iki kez gösterilmiyor.
- Dar yatay ekranlarda sadece esas batarya ve teyit edilebilir
  ölçümler görünür; doğrulanmamış alanlar gerçekmiş gibi
  doldurulmaz.
- Başarılı Node tests / GitHub Actions APK başarılı olsa dahi
  fiziksel 2024 EV Cockpit, POCO F5 ve Car Scanner beraberlik testi
  **yapılmış değildir**.

Kaynaklar:
- https://developer.android.com/training/cars/apps/navigation
- https://developers.google.com/cars/design/android-auto/apps/nav-apps
- https://www.carscanner.info/custompids/
- https://www.carscanner.info/records/
- https://www.cds.caltech.edu/~murray/dgc05/upload/a/a9/ELM327DS.pdf


## 23 Ekim 2026 — APK'dan doğrulanan sınırlar

- Son indirilen APK içindeki `assets/evcockpit/traffic-config.js` incelendi: `googleKey` ve `yandexKey` alanlarının **ikisi de boş**. Bu nedenle ücretli sağlayıcı trafiği mevcut paket için devre dışı; OSRM ETA, trafiksizdir.
- ELM327 Bluetooth istemcisi var ama `EvCockpitMebDecoder.VEHICLE_PROFILE_VALIDATED=false`: kalibre edilmiş EV Cockpit SoC/voltaj sıcaklık rakamları sunulmaz. Bağlantının fiziksel araçta çalıştığı henüz gösterilmedi.
- `patch_mediaengine.py` yeni Android Auto `CarAppService` / `androidx.car.app.category.NAVIGATION` bildirmiyor ve otomotiv sunumunda EV Cockpit girişini gizliyor. Bu nedenle EV Cockpit telefon kokpiti araç ekranında desteklenmiyor.
- Hedef geniş ekran yerleşiminde soldaki kartlar, orta harita, sağ rota ve alt güzergâh ilerleme çubuğu gerçek uygulama verileriyle çalışır; 68% ve 318 km gibi referans görsel numaraları, sanal klima veya araç kontrol düğmeleri eklenmez.
