# Adım 12 — EV Cockpit · MEB Read-only PID Teşhis

Bu sürüm önceki `:nativecockpit` Android Auto + GPS + OSRM + EPDK modülünü korur.

## Önemli MEB PID bulgusu

Topluluk kaynağı: https://github.com/raimuucka/OBD2-Volkswagen-MEB-EV-CAN-parameters/blob/main/VW%20MEB%20UDS%20PIDs%20list.csv

- `22028C`: Topluluk tarafından BMS SoC olarak tanımlanır; `62 02 8C XX` pozitif yanıtında *aday* SoC `XX/2,5`. Gösterge paneli HMI SoC ile aynı olmayabilir. **Doğrulamasız rota/SOC hesaplamasına geçmez.**
- `221E0E`: Topluluk kaynağında **HV batarya maksimum sıcaklığıdır**, SoH değildir. `62 1E 0E WW XX YY ZZ` dönüşünde aday sıcaklık `(WW×256+XX)/64` °C. Daha önce paylaşılan `04 68 ...` değeri bu tanıma göre **17,625 °C** üretir; `112,8% SoH` değildir.
- `221E3D`: Ham HV akımı yanıtı; çoklu ISO-TP çerçevesi desteklenir ama akım dönüşümü kaynağa göre belirsiz olabileceğinden yalnızca ham yanıt sergilenir.

Tüm bu PID açıklamaları üretici garantili değildir; her EV Cockpit Pure aracında çalışacağı iddia edilmez.

## Güvenlik

Yalnız park halindeyken, kullanıcı telefon ekranında tek tek seçim yaptığında, 29-bit 500k CAN `ATSP7`, istek adresi `ATSH17FC007B` ile **UDS servis 22** read-only komutu gönderilir. Hiçbir DTC silme, ECU reset, coding, VIN sorgusu, servis 10/11/14/27/2E/31/3E yazımı yoktur. `ATH1` ile ECU kaynağı doğrulanır, yalnız `17FE007B` kabul edilir. Her adımda ELM ayarları otomatik `ATSP0`/`ATH0` durumuna döndürülür. Hata halinde bağlantı güvenli biçimde kapatılır.

Klon ELM327 adaptörlerde `ATSP7`, 29-bit header, otomatik flow-control desteklenmeyebilir. **Cihazı test etmeden canlı PID başarısı iddia edilmez.**

## Üretim notu

- Çalışma sırasında **0x22** sorgularından hiçbirisi otomatik gönderilmez; kullanıcı tek tek seçer.
- Sorgu sonucu yalnız volatil `StateFlow` belleğinde tutulur, disconnection/clear ile temizlenir.
- ECU onayından (SID 0x62, istenen DID, beklenen ECU CAN ID) ve ISO-TP bütünlüğünden geçmeyen yanıtlar reddedilir.
- BMS SoC/HV sıcaklık değerleri yalnız **deneysel teşhis adayı** olarak gösterilir; ana kokpit gerçek `VehicleTelemetry` için şimdilik hâlâ boş bırakılır. 
- Gerçek hız yalnız aracın desteklediği standart 010D yanıtına bağlıdır; `NO DATA` hâlinde görünmez.
- Android Auto DHU için `scripts/android_auto_dhu_test.ps1` kullanılabilir; burada gerçek telefon/DHU çalıştırılmadı.

## Derleme

```powershell
gradle :nativecockpit:testDebugUnitTest :nativecockpit:assembleDebug --stacktrace
```

Gerçek Android Auto ve OBD testleri fiziki cihazla doğrulanmalıdır.
