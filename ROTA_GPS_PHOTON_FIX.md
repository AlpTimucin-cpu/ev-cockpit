# Rota / GPS / Photon onarımı

- Dialog: IME padding, dikey kaydırma, klavyenin üzerinde sabit rota butonu.
- GPS: çalışma anında fine/coarse konum izni, FusedLocationProviderClient ile güncel tek seferlik ölçüm, ölçüm doğrulama ve hata gösterimi.
- Photon: desteklenmeyebilen `lang=tr` istekten kaldırıldı; HTTP/bağlantı hataları görünür.
- Buton: `startLocation != null && destinationLocation != null` koşulunda etkin.
- Var olan OSRM alternatifleri ve EPDK şarj katmanları korunur.
- Photon/OSRM kamuya açık demo servisleridir. Derleme ve cihaz testlerinden önce üretim kararlılığı iddiası yoktur.
