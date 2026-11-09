# EV Cockpit — Native Kokpit QA / UAT
Tarih: 29.10.2026 · Dal: `feature/ev-cockpit`

## Kapsam ve üretime hazır olma kararı
Bu geçiş **şimdilik production-ready değildir**. Derleme ve JVM birim testlerinin başarılı olması,
donanımda TTS/OBD, Android Auto, PiP ve üçüncü taraf medya uyumluluğunu doğrulamaz.

### Statik kod incelemesi
- **TTS:** `NavigationViewModel.onCleared()` -> `guide.close()` -> `tts.stop()`, `tts.shutdown()`, `ducking.close()`. `MainActivity.onPause()` -> `setForeground(false)` -> `guide.stop()`. Android Auto `EyaCarNavigationScreen.onStop()` seslendirimi durdurur; `onDestroy()` ses motorunu kapatır. Activity her duraksadığında TTS'yi shutdown etmek, yeniden başlamada motoru kullanılamaz kılardı; bu nedenle sadece stop yapılır.
- **AudioFocus:** `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`; konuşma bittiğinde veya iptalinde odak bırakılır. QA düzeltmesi `AudioFocusLease` ile tek sahipliği ve stale callback korumasını test edilebilir kılar. Üçüncü taraf oyuncunun gerçekten ses kısması **garanti değildir**.
- **PiP:** Manifest `supportsPictureInPicture=true` ve `OwnVideoPip` yalnızca *uygulamamızın sahip olduğu* aktif video için çalışabilir. Dış Netflix/Disney+/Prime PiP özelliği diğer uygulamaya aittir. Kodda `ParkedStreamingDialog(verifiedParked=false)` olduğu için Native Kokpit video başlatma butonları **devre dışı**. Araç hareketli video kilidi/DRM devre dışı bırakılamaz. Harici uygulamaya geçildiğinde `MainActivity.onPause()` GPS/TTS ve OBD akışını durdurur; arkada navigasyon sürdürülmez. Bu bir yaşam döngüsü kısıtıdır, çökme kanıtı değildir.
- **OBD:** ELM327 oturumu `DISCONNECTED → CONNECTING → READY / ERROR` durumlarını yayımlar; `ATZ, ATE0, ATL0` ile sıralı başlar ve salt-okunur sorguları en çok üç denemeyle sınırlar. `close()` socket/kanalı kapatıp eski işlemleri geçersizleştirir; kullanıcı yeniden bağlan dediğinde aynı oturum nesnesi `initialize()` ile **yeniden açılabilir**. Kopma ve zaman aşımı sonrasında `ElmSessionState.ERROR` yayımı ile `ObdConnectionState(phase=ERROR, error=...)` UI eşleşmesi gerçek cihaz testinde ayrı doğrulanmalıdır.
- **Enerji:** `EvBaselineEnergyEstimator` 110 km/sa üstünde logaritmik hız katsayısı, sıcaklıkta klima etkisi uygular. Eksik girdide tarafsız çarpan kullanılır, NaN ve bayat OBD reddedilir. Çarpanlar ölçülmüş fiziksel model değil, kalibrasyon varsayımıdır.

## JVM otomatik test matrisi
| Test dosyası | Sınıf / senaryo | Beklenen |
|---|---|---|
| `EvBaselineEnergyEstimatorTest.kt` | 65 km/sa, 40°C, klima açık | hız 1.00, klima 1.08, 19.44 kWh / 100 km |
| aynı | 150 km/sa, -5°C, klima açık | hız = 1 + .12·ln(3), klima = 1.24 |
| aynı | hız/sıcaklık eksik; NaN/Infinity | varsayılan 1.00/1.00, geçersiz girdiye Unavailable |
| `Elm327SessionTest.kt` | BT kopma bir kez | reconnect, ELM init yeniden |
| aynı | sürekli BT kopma / kontağın kapanması | 3 denemede IOException, soket kapalı |
| aynı | close sonrası açık kullanıcı yeniden başlatma | DISCONNECTED → CONNECTING → READY; eski yanıtlar sızmaz |
| `AudioFocusLeaseTest.kt` | fake MediaEngine oynatıcı + geçici TTS focus | taklit ses kısılır ve eski seviyesine döner |
| aynı | focus reddedildi / geç callback | medya değişmez; close sonrası callback yok sayılır |

## Telefon/araç üzerinde UAT
**Ön şartlar:** test APK'sının SHA-256 değerini karşılaştır; Türkçe TTS dil paketini yükle; Android konum iznini aç; telefonu ve aracı güvenli şekilde park et; yalnız kendi Bluetooth OBD adaptörünü kullan. Yol testlerinde ikinci bir kişi gözlem ve kayıt yapmalı; sürücü ekranla etkileşime girmemeli.

1. Native APK'yı kur ve uygulamayı aç. Açılışta ANR/crash olmadığını kontrol et.
2. Konum iznini reddet: harita doğru hata göstermeli; sahte GPS göstermemeli. Ardından hassas konum izni ver ve GPS'in güncellendiğini doğrula.
3. `SES` ekranından `Türkçe ses testi` çalıştır: 10 defa tekrarla; ses motoru boşa düşmemeli ve bekleme sırasında ses odağı tutulmamalı.
4. Park halinde, telefon üzerinde izin verilen bir ses medyası (MediaEngine) çal; SES testi başlat. Müzik Android sisteminin seçimine göre kısılmalı/durmalı ve konuşma sonunda normale dönmeli. Dönmüyorsa FAIL.
5. Gerçek OSRM rota oluştur; rota çizgisini ve manevraları doğrula. Güvenli gerçek yol koşullarında yalnız yolcu gözlemiyle yaklaşık 200 m / 50 m anonslarını takip et. Uygulamanın mock GPS reddi gerçek sürüş testinin yerine geçmez.
6. TTS sırasında uygulamayı arka plana al; konuşma durmalı, ses odağı bırakılmalı. 10 kez arka plan/öne alma ve 5 kez ekran döndürme sonrası bellek/ANR kontrolü yap.
7. Park halindeyken OBD adaptörünü eşleştir ve bağlan. Doğrulanmış ham `010D` sorgusu dışında ekranda olmayan veri uydurulmamalı.
8. OBD bağlıyken adaptörün bağlantısını kontrollü kes veya test adaptörünün gücünü kapat. Üç başarısız denemede UI ERROR görmeli ve eski veri canlı gibi kalmamalı. Adaptör yeniden açıldığında açık kullanıcı işlemiyle reconnect et.
9. `Medya` bölümünde güvenilir Park verisi bulunmuyorsa Netflix/Disney+/Prime düğmelerinin devre dışı kaldığını doğrula. Beklenen davranış budur.
10. Telefonun **kendi Netflix/Disney+/Prime** uygulamasındaki PiP'i, o uygulama destekliyorsa ayrıca dene. Bu, `EV Cockpit`ın PiP testi değildir. Native Kokpit'in harici uygulamayı PiP'e zorlaması beklenmemeli.
11. Uygulamanın kendi oynatıcısı Park bilgisiyle doğrulandığında (gelecekte), yalnız ona ait PiP geçişi/giriş/çıkışı sınanmalı; şimdiki sürümde bu uçtan uca akış yok.
12. Android Auto DHU ve gerçek araç oturumlarında TTS sahipliği çakışmasını, bağlantı kesilmesi/yeniden bağlanma ve 13.000+ istasyon harita kaydırma akıcılığını ayrıca test et.

