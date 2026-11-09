# FermataX 2.2.2 (323) — Kullanıcı APK'sından doğrulanan bulgular

**Analiz tarihi:** 12 Ekim 2026

**Kaynak dosya:** `FermataX-yeni-arayuz-debug.apk` (kullanıcının bu konuşmada paylaştığı ~152.35 MiB APK). Bu ikili dosya GitHub deposuna yüklenmedi; kullanıcı cihazında veya oturum eklerinde kalır. Statik ZIP ve Android Binary XML manifest incelendi; APK çalıştırılmadı.

## APK kimliği ve bütünlük

| Alan | Değer |
|---|---|
| Manifest package | `me.app.fermataX.auto.debug` |
| Version name/code | `2.2.2` / `323` |
| minSdk/targetSdk | `28` / `36` |
| ZIP kayıt sayısı | `1605` |
| DEX dosyası | `12` |
| Native mimariler | `arm64-v8a`, `armeabi-v7a`, `x86_64` |
| SHA-256 | `56cc317e17590c0bcedac1c0cc92f845089641d28dc506913b226e66560d49bb` |

## Bileşenler

- `AutoFermataMediaService`: MediaService.
- `CarService`: AndroidX Car App.
- `PhoneLauncherActivity`: telefonda başlangıç bileşeni.
- `ProjectionActivity` ve `ProjectionService`: Android MediaProjection izin akışı.
- Manifestte derlenmiş uygulama modülleri: `base, exoplayer, podcast, radio, tv, vlc, web`.
- Üç ABI içinde `libvlc.so` ve `libvlcjni.so` bulunur.
- `android.permission.REQUEST_INSTALL_PACKAGES` ve `android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION` gibi hassas izin talepleri de ilan ediliyor; bunların varlığı uygulamanın güvenli veya zararlı olduğunu tek başına belirlemez.
- `targetSdk 36`, Android 15'teki davranışı garanti etmez; test gerektirir.

## Kaynak koduyla karşılaştırma

Açık kaynak proje: https://github.com/chuoinho/FermataX (GPL-3.0, Fermata fork'u). Kaynak deposu, panel/dash kart yönetimi, oynatma durumlarının aktarılması, son oynatılanlar, TV/radyo/podcast/web modülleri gibi genel mimariyi doğrular.

**Alınabilecek bağımsız fikirler:** tek giriş noktasında modüler medya paneli, Android Auto ile telefon oynatma yaşam döngülerini ayırma, kaynak/kategori bazlı geri dönüş, EPG, oynatmaya devam et kartı, kullanıcı kontrollü harici uygulama açma. **Doğrudan APK kopyalama veya native VLC kütüphanelerini aktarma yapılmadı**; lisans/uyumluluk ve paketleme gerektirir.

**Media Hub değişikliği:** Ortak Entegrasyon Merkezi, debug ve normal FermataX paketlerini ayrı uygulama olarak saptar ve kullanıcı isterse telefonda açar; şimdi doğrulanmış paket mimarisini ve oynatma motoru bilgilerini gösterir. Gerçek EV Cockpit ekranındaki video hâlâ doğrulanmadı.

**Not:** Paylaşılan YouTube bağlantısı https://youtu.be/CdU0OlLds2k doğrudan okunamadı; bu rapor video görüntülerini izlediğim iddiasında bulunmaz.

Sürücü ön ekranının hareket halinde video güvenlik kilidini devre dışı bırakan kod dahil edilmez.
