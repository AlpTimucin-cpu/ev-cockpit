# EV Cockpit ↔ MediaEngine medya köprüsü (Android / WebView)

**Hedef:** `AlpTimucin-cpu/ID4-Media-Hub`, `feature/ev-cockpit`  
**Regresyon referansı:** `2b3c008` (mevcut EV köprü testlerine dokunmadan ilerle).  
**Durum:** GitHub CI + fiziksel telefon/araç doğrulaması tamamlanmadan **üretime hazır değildir**.

## Kullanıcı APK'sı ile teknik ayrım

Kullanıcı tarafından verilen `MediaEngine-3.2.apk` **veri olarak incelendi, çalıştırılmadı**.
- SHA-256: `2cf92adf8df595c97e8ac9b5c9e0d73f64e2e3ce8601756426773f14e50f3f47`
- Manifest: `versionName=3.2`, `versionCode=421`, package
  `me.app.fermataX.auto.mola32.debug`; resmi kaynak kodu veya imzalama anahtarı değildir.
- APK'da ExoPlayerEngineProvider ve arm64/armeabi-v7a/x86_64 için VLC
  yerel kütüphaneleri bulunur. APK'dan bunların kaynak proje ile sürüm,
  ABI ve imza uyumluluğu kanıtlanmaz.
- **Önemli izin farkı:** bu APK manifesti `RECORD_AUDIO`,
  `READ_MEDIA_VIDEO`, `READ_MEDIA_AUDIO` ve (Android 12 ve öncesi)
  `READ_EXTERNAL_STORAGE` ister. Ayrıca `MEDIA_PROJECTION`
  foreground service izni bulunur. Bu binary'yi mevcut, kısıtlı izinli
  EV Cockpit çekirdeğine **kopyalamak/yeniden paketlemek güvenlik kapsamını genişletir**.
  Bu nedenle binary içe aktarılmamıştır.
- MediaEngine 3.2'ye özgü olduğu iddia edilen “No Park Window” bayrakları veya
  `CarSensors` / park freni koşullarını atlatan herhangi bir mekanizma
  yeniden kullanılmamış veya etkinleştirilmemiştir.

## Gerçekte entegre edilen mimari

```text
navigator.html [MediaEngine sekmesi]
  │ JS: EVCOCKPITNativeBridge.receive('status',
  │     {command:'LAUNCH_MEDIAENGINE_32_NO_PARK'})
  ▼
evcockpit-navigator-adapter.js [sabit komut allowlist]
  │ window.MediaEngineNative.openMedia()
  ▼
EvCockpitNavigatorActivity.Bridge [@JavascriptInterface]
  │ UI thread, Intent(MainActivity.class)
  │ FLAG_ACTIVITY_REORDER_TO_FRONT, no finish()
  ▼
MediaEngine MainActivity [pinned GPL kaynaktaki mevcut TV/IPTV/Web/radyo]
  │ Orijinal MediaSession / MediaBrowserService / medya engine sahipliği
  │ Orijinal medya thread'i, ağ/çözücü/yayın yaşam döngüsü
  │      ╳  EV WebView'i ya da OBD executor'larına video motoru gömme
  ▼
MediaEngine Yol Arkadaşım dashboard kartı
  │ Intent(EvCockpitNavigatorActivity.class) + REORDER_TO_FRONT
  ▼
Aynı navigator WebView, aynı seçili rota, aynı SoC senaryosu
```

**Not:** `LAUNCH_MEDIAENGINE_32_NO_PARK` dizgesi geçmiş istemcilerle protokol
uyumluluğu için **yalnızca komut adı** olarak korunur; No-Park yetkisi
oluşturmaz. Komut yalnızca `openMedia()` yöntemine eşlenir. Parametre
ekleyen / bilinmeyen komutlar reddedilir. WebView, appassets HTTPS
origin'inde çalışır; MediaEngineNative Java nesnesi değiştirilmez.

## Java sorumlulukları

```java
@JavascriptInterface public void openMedia() {
    ui.post(() -> openMediaSafely());
}
private void openMediaSafely() {
    if (destroyed || isFinishing() || isDestroyed()) return;
    Intent intent = new Intent(this, me.aap.fermata.ui.activity.MainActivity.class);
    intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
    startActivity(intent);
    // finish() çağrılmaz, servis yeniden oluşturulmaz.
}
```

MediaEngine fragmentindeki dashboard kartı da navigatöre dönerken
`FLAG_ACTIVITY_REORDER_TO_FRONT` kullanır. Rota ve SoC state'i
eski Activity'de kalır. Android'in bellek nedeniyle Activity öldürmesi
durumunda state koruma garanti değildir; üretime çıkmadan SavedState/
kalıcı rota snapshot ve geri yükleme eklenmelidir.

## Thread / oynatıcı izolasyonu