## Ölçülebilir kabul testleri — ayrıntılı protokoller

**Ölçüm kuralı:** Aşağıdaki sayılar Android'in garanti ettiği sistem sınırları değil, **proje içi QA kabul eşikleridir**. APK commit SHA'sı, cihaz modeli, Android sürümü, ekran yenileme hızı, şebeke koşulu, katalog sürümü, GPS/OBD veri kaynağı ve test tarihini her koşuyla kaydet. Isınma (warm-up) sonrasında aynı sürümü **üç bağımsız kez** ölç; tek iyi koşuyu başarı kabul etme. Telefonun güç tasarrufunu kapat; termal kısıtlamaya giren koşuyu geçersiz sayıp cihaz soğuyunca tekrarla. Performans için **release-benzeri, profillenebilir** build kullan; debug+Profiler'ın ölçümü etkileyebileceğini raporda belirt.

### QA-MAP-13K — EPDK şarj istasyonu / MapLibre dayanıklılığı

**Ön koşul:** Gerçek kaynak metadata'sı ve kayıt sayısı kaydedilmiş **en az 13.000 geçerli, tekilleştirilmiş koordinatlı** istasyon; tüm haritaya aktarılmış `GeoJsonSource` (filtre `ALL`). Katalog 13.000'in altındaysa testi **BLOCKED** işaretle, eksik veriyi gerçek EPDK kaydı diye sunma. İnterneti sabitle; harita karo ağı gecikmesini `MapLibre` işleme gecikmesinden ayır. Sabit 60 Hz görüntüleme koşulu tercih et; başka Hz'de de 16,7/33,3 ms eşiklerinin 60 Hz'e özgü olduğunu kaydet.

**Test adımları:**
1. Uygulamayı yeniden aç, `ALL` filtresini seç; 13.000+ kayıt sayısını ve `withCluster(true)`, `withClusterRadius(60)`, `withClusterMaxZoom(13)` ayarlarını doğrula. Bir kez veri/karo ısınması yap; ilk beklenen yükleme gecikmesini ayrı kaydet.
2. Android Studio **System Trace / Perfetto FrameTimeline** kaydı başlat. 60 saniyede en az **30 yatay/dikey pan** hareketi yap; Türkiye ölçeğinden il ölçeğine geçişi kapsa. Her tekrarın geçerli `FrameTimeline` örnek sayısını yaz.
3. Zoom **4 → 8 → 12 → 15 → 12 → 8 → 4** dizisini **10 kez** uygula. Ekrandaki küme sayılarının yakınlaştırmada açıldığını, tekli istasyonların kaybolmadan seçilebildiğini görsel olarak doğrula.
4. **20 küme dairesine**, ardından **20 tekil istasyon işaretine** dokun. `ACTION_UP` ile görsel tepkinin ilk karesi arasını, küme zoom başlangıcı/bitişi ve istasyon ayrıntısı görünme süresini trace/video olay zamanlarıyla ölç. Yanlış istasyon seçimini ayrı say.
5. Pan/zoom/tıklamayı toplam **10 dakika** sürdür; `logcat` içinde `ANR`, `FATAL EXCEPTION` ve `OutOfMemoryError` ara. 3 koşunun her birinde tüm eylemler tamamlanmalı.


**Tekrarlanabilir ölçüm / kanıt:**
- Test datasını CSV/GeoJSON SHA-256, EPDK doğrulama tarihi, toplam kayıt, benzersiz istasyon ID ve geçerli koordinat sayısıyla sabitle. Başarı için **en az 13.000 nokta MapLibre kaynağında** bulunmalı; ekranda aynı anda 13.000 sembol görünmesi gerekmez (cluster özelliği). Bu sayım desteklenmiyorsa sonucu BLOCKED kaydet.
- Önceden belirlenmiş iki sabit viewport ve aynı zoom seviyelerini üç koşuda da kullan; gesto sırasını sabitle. 60 Hz referans ekranda 10 tam zoom dizisi, 30 pan, 20 küme ve 20 tekil marker dokunuşunu **her koşu** için uygula. Dokunuş hedefinin gerçek tekil feature ID'sini ve ekranda açılan istasyon ID'sini karşılaştır.
- Trace başlat/durdur ile uygulama yükünü ayır: pan/zoom için input ACTION_DOWN/ACTION_UP ve ilk görsel kare; marker için ACTION_UP ve ayrıntı kartının ilk çizildiği kare; cluster için ACTION_UP, kamera animasyonu başlangıç/bitişini zaman damgasıyla eşleştir. Perfetto FrameTimeline/janky frame verisini ekran videosuyla senkronize et. Uygulama içi callback zaman damgası veya güvenilir video/trace eşleştirmesi yoksa ilgili **gecikme metriği BLOCKED**.
- p95 hesabı: her koşunun ham milisaniye örneklerini artan sırala; **nearest-rank p95 = sıralı[n = ceil(0,95 × örnek_sayısı)]** (1 tabanlı). Her metrik için örnek sayısı (pan ≥ 30, cluster ≥ 20, marker ≥ 20), p50, p95, maksimum, 60 Hz'de toplam kare/jank yüzdesini ayrı kaydet. Bir koşu limit dışındaysa üç koşunun ortalamasıyla gizleme.
- ANR tanısı: Android'in sistem ANR kaydı ile 1 saniyelik **proje içi kullanıcı-tepkisizlik eşiği** ayrı kavramlardır; sistem ANR için sabit 1 saniyelik tanım kullanma. ANR traces/logcat ve mümkünse ApplicationExitInfo belgesini sakla.
**Beklenen sonuç / PASS:** Her geçerli koşuda **0 crash / 0 ANR / 0 OOM**; haritada **1 saniyeyi aşan tepkisizlik 0**; **60 Hz'de görüntü kare süresi p95 ≤ 33,3 ms**, janky frame oranı **≤ %10** (FrameTimeline); pan/zoom ilk tepki **p95 ≤ 250 ms**; küme zoom animasyonu başlangıcı **p95 ≤ 250 ms**, tamamlanması **p95 ≤ 1.500 ms**; tekil marker ayrıntısı **p95 ≤ 500 ms**; **0 yanlış işaret/istasyon**. Bütün süreleri *ms*, p95'i ham örneklerden hesapla. Trace/video veya örnek sayısı yoksa ölçüm **BLOCKED**, gözleme dayalı PASS değil.

