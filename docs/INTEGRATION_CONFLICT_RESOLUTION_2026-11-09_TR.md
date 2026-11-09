# EV Cockpit — main ile çakışma çözüm raporu (9 Kasım 2026)

**Durum:** Taslak entegrasyon, üretim yayını **DEĞİL**. Ana dalda doğrudan değişiklik yok. **Eski tarihsel commit zinciri Gitleaks nedeniyle güvenli sayılmadı; yalnız güncel kaynak ağacı tek-parent snapshot ile önerildi.**
**Kaynak:** GitHub üç-yollu ağaç karşılaştırması; ortak ata
`b5e026c8b955fcede9a6c88a48a885ac004a72ba`, karşılaştırılan main
`80ab19471abbe8ffb33a82e240d98e426e2a4b50`, son güvenlik doğrulanmış
geliştirme kaynak commit'i `c1f8332b1ff572391c0434265e0abd55d6cb565e`.

## Ölçüm

- Ortak ata: 72 Git ağaç girdisi (dosyalar ve dizinler dâhil).
- Main: 84 ağaç girdisi; geliştirme: 774 girdisi.
- **7 aynı yolda farklı içerik** tespit edildi.
- **5 yalnız main'de yeni/değişmiş dosya** tespit edildi.
- Bu, GitHub PR #49'un `mergeable=false/dirty` olmasını açıklar;
  yalnızca dosya sayısı değil, gerçek dosya çakışmaları çözülecek.
- Karşılaştırma, tarihsel commit ve binary güvenlik incelemesinin yerine
  geçmez. Büyük entegrasyon için CI ve gerçek araç UAT zorunludur.

## Çakışan 7 yol ve tercih edilen çözüm

| Yol | Karar | Gerekçe |
| --- | --- | --- |
| `README.md` | Güncel native rehberi + eski main'e ilişkin tarihsel ve üretim dışı uyarı | Uygulama kimliği, gerçek test sınırı korunur |
| `README_TR.md` | Güncel Türkçe native rehber + entegrasyon öncesi main notu | Mevcut eski Releases, yeni native APK değildir |
| `.github/workflows/build-nativecockpit.yml` | Son başarılı native güvenlik kapısı | Lint, OBD, secret scan, APK DEX doğrulaması eski CI'dan daha sıkı |
| `.github/workflows/release.yml` | **Geliştirme sürümü, `if: false`** | Eski dağıtım sırrının açığa çıkma riski; Release kapalı |
| `NOTICE.md` | Güncel bildirim + eski medya/alt lisansların geçerliliğini koruyan not | GPL ve atıf geçmişini silmez |
| `SECURITY_TOMTOM.md` | Güncel güvenlik rehberi + eski `ID4_TOMTOM_API_KEY` riski | Eski ve yeni anahtar adlarının riskini saklamaz |
| `docs/DOCUMENTATION_INDEX.md` | İki taraftaki belge bağlantılarının birleşimi | Main'in tarihî UX/kaynak belgeleri kaybolmaz |

## Main'e özgü beş yol

| Main-only yol | Entegrasyon kararı |
| --- | --- |
| `.github/workflows/auto-release.yml` | **Korundu ama `if: false` güvenlik kilidiyle devre dışı**; geçici imzalama kaldırıldı |
| `EV_Cockpit_Legacy_Source_v0.8.zip` | Git blob SHA korunarak taşınır; **Releases'e yüklendiği iddia edilmez**, silinmez |
| `docs/PRODUCTION_UX_KOTLIN_SOURCES.md` | Main blobu eksiksiz korunur (tarihî) |
| `docs/PRODUCTION_UX_RESOURCES_RELEASE.md` | Main blobu eksiksiz korunur (tarihî) |
| `docs/PROJECT_HISTORY.md` | Main blobu eksiksiz korunur |

## Teknik entegrasyon

- Kaynak feature dalı üzerinde 7 çakışma **açık kararlarla** çözüldü.
- Eksik yalnız-main dosyalar GitHub Git Tree API ile, **özgün blob SHA'ları
  değiştirilmeden** yeni ağaç nesnesine taşınır; ikili ZIP kopyalanıp
  kodlanmaz veya kayıplı şekilde yeniden üretilmez.
- İlk deneme iki ebeveynli PR #50 ile yapıldı. GitHub bu PR'ı içerik
  çakışması bakımından temiz buldu ancak Gitleaks, taşınacak 1.963
  tarihsel commit'te **4 bulgu** tespit ederek reddetti. Bu bulgular
  doğrulanıp eski anahtarlar döndürülmeden eski feature commit geçmişi
  `main` tarihine eklenemez. **PR #50 bloke/terk edilmiştir.**
- Güvenli alternatif: uzlaştırılmış **aynı güncel Git tree** içeriği,
  doğrudan mevcut `main` commit'inin **tek ebeveynli yeni bir commit'i**
  olarak hazırlanmıştır. Bu, `main` içeriğini veya geçmişini yazmaz;
  tarihsel feature commit'lerini `main` geçmişine katmaz. Eski geliştirme
  PR'ları ve kaynak geçmişi GitHub'daki kendi dallarında korunur.
  Yeni temiz snapshot da **tüm dosya diff'i üzerinden** Gitleaks,
  APK ve Release-smoke kontrollerinden geçmelidir.
- Sadece **taslak PR**, `main`'e merge/release değildir.
- Android lint + tüm JVM ve OBD güvenlik testleri + Gitleaks + Debug DEX
  testi + R8 Release smoke aynı entegrasyon commit'inde **yeşil olmalı**.
- Entegrasyon statik CI geçerse bile Volkswagen ID.4 üzerinde gerçek
  GPS/USB/map/audio/OBD kabul kanıtı sağlanana kadar yayın **YASAK**.

## Geri alma

Yeni **snapshot entegrasyon** taslak PR'ı kapatılıp dalı silinebilir. `main`,
eski PR #48, #49 ve bloke #50 değişmez. Eski potansiyel gizli anahtarlar
kamuya açık geçmişte kalabileceğinden, kaynak snapshot CI geçse bile
anahtar rotasyonu ve önceki APK'ların incelenmesi zorunludur. Gelecekte ana dala birleştirme ancak gerçek UAT, gizli anahtar
rotasyonu ve kullanıcı onayından sonra yapılabilir.
