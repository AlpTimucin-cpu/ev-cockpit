# EV Cockpit — Medya ve Araç Entegrasyonu

Bu bölüm, açık kaynak GPL-3.0 medya uygulaması üzerine **EV Cockpit** navigasyon, enerji, OBD ve şarj ekranının eklenmesini açıklar. Kullanıcı tarafından daha önce kurulan başka bir medya uygulamasının imzalı APK dosyası değiştirilmez; bu proje ayrı bir önizleme paketi oluşturur.

## Kaynak ve lisans sınırı

Kullanılan özgün medya projesinin sürüm ve commit kimliği derleme betiğinde **sabitlenmiştir**. Bu önizleme sürümünün o projenin daha yeni, farklı bir kapalı dağıtımıyla birebir aynı olduğu iddia edilmez.

Özgün medya motoru, eklenti ve oynatıcılar mümkün olduğu ölçüde dokunulmadan korunur. Kod değişiklikleri pinlenmiş kaynak ağacı üzerinde doğrulanır; kaynak hash'i değişirse işlem güvenli biçimde durur. Türetilen paket, ilgili açık kaynak lisansının kaynak kod erişimi ve atıf yükümlülüklerine tabidir. Ayrıntılar: [LICENSE](../LICENSE) ve [NOTICE](../NOTICE.md).

## Harita ve rota

- Tek bir Leaflet haritası açılır; diğer harita bileşenleri aynı örneğe bağlanır.
- OSRM yanıtındaki gerçek GeoJSON rota geometrisi incelenip çizilir. Ağ başarısızsa olmayan rota oluşturulmuş gibi gösterilmez.
- Navigasyon süresi kullanılan rota motorunun verdiği tahmindir; gerçek zamanlı trafiğin etkisi ayrıca doğrulanmadan katılmış sayılmaz.
- İstasyonlar veri kaynağı ve güncellik durumuyla gösterilir. Anlık boş soket veya kesin fiyat verisi uydurulmaz.

## OBD köprüsü ve veri güvenliği

- Araçtan gelen ölçümler mevcut Android Java telemetri sınıfının olaylarıyla ön uca iletilir.
- İkinci bir herkese açık JavaScript arayüzü veya sabit JSON telemetri üreticisi eklenmez.
- Gerçek SoC, voltaj, sıcaklık ve diğer ölçümler yalnız doğrulanmış/veri yaşı uygun olduğunda gösterilir.
- Rota ilerlemesi güncel GPS noktasının yol geometrisine izdüşümünden türetilir; GPS yoksa ilerleme ölçülmüş gibi sunulmaz.
- Çalışma sırasında ekran yeniden çizilirken orijinal medya hizmetleri durdurulmaz.

## Sürüş odağı

Telefon ve araç ekranlarının izin verilen uygulama kategorileri farklıdır. Görsel medya ve üçüncü taraf web servislerinin sürücü ekranında hareket hâlindeyken kullanımını açmaya yönelik kısıtlar kaldırılmaz.

## Yeni marka

Kullanıcıya görünen uygulama başlığı **EV Cockpit**dır. Eski sınıf, kaynak dizini, köprü olayı ve paket kimliği tanımlayıcıları Android güncelleme uyumluluğu nedeniyle şimdilik teknik düzeyde korunur. Bu adları toplu biçimde değiştirmek mevcut derleme testlerini ve uygulama verilerini etkileyebilir.

Kaynak dokümanın gerçek koda uygulanma detayları: [Teknik entegrasyon](../docs/ELEKTRIKLI_YOL_ARKADASIM_ENTEGRASYON.md). Bu açıklama fiziksel araç testi yerine geçmez.