**Başarısızlık durumunda:** İhlalin `P95_FRAME`, `JANK`, `PAN_LATENCY`, `CLUSTER_ZOOM`, `MARKER_TAP`, `ANR`, `DATA_COUNT` kodunu kaydet; ekran kaydı, `Perfetto` trace, ağ/karo gecikmesi ve kataloğun GeoJSON seri hale getirilme süresini iliştir. Kaynak güncellemelerinin gereksiz yeniden tetiklenmesini, ana thread'de GeoJSON oluşturulmasını, hatalı/tekrarlı istasyonları ve style reload'larını araştır. Ağ gecikmesi ise veri önbelleği/sunucu ile GPU/cluster hatasını ayrı raporla; düzeltmeden sonra **aynı 3 koşuyu tekrar** et.

### QA-MEM-TTS — TTS / AudioFocus sızıntı ve lifecycle testi

**Ön koşul:** Gerçek Türkçe TTS motoru yüklü. **Android Studio Memory Profiler** içinde Java/Kotlin heap, Native heap ve `Allocations` kaydı kullan. Aynı APK, aynı cihaz, aynı ekran; uygulama stabil olduğunda **2 dakika boşta bekleyip GC** sonrası başlangıç (B0) snapshot'ı al. Profiler'ın kendi ek yükünü not et.

**Test adımları:**
1. Uygulama ön plandayken **50 tur** yap: `Türkçe ses testi` başlat → bitmesini bekle → MediaEngine veya test müziğinde geçici kısılma/geri dönüşü doğrula → **2 sn** bekle. Ayrıca her **10 turda** bir konuşma sürerken `onPause` (uygulamayı arka plana al) ve `onResume` dönüşünü test et.
2. **10 kez** ekran yönünü değiştir; varsa etkin rota/TTS durumunu korurken eski Activity nesnelerinin tutulmadığını izle. TTS motorunu Activity her durakladığında `shutdown()` etme: `onPause()` için `stop() + releaseFocus`, ViewModel `onCleared()` / Android Auto `onDestroy()` için `stop() + shutdown() + releaseFocus` beklenir.
3. **10., 20., 30., 40. ve 50. turda** ses tamamlandıktan sonra **30 sn** boşta bekle, GC tetikle ve `Java heap`, `Native heap`, `PSS/Private Dirty`, `TextToSpeech`, `Activity`, `AudioDuckingManager` retained-instance sayılarını kaydet. 50 tur sonunda ek olarak **2 dk** boşta bekle, GC ardından B50 ölç.
4. Navigasyon ekranını kapatıp ilgili Activity/Screen'i yok et; heap dump'ta **eski Activity/Screen = 0 retained instance**, kapatılmış `TextToSpeech` ve eski ses odağı yöneticisi = **0** olmalı. Güncel yaşayan Activity ve tek TTS sahibi sızıntı sayılmaz.


**Tekrarlanabilir ölçüm / kanıt:**
- Aynı TTS motor sürümü, ses paketi, cihaz/Android sürümü ve test rotasıyla çalış. Her örneklemede **30 saniye boşta + iki GC isteği + 10 saniye ölçüm kararlılığı** uygula; GC'yi sızıntıyı onaran bir işlem gibi değil, karşılaştırılabilir snapshot için kullan. B0, B10, B20, B30, B40, B50 ölçümlerinde zaman damgası ve ‘Profiler attached’ bilgisini sakla.
- Her snapshot için **Java/Kotlin heap (MiB), Native heap (MiB), PSS (MiB)** ölçümlerini aynı araçla al; karşılaştırma B50 eksi B0'dır. PSS süreç genelidir; OS önbelleği ve cihazdaki başka uygulamalar ayrı not edilir. 3 koşuyu birbirinden bağımsız uygulama yeniden başlatmasıyla tekrarla; tek koşunun negatif farkı diğerindeki tutulan Activity'yi gizlemez.
- Heap dump'ta önceki nesilleri, tip adı ve retained path ile say: kapatılan Activity, onun ViewModel'i, önceki NativeVoiceGuide, AudioDuckingManager ve TextToSpeech referansları. Yalnız hâlâ yaşayan mevcut Activity/TTS nesnesi sızıntı sayılmaz. OnPause sonrası aynı ViewModel'in korunması normaldir; onCleared/onDestroy sonrası beklenmeyen referans FAIL'dir.
- AudioFocus kabul/ret, her utterance onStart/onDone/onError/onStop ve release/close zaman damgasını test build logunda ilişkilendir. **Konuşma bitti veya iptal edildi → ≤ 1 sn focus release** kuralını 50/50 olayda doğrula; üçüncü taraf medya ducking davranışı ayrıca donanım UAT'sidir.
- B0/B50 bellek farklarına ek olarak B10→B50 noktalarının **4 ardışık aralığının hepsinde artış** varsa, büyüme sınırın altında olsa bile leak incelemesine sevk et; ancak retained-object kanıtı olmadan tek GC artışını kesin leak olarak sınıflandırma.
**Beklenen sonuç / PASS (QA bütçesi):** B50–B0 **Java heap ≤ +10 MiB**, **Native heap ≤ +15 MiB**, **toplam PSS ≤ +25 MiB**; 10→50. tur örneklerinde devamlı ve belirgin yükselme eğilimi yok (beş nokta üst üste artıyorsa **FAIL/inceleme**); süreç boyunca **0 crash / ANR**; biten/durdurulan her TTS sonrası `AudioFocus` en geç **1 sn** içinde serbest ve eski medya ses seviyesi kullanıcı müdahalesi olmadan geri dönmeli. Fiziksel Android'de AudioFocus `abandon` gözlenemiyorsa kabul **BLOCKED**.

**Başarısızlık durumunda:** `MEM_JAVA`, `MEM_NATIVE`, `MEM_PSS`, `RETAINED_ACTIVITY`, `TTS_SHUTDOWN`, `FOCUS_STUCK` koduyla heap dump + allocation timeline + `dumpsys meminfo` + logcat ekle. `Handler` callback, `UtteranceProgressListener`, context/Activity referansı, yinelenen TTS örneği ve geç `AudioFocus` callback zincirini incele; düzeltmeden sonra B0'ı yeniden oluşturup 50 turu tekrar koş. Tekil GC dalgalanmasını kanıtlanmış leak olarak raporlama.

### QA-EV-OBD — Enerji tahmini / ölçülmüş tüketim karşılaştırması

**Ön koşul:** `EvBaselineEnergyEstimator` tahmini ile kıyaslanacak **bağımsız doğrulanmış** kWh tüketim ölçümü zorunlu. BMS toplam enerji sayaçlarının birimi/ölçeği belgelenmeli veya **doğrulanmış HV güç (kW)** değerleri zaman üzerinden (çekiş + HVAC + rejenerasyon işareti doğru) entegre edilmeli. Yalnız ekran SoC yüzdesi, denenmiş ham PID veya `candidateBmsSocPercent` **kWh ölçümü sayılmaz**. Projede güvenilir ölçüm kanalı yoksa bu UAT **BLOCKED**; modelin doğru olduğu iddia edilmez.