- `EvCockpitNavigatorActivity`: OSRM rota (`jobs`), geocoding (`searchJobs`),
  istasyon (`stationJobs`), hava (`routeWeatherJobs` /
  `currentWeatherJobs`), trafik olayları (`incidentJobs`),
  varyant rota (`variantJobs`) birbirini bloke etmeyen executor'lar
  olarak kalır; dokunulmadı.
- `EvCockpitLiveTelemetry` bağlamı navigasyon Activity'sine aittir;
  `onPause()` arka planda OBD okumayı güvenli biçimde durdurur,
  `onResume()` yalnız önceden verilmiş izinle yeniden bağlanır.
  Medya oynatıcıdan aynı adaptöre paralel ELM327 isteği göndermeyin.
- MediaEngine'nın orijinal TV/Web/ExoPlayer/VLC/MediaSession servislerini
  **yerinde koruyun**; medya decoder'ını ayrı bir `navigator.html`
  WebView'inde başlatmayın. MediaSession/audio focus yönetimi mevcut
  MediaEngine medya katmanının sorumluluğudur.
- Arka planda IPTV sesinin devamı MediaEngine kaynak ve medya servisinin
  davranışına bağlıdır; mevcut köprü bunu garanti etmez. Android Auto
  üzerinde sürüşte **ses odaklı medya**, video ise yalnız platformun
  izin verdiği park/uygun yolcu ekranı modunda olmalıdır.

## Android Auto güvenlik sınırı

- Araca hareket/park sensörleri hakkında sahte bilgi gönderilmez.
- `CarSensors` hız veya el freni durumunu aldatacak bayraklar eklenmez.
- `CAR_LAUNCHER` veya video distraction-optimized olarak sahte kayıt yapılmaz.
- Android Auto'ya gerçek POI ekranı için mevcut `EvCockpitCarPoiService`
  ayrı kalır. Harita WebView'i doğrudan sürüşte video olarak projekte edilmez.
- Google platform gereksinimi: https://developer.android.com/training/cars
  ve https://developer.android.com/training/cars/platforms/android-auto .

## Güvenlik ve sürüm geçişi regresyon kapıları

1. `node media-engine/tests/test_live_map.js` — hava/enerji/SoC doğruluğu,
   yerel ihbar ve 5 km istasyon koridoru; kaynak değiştirilmedi.
2. `node media-engine/tests/test_evcockpit_native_bridge.js` —
   `AndroidBridge` örneğinin değişmemesi, kötü biçimli paket, eskimiş GPS/SoC,
   tam/alt küme EPDK ayrımı, yeni sabit medya komutu ve saldırgan komut reddi.
3. `node media-engine/tests/test_navigator.js` —
   kokpit MediaEngine sekmesi Android'e ulaşır; Activity finish/back kullanılmaz.
4. `python3 media-engine/tests/test_android_auto_manifest.py <patched-mediaengine>` —
   keşfedilebilir POI, media/template, bozulmamış AA ayrımı.
5. `patch_mediaengine.py` SHA sabitleme ve `git diff --exit-code`
   ile MediaEngine'nın TV/Web/VLC/ExoPlayer kaynaklarının değiştirilmediği kontrolü.
6. Final merged APK manifestindeki **permission diff**:
   kamera, mikrofon, genel depolama ve media projection izinleri
   `3.2` binary'den sessizce taşınmamalıdır. GitHub CI APK üretimi,
   telefon/Android Auto gerçek ortam testinin yerine geçmez.
7. Gerçek POCO F5 + EV Cockpit testlerinde: rota açık → MediaEngine medya → haritaya
   dön → aynı rota/geçerli SoC; yavaş IPTV/akış değişimi; ağ kesintisi;
   OBD ayrı adaptöre kilitlenmeden geri bağlanma; onPause/onResume;
   izin yok / Activity yok / ekran döndürme / sistemin Activity öldürmesi.

## Kaynak uyumluluğu / özgünlük

Mevcut derleme, kullanıcıdan alınan `MediaEngine-3.2.apk` dosyasını yeniden
imzalamıyor veya onun DEX / VLC ikililerini projeye eklemiyor.
`moris545454/araba-` GPL kaynak ağacının belirtilmiş commit'i
(`3f45ad3557a25822c2a3a9f152f32ef82f57e005`) üzerinden ayrı imzalı
EV Cockpit Preview uygulaması üretiliyor. Medya çekirdeğini yeni MediaEngine 3.2
sürümüne **gerçekten taşımak** için eşleşen, yeniden dağıtımı ve
uyumluluğu doğrulanmış kaynak kod ve bağımlılık grafı gerekir.

**Teslim kapsamı:** güvenli sürüş/medya geçişi + regresyon testleri.
**Teslim kapsamında olmayanlar:** No Park Window bypass, hareket hâlinde
görüntü kilidini kaldırma, doğrulanmamış MediaEngine 3.2 DEX/native kod ithali,
donanım ve Android Auto saha sertifikasyonu.
