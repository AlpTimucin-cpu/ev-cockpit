# EV Cockpit v1.2 • Gömülü Türkiye Haritası

Bu sürümde **harita EV Cockpit içinden çıkmadan açılır**:
Netflix/Disney+ benzeri bir uygulama-içi deneyim, fakat video veya
kopyalanmış üçüncü taraf navigasyon kodu değildir.

## Başlangıç
- Ana sayfada **Türkiye Haritası • Uygulama içinde**.
- EPDK halka açık istasyon listesinde **Haritada aç**.
- Türkiye içinde serbest adres araması (Nominatim; düğmeye basınca).
- Haritada **dokun**: hedef seç. **Uzun bas**: başlangıç seç.
- **Rota çiz**: yalnızca kullanıcı isteğiyle sınırlı OSRM rota
  önizlemesi. Mavi rota uygulamanın içinde çizilir.
- **⚡ İstasyonlar**: görünür bölgede EPDK halka açık kayıtlarından
  en fazla 55 nokta çizilir. EPDK JSON/gzip okuma arka plan iş parçacığında.

## Önceki ANR sorunu için yaklaşım
Eski sürümün MapLibre/GPU harita kütüphanesi ve tek karede yüzlerce
işaret oluşturma kodu EV Lite modülüne **eklenmedi**. Yerine Android'in
WebView'ı + Leaflet 1.9.4 (uzaktan SRI doğrulamalı CDN) ve AndroidX
WebViewAssetLoader 1.16.0 ile uygulama içine paketlenmiş özgün HTML
kullanılır. Harita 55 noktayla sınırlı; 13 binden fazla istasyon
ana iş parçacığında çizilmez. Bu ANR'nin kesin çözüldüğü anlamına
gelmez; gerçek POCO F5 testi gerekir.

## Kısıtlar
- Gömülü harita için internet gereklidir. Leaflet JavaScript/CSS
  unpkg CDN'den, harita görselleri OpenStreetMap sunucusundan gelir.
  OSM'nin tile policy'si (attribution, önbellek, düşük trafik,
  önceden indirme yasağı) geçerlidir:
  https://operations.osmfoundation.org/policies/tiles/
- OSM'nin halka açık tile sunucusu ticari/yüksek trafikli uygulama
  dağıtımı için garanti verilmiş servis değildir. Büyümede sözleşmeli
  tile provider veya barındırılan tile cache tercih edilmelidir.
- Nominatim tekil, manuel arama içindir; otomatik öneri/sürekli
  arka plan sorgusu yapılmaz:
  https://operations.osmfoundation.org/policies/nominatim/
- Rota OSRM kamu/demo servisinde anlık istekle hesaplanır; yol
  koşulları, canlı trafik, şerit bilgisi ve **sesli dönüş komutu yoktur**.
  Bu bir rota *önizlemesi*dir, sertifikalı turn-by-turn navigasyon değil.
  Büyük ölçek için kendi veya lisanslı routing backend'i gerekir.
- Kullanıcının konumu **otomatik takip edilmez**; GPS ve arka plan
  konum izni talep edilmez. Başlangıcı haritaya uzun basarak seçer.
- EPDK snapshot'ında canlı soket doluluğu ve güncel soket fiyatları
  yoktur. Doluluk bilgisi uydurulmaz.
- Android Auto'nun araç navigasyon kategorisi uygulanmış değildir;
  ekran telefon üzerinde çalışmak üzere tasarlanmıştır.
- Film/dizi katalogları geri getirilmez; MediaEngine TV isteğe bağlı
  ayrı menüde kalır.

## Proje
- Kaynak HTML: `evlite/src/main/assets/map/index.html`
- Android Activity: `evlite/src/main/java/com/evcockpit/mediahub/EmbeddedMapActivity.kt`
- Adres/rota API: `evlite/src/main/java/com/evcockpit/mediahub/EmbeddedMapServices.kt`
- Sürüm: v1.2-embedded-map-beta
- APK paket kimliği: `com.evcockpit.evguide.lite.tvbeta`.