**Test adımları:**
1. Önceden kalibre edilmemiş, **en az 5 ayrı sürüş** belirle; her biri **≥ 30 km** ve ölçülmüş enerji **≥ 2 kWh**. Mümkünse şehir/otoyol, klima açık/kapalı, soğuk/ılık koşulları kapsa; güvenlik kuralları ve sürüş hız limitleri dışına çıkma.
2. Kalkıştan **önce** gerçek OSRM rota ID/mesafesi, model sürümü, kalibre edilmiş kullanılabilir batarya kapasitesi (kWh), referans Wh/km, başlangıç SoC **kaynağı** (`FreshObd` / `ExplicitAssumption`), hedef rezerv, öngörülen hız ve sıcaklık ile klima durumunu kaydet. Tahmini kWh'ı **önceden dondur**; gerçek sürüş verisiyle geriye dönük değiştirme.
3. OBD enerji ölçümünü **≥ 1 Hz** örnekle; zaman damgası, ölçüm birimi, işaret ve geçerliliği sakla. **Geçerli örnek kapsaması ≥ %95**, arada **> 5 sn** boşluk yoksa koşu geçerli. OBD kopan, yanlış ölçekli veya geçersiz verili koşuyu **INVALID**, gerçek ölçümü olmayan koşuyu **BLOCKED** olarak ayır.
4. Varışta aynı enerji ölçümünden `E_ölçüm_kWh` hesapla; `E_tahmin_kWh` ile eşleştir. **Sapma (%) = 100 × (E_tahmin − E_ölçüm) / E_ölçüm**; mutlak sapma `|sapma|`. Tüm koşularda ağırlıklı toplam sapma `100 × |ΣE_tahmin − ΣE_ölçüm| / ΣE_ölçüm` olarak ayrıca yaz.
5. Sonuçları `run_id,date,commit,route_id,route_km,actual_km,start_soc_pct,soc_source,battery_usable_kwh,reference_wh_km,model_speed_kmh,actual_mean_speed_kmh,temp_c,climate_on,speed_multiplier,climate_multiplier,predicted_kwh,measured_kwh,signed_error_pct,absolute_error_pct,sample_coverage_pct,max_gap_s,measurement_source,result` sütunlarıyla **CSV** dosyasına kaydet. Ham zaman serilerini ayrıca iliştir; kişisel konum izlerini paylaşmadan önce maskele.


**Ölçüm hesabı / karşılaştırılabilirlik:**
- Sürüşe başlamadan önce aynı rota için tek bir sabit tahmin üret; başlangıç ve varış noktalarını kilitle. **Gerçek sürüş kilometresi ile model rota kilometresi farkı > %3** ise güzergâh eşleşmemiş say ve koşuyu INVALID olarak yeniden yap (başka güzergâhı model hatası olarak puanlama). Rota sapmaları ve mediaengine/şarj başlangıç-bitiş zamanlarını kaydet.
- Net yüksek voltaj batarya enerjisi ölçümü için doğrulanmış **P[kW]** zaman serisini işaret konvansiyonuyla kullan. P>0 bataryadan çıkan, P<0 rejenerasyonla geri kazanılan güç olacak şekilde **E_ölçüm[kWh] = Σ((P_i + P_{i+1})/2 × Δt_i[s]/3600)** (trapez integrali). Ham P değerleri *W* ise önce 1000'e böl; 1 Hz örnekleme yeterliliği ve 5 saniyeden uzun gap olmamasını doğrula. Net ölçüm ≤ 0 veya hesap/birim işareti belirsizse INVALID.
- Kıyaslanan enerji sınırı aynı olmalı: tahminin kapsamadığı şarj enerjisi, farklı sürüş mesafesi, ölçüm dışı rölanti/HVAC, harici ön koşullandırma ayrı not edilmeli. Tek nokta SoC farkından gerçek tüketim kWh hesabı yapma.
- **Birincil karar metriği:** her geçerli sürüş için mutlak yüzde hata ≤ %15. **Toplam karar:** ağırlıklı net bias = 100 × |Σ(E_tahmin−E_ölçüm)| / ΣE_ölçüm ≤ %10. Ek olarak hata iptalini göstermek için **WAPE = 100 × Σ|E_tahmin−E_ölçüm| / ΣE_ölçüm** değerini raporla; WAPE tek başına eski bias kriterinin yerine geçmez.
- CSV'nin her satırına doğrulanmış ölçüm kaynağı (ECU/PID sürümü, birim, işaret), veri kapsaması, zaman senkronizasyonu ve 5 sürüş sonunda özet bias/WAPE ekle. Örnek satır uydurma; kullanılan ham kW/saat örneklerini anonimleştirilmiş ayrı CSV ile iliştir.
**Beklenen sonuç / PASS (ilk kalibrasyon hedefi):** Her **geçerli** sürüşte `|sapma| ≤ %15`; en az 5 sürüşün toplam enerji ağırlıklı sapması **≤ %10**; ölçüm kapsaması **≥ %95**; tahmin `MODEL / TAHMİN` diye etiketli; bayat OBD otomatik reddediliyor. Bu sınırlar proje kalibrasyon hedefidir; **ABRP düzeyinde doğruluk garantisi değildir**. 5 geçerli sürüş tamamlanmazsa **BLOCKED**, geçerli sürüşlerden biri limit dışındaysa **FAIL**.

**Başarısızlık durumunda:** `ENERGY_BIAS`, `ENERGY_TRIP`, `OBD_UNVERIFIED`, `SAMPLE_GAP`, `MODEL_SOURCE` kodunu kaydet. Wh/km birim dönüşümlerini, rejenerasyon işaretini, batarya kapasitesi varsayımını, hızın tek anlık mı yoksa rota ortalaması mı olduğunu, HVAC/sıcaklık ve rota uzunluğu farklarını denetle. Kalibrasyon katsayılarını sadece ayrı **eğitim sürüşlerinde** ayarla; bu 5 doğrulama sürüşüne göre ayarlayıp aynı veriye PASS verme. Düzeltmeyi farklı 5 sürüşle yeniden test et.

### QA-OBD-10X — 10 ardışık bağlantı / kopma döngüsü

**Ön koşul:** Araç güvenli biçimde **park halinde**, test cihazı daha önce eşleştirilmiş, adaptör kullanıcıya ait. Kontağı defalarca aç/kapatmak yerine kontrollü test için adaptör gücünü kesmek veya erişim dışına çıkarmak tercih edilir; aracın ve elektronik sistemlerin güvenliği önceliklidir. SPP/BLE adaptör türünü, cihaz Android sürümünü, ECU/protokolü ve poll aralığını kaydet.

