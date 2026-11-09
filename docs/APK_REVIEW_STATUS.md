# Claude bağlantısından APK aktarımı — doğrulama durumu

**Kontrol edilen adres:** https://claude.ai/artifact/J4Nmn2k3jqWkuyVTeXzjJh

**12 Ekim 2026 erişim sonucu:** Claude Artifact sayfası web isteğine yanıt vermiyor; erişim engellendi. Bu nedenle bağlantıda kaç APK bulunduğu, paket adları, sürümleri, izinleri, kaynak kodları veya lisansları **bilinmiyor**. Hiçbir APK indirilmiş, tersine mühendislikle incelenmiş ya da EV Cockpit'a monte edilmiş olarak gösterilemez.

## Dosyalar sağlandığında izlenecek güvenli süreç

1. Claude ekranında APK dosyalarını indirin. Varsa tüm dosyaları tek ZIP arşivine koyun. **APK'yı yüklemeden veya çalıştırmadan** mevcut sohbete ekleyin ya da erişilebilen GitHub deposuna kaynakları ekleyin.
2. Her APK için SHA-256, paket adı/sürümü, manifest, izinler, dex ve native kütüphane envanteri oluşturun.
3. İzin/veri erişimi, dış bağlantı, sertifika doğrulama, lisans ve Android 15 uyumluluğunu denetleyin. APK içindeki özel anahtarlar veya oturum belirteçleri rapora alınmaz.
4. Hakları uygun açık kaynak parçaları kaynak belirtip lisans şartlarına uyarak kullanın; özel mülk yazılımlardan uygulama kodu, marka veya ikon kopyalamayın. Belgelendirilmiş kamuya açık Intent/API arayüzleri üzerinden bağlanma seçeneğini değerlendirin.
5. Özellikleri kendi Media Hub kodunda derleyin, otomatik Android testlerini çalıştırın; fiziksel electric vehicle test sonuçlarını ayrıca kaydedin.

## Mevcut APK envanter aracı

Bağımsız ve salt okunur araç: `tools/apk_inventory.py`.

Windows'ta veya Linux'ta, Python 3 ile:

```bash
python tools/apk_inventory.py app1.apk app2.apk --out apk_inventory_report.json
```

Android SDK'nın `aapt` programı PATH üzerinde mevcutsa APK paket adı, sürüm, min/target SDK ve ilan edilen izinler de raporlanır. `aapt` yoksa sınırlı ZIP içerik envanteri çıkarılır. APK'lar **kurulmaz veya çalıştırılmaz**. DEX dosyası tespit etmek orijinal kaynak kodunu elde etmek anlamına gelmez.

## Sınırlar

- Dış uygulamalar sürücü ön ekranındaki hareket halinde video kilidini aşmak için kullanılmaz.
- Lisans, DRM, telif veya izin kontrollerini aşan kod uyarlanmaz.
- APK envanter dosyası güvenlik garantisi, telif hakkı lisansı veya EV Cockpit uyumluluk belgesi değildir.
