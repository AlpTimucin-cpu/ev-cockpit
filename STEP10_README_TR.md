# EV Cockpit — Adım 10 · Android Auto gerçek navigasyon metadata

Önceki 0.9.0 üzerinde **yalnız `:nativecockpit`** modülüne uygulanan yama. Telefonun mevcut OSRM/Photon/MapLibre ekranını korur.

- Sürücü araç ekranında **Navigasyonu başlat** eylemine dokunduğunda, yalnız doğrulanmış OSRM rota ve ≤10 sn güncel ≤35 m GPS varsa `NavigationManager.navigationStarted()`.
- Aktif seyirde `NavigationManager.updateTrip()` gerçek rota, GPS izdüşümü, OSRM'nin trafiksiz ETA modeli, manevra ve kalan mesafeyi iletir.
- Her saniye host IPC göndermek yerine adım değişimi, mesafe değişikliği veya 10 sn eşiği izlenir.
- Sürücü **Navigasyonu bitir** dediğinde veya host `onStopNavigation()` ile ownership değiştirdiğinde seslendirme durur ve `navigationEnded()` çağrılır.
- 15 saniye kullanılabilir GPS gelmezse navigasyon oturumu biter, sahte ilerleme gösterilmez.
- `NativeVoiceGuide` geçici audio focus/ducking ile Türkçe ses verir. Telefonun Activity TTS'si araç navigasyonu aktifken susturulur.
- OSRM manevrasının orijinal `type/modifier` değerleri korunur; bilinmeyen manevraya uydurma yön işareti atanmaz.

## Henüz tamamlanmayanlar

**Bu bir Android Auto/DHU laboratuvar adımıdır, Play onayına veya araç üzerindeki canlı kullanıma hazır olduğu iddia edilmez.**

- `NavigationManagerCallback.onAutoDriveEnabled` test simülasyonu, kullanıcının sahte GPS istememesi nedeniyle gerçek seyir yoluna eklenmemiştir. Google Play navigasyon testi için ayrı ve açık etiketli QA simülasyonunun geliştirilmesi gerekir.
- Telefon arka plandayken kesintisiz bağımsız navigasyon için foreground service/notification henüz yoktur; Car Screen kapanınca navigasyon bitirilir.
- Araç OBD bağlantısı **halen yok**: mevcut `DisconnectedVehicleRepository` tüm SoC ve telemetri alanlarını `null` döndürür. OBD'nin çalıştığı iddia edilmez.
- Trafik, anlık ücretler, şerit bazlı map matching yok. ETA OSRM'nin trafiksiz hız modeli ve GPS rota yüzdesine dayanır.
- Fiziksel araç ve DHU testleri henüz yapılmadı. APK derleme/test durumu için GitHub Actions sonuçlarına bakınız.

## Derleme

```powershell
gradle :nativecockpit:testDebugUnitTest :nativecockpit:assembleDebug
```

GitHub Actions `build-nativecockpit.yml` yeni commit üzerinde otomatik koşar; `:app` ve `:evlite` değiştirilmez.