**Test adımları:**
1. Başlangıç durumu `ObdPhase.DISCONNECTED` olmalı; bağlı soket/GATT kalmamalı. Adaptöre bağlan, `CONNECTING → CONNECTED` geçişini ve `ATZ → ATE0 → ATL0` sırasını doğrula; **en az bir** salt-okunur `010D` sorgusunun yanıtını kaydet.
2. **10 kez**, her birinde: bağlantıyı kur → **30 sn** stabil okuma → kontrollü olarak adaptörün güç/bağlantısını kes → yeni `010D` okuması dene → `ERROR` veya güvenli `DISCONNECTED` durumunu ve hata metnini gözle → adaptörü geri aç → uygulamanın **açık kullanıcı komutuyla** yeniden bağlan. Hata halinde arka planda sonsuz reconnect döngüsü beklenmez.
3. Her turda süreleri ölç: `t_connect` = kullanıcı bağlan komutundan `CONNECTED` durumuna; `t_loss` = ilk başarısız okumadan `ERROR/DISCONNECTED` mesajına. Ayrıca adapter oturum sayısı, son ham yanıt zaman damgası, aynı anda açık soket/GATT ve `ObdConnectionState.error` alanını kaydet.
4. 10. tur sonunda bir normal bağlantı daha dene, bağlantıyı **manuel kes**; uygulamayı arka plana al ve yeniden aç. Bağlantı kapalı kalmalı, kaybolmuş veya gecikmiş eski yanıt yeniden `CONNECTED` durumuna döndürmemeli.


**Tekrarlanabilir ölçüm / durum matrisi:**
- Her turda aynı deney sırasını uygula: kullanıcı bağlantı isteği (T0) → UI CONNECTING → ELM init tamamlandı → ilk geçerli salt-okuma → 30 saniye stabil → adaptör kontrollü kesildi (T1) → ilk başarısız okuma (T2) → UI ERROR veya DISCONNECTED + açıklama (T3) → kullanıcı reconnect (T4) → CONNECTED. Bu 10 geçişin tamamını tur numarasıyla zaman damgalı kaydet.
- SPP adaptör için Bluetooth uzaklaşma/güç kesilmesi; BLE adaptör için GATT kopması ayrı test kategorileridir. Mevcut cihaz yalnız SPP destekliyorsa BLE testine **BLOCKED** ver; Bluetooth Classic başarısını BLE başarısı gibi sayma. Test sırasında araç güvenli biçimde parkta kalsın.
- **Beklenen durum dizisi:** DISCONNECTED → CONNECTING → CONNECTED → (kopmada) ERROR/DISCONNECTED; kopma sonrasında en fazla 3 otomatik okuma denemesi; kullanıcı açıkça yeniden bağlantı seçmedikçe tekrar CONNECTED görünmemeli. Her turda error mesajı, bağlantı/serbest bırakılan soket/GATT sayısı, ham yanıtın son timestamp'i ve TTL 10 saniye sonra gösterilen SoC/hız alanı ekran görüntüsü alın.
- Ölçümler: T0→CONNECTED **≤ 30 sn**, T2→T3 **≤ 45 sn**, 30 saniyelik stabil kısımda **en az bir geçerli gerçek salt-okuma**; aynı anda canlı oturum sayısı **0 veya 1**, test sonunda **0**. Daha eski kapanmış oturumdan gelen callback yeni oturumu veya UI verisini değiştirmemeli. Ekran kaydı ve logcat saatini aynı referans saate senkronize et.
- Her döngüye `cycle,transport,device_model,t0_connect,connected_at,loss_at,first_failed_read_at,error_visible_at,retries,live_sockets,last_valid_read_at,ui_phase,ui_error,ttl_expired,result` kayıt alanlarını ekle. 10 turun birinde UI veya TTL şartı bozulursa FAIL; sadece cihazın yeniden bağlanmayı desteklememesi test ön koşuluyla belgelenmişse BLOCKED.
**Beklenen sonuç / PASS (cihaz QA hedefi):** **10/10** turda crash/ANR yok; her başlangıç bağlantısı **≤ 30 sn**; tek okuma hatasından itibaren politika gereği en çok **3 okuma denemesi**, `ERROR` veya `DISCONNECTED` UI mesajı **≤ 45 sn**; eş zamanlı **en fazla 1** canlı socket/GATT oturumu; ölçüm verisi mevcut `TimedValue.current(now, 10_000)` TTL'si dolduğunda **canlı gibi görünmemeli**; bağ kopması ve reconnect esnasında kilitlenme/yanlış ECU yanıtı yok; son manuel `disconnect` sonrası bağlantı açık kalmaz. Hata metninin UI'da görünmesi zorunlu; yalnızca logcat'te hata olması **FAIL**.

**Başarısızlık durumunda:** `OBD_TIMEOUT`, `OBD_RECONNECT`, `OBD_STATE`, `OBD_STALE`, `OBD_LEAK`, `OBD_CRASH` koduyla tur numarası, SPP/BLE türü, Bluetooth HCI kaydı (mümkün ve hassas veriler maskeli ise), uygulama logcat, durumu gösteren ekran kaydı ve bağlantı sayacı ekle. `Mutex` kilitleri, `BluetoothSocket.close()`/GATT `close()`, eski callback'lerin yeni oturumu etkilemesi ve üç deneme sınırını incele. Sorunu düzelttikten sonra **10 turun tümünü yeniden** koş.

### QA-POWER-15 — %15 telefon pili / güç tasarrufu / GPS ve TTS

**Ön koşul:** Telefon bataryası **%14–16**, cihazın yerleşik **güç tasarrufu etkin**, hassas konum izni verilmiş, Türkçe TTS ses paketi hazır, EPDK veri kataloğunda **13.000+ geçerli** istasyon yüklenmiş olmalı. Android sürümü, batarya yüzdesi, güç tasarrufu tipi (normal/aşırı), ekran yenileme hızı (Hz), TTS motoru ve APK commit SHA'sını kaydet. Telefonun harici güç bağlantısını kes; cihaz aşırı ısınmışsa koşuyu INVALID yap. Karşılaştırma için aynı cihazda güç tasarrufu **kapalı** ve sabit rota/harita verisiyle ayrı bir kontrol koşusu kullan. Gerekli gerçek batarya seviyesi veya katalog yoksa **BLOCKED**; ADB ile sahte batarya yüzdesini gerçek pil kanıtı gibi sunma.

