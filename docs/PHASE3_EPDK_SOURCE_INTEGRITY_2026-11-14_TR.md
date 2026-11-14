# Faz 3 · EPDK istasyon verisinin kaynak ve tazelik güvenliği
Tarih: 14 Kasım 2026 · Taslak, gerçek telefon ve araç testleri bekleniyor.

## Dayanak
- Faz 2 (TomTom) dalı `8972e569646277924597ae862692f863f103a788` üzerine açıldı; Faz 2 gerçek API/ID.4 kabulü henüz yapılmadı.
- Bu Faz 3 çalışma dalındaki sıkıştırılmış EPDK snapshot tarihi **17.10.2026**; bu tarih eski olarak gösterilmeli.
- Android uygulamasının `EpdkService` istemcisi `raw.githubusercontent.com/AlpTimucin-cpu/ev-cockpit/feature/ev-cockpit` üzerinde duran `data/charging/epdk_public_stations.json.gz` dosyasına bağlı. Bu dalın meta dosyası **13.11.2026 20:55:43 UTC** ve **13.259 halka açık istasyon** gösteriyordu (denetim 14 Kasım). **Bu, cep telefonunda canlı çekimin kanıtı değildir.**
- EPDK kamu istasyon kaydı socket status ve tarife içermez. `prices_included=false`, `live_status_included=false`. İstasyonların varlığı/CCS kaydı bugün erişilebilir veya boş olduğunu kanıtlamaz.
- Şarj planı BMS SoC + kullanıcı model profili + OSRM yol sapması olmadan tahmine dönüştürülmez; ETA, tarife, hız gücü çıkarılmaz.

## Uygulanan koruma
1. `EpdkSourceAgePolicy` 7 günlük **uygulama içi uyarı eşiği** kullanır, resmî EPDK yenileme SLA'sı değildir. ISO zaman damgasını UTC anına çevirir. Eksik, bozuk veya gelecekteki zamanlar `UNVERIFIED_DATE`; 7 günü geçen kaynak `OUTDATED_SNAPSHOT`; diğerleri `FRESH_SNAPSHOT`. Her üç durumda `CANLI DEĞİL`.
2. Telefon Şarj sekmesi yaş, kaynak UTC, offline-cache uyarısı ve anlık doluluk/fiyat yok açıklamasını taşır.
3. Android Auto istasyon listesi için ortak `CarChargingListPolicy`, aynı yaş durumunu gösterir.
4. Rota durak öneri ekranında katalog eskimiş/kanıtsız/önbellekteyse uyarı yazılır; mevcut koşullu BMS / gerçek yol kontrolü gevşetilmez.
5. `EpdkSnapshotParser` yalnız `https://apigateway.epdk.gov.tr/sarjIstasyonlari` kökenli olarak etiketlenen şema-v1 dokümanı kabul eder. Bu **kripto imzası / origin kanıtı değil**, hatalı etiketleme reddidir; HTTPS bağlantısı ve snapshot yayın güveni ayrı incelenir.
6. Debug tanılamasında UTC `+00:00` damgaları `Z` biçimine dönüştürülür; koordinat, VIN, anahtar veya istasyon adresi loglanmaz.
7. Python CI + Kotlin/JVM yaş eşik testleri eklendi; yanlış yeşil canlı-soket iddiası yok.

## Gerçek EPDK yenilemesi
Uygulamanın veri indirdiği feature dalında son yazım 13 Kasım göründü. Bunun ötesinde daha yeni istasyon kaydı canlı sorgulanmış gibi davranılmıyor.
GitHub Action `update-epdk-stations.yml` takvimi yalnız default dalda etkinleşebilir; bu taslak PR merge edilmedikçe günlük otomatik güncelleme garantisi yoktur.
EPDK resmi sorgu: https://apigateway.epdk.gov.tr/sarjIstasyonlari
İzinli snapshot oluşturucu: `scripts/update_epdk_public_stations.py`; raporun `verified_at_utc` alanı başarılı kaynak okuma/normalize sonucunda atanır.

## Kabul testleri
1. Faz 3 Debug APK: telefon Şarj sekmesindeki kaynak saati ve "CANLI DEĞİL" uyarısını kontrol et.
2. İnternet yokken istasyon önbelleğinin eskilik uyarısının korunduğunu doğrula; hata istasyon sayısı 0 gibi sunulmasın.
3. OSRM rota seç; rota DC ve rota CCS filtreleri, istasyon adları ve koordinatları doğrula. "CCS" olmayanı CCS listesine ekleme.
4. Station details'de çıplak sayı "150" değerinin "150 kW" gibi işlenmediğini ve fiyat/boşluk uydurulmadığını doğrula.
5. Park halindeki ID.4 Android Auto: listede kaynak yaş uyarısı ve host satır limitleri, harita taşması/çökme olmaması.
6. Hata/Debug Tanılama: kaynak UTC, olay/anahtar/VIN/koordinat yok.
7. Android lint, JVM, Python kaynak testleri, R8 Release smoke, Gitleaks tümü aynı HEAD'de SUCCESS olmalı. Telefon/ID.4 testleri olmadan PR merge/release yok.

Geri alma: yalnız `phase3/epdk-charging-source-integrity-20261114` dalını kaldırıp Faz 3 PR'ını kapat; Faz 1/Faz 2 ve `main` değişmez.