**Test adımları:**
1. Güç tasarrufu kapalıyken **5 dk** ön plan kontrol koşusu al: sabit viewport'ta 20 pan, **4 → 8 → 12 → 15 → 12 → 8 → 4** zoom dizisini 5 kez uygula, **10 tekil marker / 10 cluster** tıklaması yap. FrameTimeline p95/jank, tıklama gecikmeleri ve GPS fix zaman damgalarını kaydet. Aynı rotada Türkçe ses testini **5 kez** çalıştır.
2. Gerçek pil **%14–16** aralığındayken güç tasarrufunu aç; uygulamayı yeniden öne getir, **2 dk** kararlı durumda bekle. Aynı veri/viewport/gesto sırasını üç kez tekrarla. Android Studio **System Trace / Perfetto** ile frame sürelerini ve olay zamanlarını, `logcat` ile `ANR`, `FATAL EXCEPTION`, `OutOfMemoryError` sonuçlarını kaydet.
3. Açık gökyüzü altında uygulama **10 dk ön planda** açıkken gerçek `FusedLocationProviderClient` fix'lerini kaydet: `recordedAtMillis`, doğruluk metre, ardışık fix aralığı ve `GpsFix.usable(now)` durumu. Mevcut `AndroidLocationRepository` **1.000 ms** istek aralığı / **500 ms** minimum / **2 m** minimum hareket ister; bu Android tarafından **garanti edilen GPS frekansı değildir**. Hareketsizken `2 m` filtresi yüzünden fix gelmemesini tek başına sistem hatası olarak değerlendirme; kontrollü hareketli güzergâhta yolcu gözlemciyle veya güvenli yaya testinde ölç.
4. Ön planda **10 kez** `Türkçe ses testi` çalıştır (her birinin bitmesini bekle). Sesin duyulduğunu, `AudioFocus` alınıp bırakıldığını ve her konuşmadan sonra başka uygulamanın izin verilen ses medyasının geri döndüğünü kaydet. **200 m / 50 m** manevralarını yalnız gerçek rota ve güvenilir GPS ile, sürücü olmayan bir gözlemciyle ayrıca doğrula; konum mock'lama veya araç kullanırken ekrana dokunma gerektirme.
5. Bir anons başlarken uygulamayı arka plana al, ekranı kilitle ve **60 sn** bekle; ardından geri aç. Mevcut mimaride `MainActivity.onPause()` TTS'i durdurur ve GPS akışını kapatır; **arka planda kesintisiz navigasyon beklenmez**. Ön plana dönünce GPS izninin sürdüğünü, yeni ve kullanılabilir fix alındığını ve eski manevranın yanlışlıkla tekrar okunmadığını kontrol et. Testi **3 kez** yinele.

**Beklenen sonuç / PASS (proje içi cihaz kabul hedefi):** Ön planda **0 crash / 0 ANR / 0 OOM**, 10/10 TTS denemesinde Türkçe ses (motor ve AudioFocus hazırsa), anons tamamlandıktan/durdurulduktan **≤ 1 sn** sonra ses odağı bırakılmış; **10 dk hareketli açık alan GPS testinde** ardışık fix aralıklarının **p95 ≤ 10 sn**, **maksimum ≤ 20 sn** olması ve geçersiz/bayat GPS ile **0 yanlış manevra anonsu**. Güç tasarrufu modunun Android tarafından frekansı düşürebileceği kabul edilir; aralık limiti aşılırsa koşul **FAIL**, konum izni veya gerçek GPS koşulu yoksa **BLOCKED**. Harita tıklaması **p95 ≤ 1.000 ms**, pan/zoom ilk görsel tepki **p95 ≤ 500 ms**, 60 Hz devam ediyorsa kare p95 **≤ 50 ms**; cihaz tasarruf nedeniyle 30 Hz'e geçiyorsa kare p95 **≤ 66,7 ms** (ölçülen ekran Hz'sini kaydet); cluster/marker veri eşleşmesi **%100**. Arka plana geçişte TTS/GPS'in durması **beklenen**, eski konumun canlıymış gibi gösterilmesi veya ses odağının tutulması **FAIL**. Bu eşikler Android'in garanti ettiği süreler değil, QA kabul hedefidir.

**FAIL/BLOCKED durumunda kaydedilecek kanıt:** `POWER_TTS`, `POWER_FOCUS`, `POWER_GPS_GAP`, `POWER_MAP`, `POWER_BACKGROUND`, `POWER_DATA` kodu, pil seviyesi ve güç tasarruf ekran görüntüsü, `dumpsys battery`, `dumpsys power`, GPS fix aralıkları CSV'si, `Perfetto` trace, `logcat`, TTS olay zamanları, marker ID eşleşme dosyası ve ekran kaydı. Katalog eksikliği / desteklenmeyen test ön koşulunda **BLOCKED**; aşırı ısınma ya da ölçüm kesintisinde **INVALID**. Güç tasarrufu politikasını Android'in sistem davranışından ayır; donanım kısıtını uygulama hatası gibi varsaymadan kanıtla ve aynı koşuyu yeniden ölç.

### QA-LOCALE-EN — İngilizce cihaz dili / sabit Türkçe TTS ve OSRM manevrası

**Ön koşul:** Android sistem dilini **English (United States) / en-US** yap; uygulamayı tamamen kapatıp yeniden aç. Karşılaştırma için önce **Türkçe (Türkiye) / tr-TR** cihaz dilinde aynı APK, aynı OSRM rota ve aynı Türkçe TTS motorunu kaydet. `TextToSpeech` motorunda **tr-TR** dil paketinin yüklü olup olmadığını ayrı doğrula. Uygulama içinde bağımsız dil seçme ayarı bulunmadığı sürece hedef, cihaz dili en-US iken de **Türkçe navigasyon ve mevcut Türkçe UI'nın tutarlı kalmasıdır**; otomatik İngilizceye geçiş beklenmez.

**Test adımları:**
1. tr-TR kontrol koşulunda **aynı başlangıç/varış koordinatlarıyla** OSRM rota oluştur; OSRM istek URL'si, HTTP yanıt kodu, `legs[].steps[].maneuver.type/modifier/location`, `step.name` alanları, `NavigationManeuver.instructionTr` metni ve ses çıktısını kaydet. Gerçek OSRM servisi erişilemiyorsa sabit ve kaynağı belgelenmiş JSON fixture ile **parser testi** yapılabilir; ancak canlı ağ UAT'si ayrıca **BLOCKED** kalır.
2. Cihaz dilini **en-US** yapıp uygulamayı yeniden başlat. `NativeVoiceGuide` içinde `Locale("tr", "TR")` / `isLanguageAvailable(tr-TR)` / `setLanguage(tr-TR)` yolunu kontrol et. `Türkçe ses testi`ni **10 kez** başlat; Türkçe telaffuz ile okunmasını ve `Locale.getDefault()` değiştiğinde motorun sessizce `en-US` sesine düşmemesini doğrula.
3. Aynı gerçek başlangıç/varış çifti için OSRM sorgusunu yeniden oluştur. Mevcut `OsrmRequestFormat` URL'sinde `steps=true` bulunmalı; standart OSRM Route API'sinde **`language`/Türkçe manevra metni parametresi yoktur**. URL'ye `language=tr`, `language=en` vb. **uydurma parametre ekleme**. Dil mantığını OSRM JSON manevra kodlarının `OsrmTurkishInstructions.speak(type, modifier, name)` ile Türkçe talimata dönüştürülmesinde doğrula.
4. En az **10 bilinen manevra fixture'ını** (`left/right/straight`, `slight left/right`, `roundabout`, `merge`, `on/off ramp`; mevcut parser'ın desteklediği kombinasyonlar) tr-TR ve en-US cihaz dilinde karşılaştır: üretilen `instructionTr` içeriği aynı olmalı, bilinmeyen `type` için sahte yönlendirme üretilmemeli. Canlı rota segmentinin farklılaşması durumunda aynı fixture'ı kullanıp ağ verisi ile lokalizasyon hatasını ayır.
5. Kokpitte **en az 20 sabit UI metnini** (Rota, Şarj, Medya, Ses, Navigasyon, manevra, hata mesajları) tr-TR ve en-US ekran kayıtlarında karşılaştır. Projede metinlerin büyük bölümü Kotlin içinde Türkçe sabit olduğu için **tek tip Türkçe UI** beklenir; sistem izni penceresi ve OSRM'den gelen özel yol adları cihaz/harita diliyle değişebilir, bunları uygulamanın UI yerelleştirme hatası sayma. Türkçe karakter bozulması, Türkçe/İngilizce karışık **uygulama içi sabit etiket** veya kesilen buton metni olmamalı.
6. TTS ayarlarından **Türkçe ses paketini devre dışı bırakılmış/erişilemez** olduğu güvenle test edilebilen ayrı koşul oluştur: motor `TURKISH_VOICE_MISSING` durumunu göstermeli, `en-US` sesiyle Türkçe manevra okumaya çalışmamalı. İşletim sistemi veya TTS motoru dil paketini seçmeli olarak devre dışı bırakmaya izin vermiyorsa bu **negatif test BLOCKED**; ana en-US/tr-TR senaryosunu geçersiz kılmaz.

**Beklenen sonuç / PASS:** tr-TR ve en-US karşılaştırmalarında **10/10** ses testi Türkçe (ses paketi hazırsa); hiçbir anons cihaz varsayılan `en-US` sesine sessizce dönmez; desteklenen **10/10** manevra fixture'ı her iki cihaz dilinde **aynı Türkçe talimatı** üretir; OSRM isteklerinde `steps=true` ve geçerli `longitude,latitude` sırası korunur, desteklenmeyen **0 dil parametresi** gönderilir; **20/20 uygulama metni** tutarlı ve okunaklı; **0 crash / ANR**. Türkçe ses yoksa `TURKISH_VOICE_MISSING` görünür ve İngilizce varsayılan sesle sahte PASS verilmez. Bu senaryo uygulamanın en-US yerelleştirme desteği sunduğunu iddia etmez.

**FAIL/BLOCKED durumunda kaydedilecek kanıt:** `LOCALE_TTS_FALLBACK`, `LOCALE_VOICE_MISSING`, `LOCALE_OSRM_REQUEST`, `LOCALE_MANEUVER`, `LOCALE_UI_MIX`, `LOCALE_TEXT_OVERFLOW` kodları; sistem dil ekran görüntüsü, TTS motor/dil paketi sürümü, uygulama yeniden başlatma zamanları, URL (gizli anahtar ve hassas koordinatları maskeleyerek), OSRM yanıt JSON'u veya fixture SHA-256, `instructionTr` karşılaştırma CSV'si, 20 etiket için ekran görüntüleri ve ses kayıtları. Canlı OSRM sunucusu erişilemiyorsa **canlı ağ testi BLOCKED**; fixture sonucu ayrı raporlanmalı. Türkçe ses yüklü olduğu halde `en-US` fallback gerçekleşirse **FAIL**, sadece devre dışı bırakma testi mümkün değilse negatif koşul **BLOCKED**. Hata düzeltildikten sonra aynı 10 ses/10 manevra/20 UI karşılaştırmasını tekrar koş.

### Sonuç kaydı şablonu

Her QA protokolünde şu tek satır biçimini kullan:
`test_id | commit | cihaz/Android | tekrar sayısı | kaynak/veri sürümü | ölçülen değer(ler) | PASS/FAIL/BLOCKED/INVALID | kanıt yolu | issue no | yeniden test tarihi`.

- **PASS:** tüm zorunlu kanıtlar mevcut ve bütün eşikler karşılanmış.
- **FAIL:** kanıt var ve en az bir kabul eşiği ihlal edilmiş.
- **BLOCKED:** gerekli gerçek EPDK/OBD/PiP koşulu ya da ölçüm altyapısı yok; sonuç geçerli biçimde sınanamıyor.
- **INVALID:** örnek kaybı, termal throttling veya bozuk ölçüm nedeniyle koşu karşılaştırılabilir değil; tekrar alınmalı.
- Release kararı için kritik `ANR`, bellek sızıntısı, OBD yanlış canlı veri ve enerji kaynağı sahteciliği **0 açık hata** koşulu geçerlidir.

### ADB gözlem komutları (Windows PowerShell)
```powershell
$adb = "C:\Users\Alp\Desktop\platform-tools\adb.exe"
& $adb logcat -c
& $adb logcat -v time | Select-String "FATAL EXCEPTION|ANR|AudioFocus|TextToSpeech|Bluetooth|MapLibre"
```

### UAT kabul ölçütleri
- Harita `QA-MAP-13K`: 13.000+ geçerli kaynak, 3 koşuda 0 ANR/OOM; 60 Hz'de p95 kare ≤ 33,3 ms, jank ≤ %10; işaret/küme gecikme eşikleri sağlanmalı.
- TTS/AudioFocus `QA-MEM-TTS`: 50 tur sonrası GC ölçümünde Java ≤ +10 MiB, Native ≤ +15 MiB, PSS ≤ +25 MiB; önceki Activity/TTS referansları tutulmamalı ve ses odağı bırakılmalı.
- OBD `QA-OBD-10X`: 10/10 bağlan-kop döngüsünde durum/hata UI'a iletilmeli; soket sızıntısı ve 10 sn TTL'yi aşan canlı değer bulunmamalı.
- Enerji `QA-EV-OBD`: 5 geçerli ve her biri ≥ 30 km ölçümlü sürüşte her bir mutlak kWh sapması ≤ %15, toplam enerji ağırlıklı sapma ≤ %10 olmalı; doğrulanmış OBD enerji ölçümü yoksa BLOCKED.
- Düşük pil `QA-POWER-15`: gerçek %14–16 pil ve güç tasarrufunda 10/10 Türkçe TTS (hazır ses motoru), 0 yanlış/bayat GPS manevrası; GPS ve harita gecikme sınırları ölçülmüş olmalı.
- Dil `QA-LOCALE-EN`: en-US cihazda TTS tr-TR kalmalı; 10/10 Türkçe manevra fixture'ı ve 20/20 sabit UI etiketi tutarlı; standart OSRM isteğine desteklenmeyen dil parametresi eklenmemeli.
- Harici medya + PiP için olmayan yeteneklerin 'çalışıyor' olarak sunulmaması.
- JUnit + Gradle assembleDebug başarılı olsa bile donanım UAT tamamlanmadan production-ready ilan edilmez.

## Ek saha UAT protokolleri — TTS / WebView DRM / Harita katmanları

**Kanıt zorunluluğu:** Her koşu için commit SHA, APK sürümü, cihaz/Android/WebView/TTS sürümleri, test tarihi, EPDK katalog tarihi, ağ, konum izni, Park doğrulama kaynağı ve ekran/log kanıtı kaydedilir. Fiziksel cihaz testi çalıştırılmadıkça PASS verilmez; koşul sağlanmıyorsa **BLOCKED**.

### QA-TTS-PACK — TTS Paket Kontrolü

- [ ] Android **Ayarlar > Dil ve Giriş > Metin Okuma** altında Türkçe konuşma veri paketini kontrol et (telefon modeline göre menü adı değişebilir).
- [ ] Ses ekranından **Türkçe ses testi**ni 10 kez başlat; duyulan metin Türkçe olmalı. Ses motoru/cihaz ses çıkışı ayrıca kontrol edilmeli.
- [ ] Logcat: `EYA-TTS-UAT` satırlarında `LANG_AVAILABLE` veya `LANG_COUNTRY_AVAILABLE`; hata koşulunda `LANG_MISSING_DATA` / `LANG_NOT_SUPPORTED`; `setLanguage` sonucunu kaydet.
- [ ] Türkçe ses paketi yoksa kullanıcıda **Türkçe TTS Paketi Eksik** dialogu açılmalı. Motor tr-TR yerine sessizce en-US ses kullanmamalı; paketi devre dışı bırakamıyorsan negatif testi BLOCKED kaydet.
- [ ] `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` konuşmada alınmalı; ses tamamlanınca, hata veya uygulama arka planında bırakılmalı. Harici oynatıcının ne kadar kısıldığı OS tarafından belirlenir.
- [ ] Gerçek OSRM `legs[].steps` manevralarında 200 m / 50 m uyarılarının iki ayrı aşamada yalnız birer defa duyulduğunu güvenli koşullarda **yolcu gözlemcisi** doğrulasın. Güncel GPS yoksa BLOCKED.
- [ ] FAIL halinde zaman damgası, motor sonucu, focus durumu ve Logcat ilişkisini kaydet. Hassas GPS koordinatı/ses kaydını paylaşırken maskele.

### QA-WEBVIEW-DRM — WebView / Resmî uygulama / PiP

- [ ] Ön koşul: güvenilir Park sinyali. Native kokpit halen `verifiedParked=false` verdiği için mevcut sürümde WebView uçtan uca testi **BLOCKED**; sahte Park sinyali ekleme.
- [ ] Park doğrulanabildiğinde YouTube `https://m.youtube.com` aç; `EYA-MEDIA-UAT` altında `pageStarted` ve `pageFinished` veya HTTP/SSL hata kaydını al.
- [ ] İzinli test videosunda oynatma/ses/codec ve tam ekrana gir-çık kontrolü yap; `WebChromeClient.onShowCustomView` / `onHideCustomView` Logcat, geri tuşu ve bellekte bırakılmış WebView olup olmadığını incele.
- [ ] Netflix/Disney+/Prime düğmelerinde DRM uyarısı ve harici uygulamayı **kullanıcı onayıyla** açma davranışını doğrula. Resmî paket yüklü değilse anlamlı hata gösterilmeli.
- [ ] Harici uygulama PiP destekliyorsa onun kendi PiP davranışını test et; bizim uygulamanın başka Activity'yi zorla PiP'e alması **beklenmez**.
- [ ] Harici geçişte `MainActivity.onPause()` mevcut GPS/TTS/OBD yaşam döngüsünü durdurur; arka planda kesintisiz navigasyon bekleme. DRM siyah ekranını uygulama çökmesi olarak sınıflandırma.
- [ ] FAIL kanıtı: cihaz/WebView sürümü, EYA-MEDIA-UAT satırları, ekran kaydı, izin/oturum/codec hata kodu. Hesap ve çerezleri maskele.

### QA-MAP-LAYERS — Harita Katman Sıralaması ve DC 5 km

- [ ] Geçerli OSRM `LineString` ve `eya-route-line` mevcut mu? Rota çizilmeden görüntü testi BLOCKED.
- [ ] MapLibre kaynakları: `eya-weather-route`, `eya-osrm-speed-profile`, `eya-station-source`; katmanları: `eya-weather-markers`, `eya-osrm-speed-normal`, `eya-osrm-speed-slow`, `eya-station-route-outline`.
- [ ] `style.addLayerAbove(weatherLayer, ROUTE_LAYER)` sonrasında sıcaklık + durum ikonu rota çizgisi üstünde görünmeli; 100 km aralıklı örnekleme ve tam varış koordinatını karşılaştır.
- [ ] Kırmızı/yavaş ve yeşil/normal hız çizgilerini farklı zoomlarda görsel denetle. `annotations=speed` yalnız **OSRM profil hızı**; canlı trafik sonucu sayma.
- [ ] `EYA-MAP-UAT` içindeki `corridorDc5km`, rota nokta sayısı ve katalog sayısını kaydet; EPDK DC soketli olup rotaya 5 km'den yakın istasyonlar turkuaz halka almalı, uzaktakiler almamalı.
- [ ] Clustering nedeniyle düşük zoomda halka kaybolabilir; yüksek zoomda ve farklı rota alternatiflerinde tekrar kontrol et. Stil yeniden yüklemesi sonrası `weatherLayer=true`, `speedLayers normal=true slow=true`, `dcRingLayer=true` bekle.
- [ ] Uygulama arka/öne geçişi, rota temizleme, harita kaydırma/zoom ve ağ hatalarında crash/ANR olmamalı.
- [ ] FAIL halinde `EYA-MAP-UAT` çıktısı, stil/zoom, kaynak verisi SHA, işaret ID ve ekran kaydını iliştir.

### QA-UI-DARK — Koyu GPS uyarısı

- [ ] Uygulamanın **kendi** uyarı diyaloğunda `CustomWarningDialog` seçilmişse renkler `#0A0F1A`, beyaz metin, neon yeşil `#00E676` ve geri/kapat davranışı doğrulanır.
- [ ] Android sisteminin konum izni diyaloğu başka bir pencereyse Compose temasıyla değiştirilemez. Ekran sahibini doğrula; sırf beyaz olduğu için uygulama UI hatası verme.
- [ ] Mevcut Tesla/ABRP koyu kokpit ve cam panel, MapLibre, `EvBaselineEnergyEstimator` ve özgün MediaEngine servisine regresyon olmamalı.

### QA Logcat ve sonuç kaydı (Windows PowerShell)

```powershell
$adb = "C:\\Users\\Alp\\Desktop\\platform-tools\\adb.exe"
& $adb logcat -c
& $adb logcat -v time -s EYA-TTS-UAT EYA-Voice EYA-MEDIA-UAT EYA-MAP-UAT AndroidRuntime
```

`test_id | commit | cihaz/Android | test girdisi | gözlenen | PASS/FAIL/BLOCKED/INVALID | log/ekran kanıtı | issue`
