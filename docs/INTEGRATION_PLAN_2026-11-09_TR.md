# EV Cockpit — 9 Kasım 2026 entegrasyon ve çakışma raporu

**Durum: PLAN. `main` DEĞİŞMEDİ, birleştirme YAPILMADI.**
Bu rapor `feature/job2-phone-id4-uat-20261109` baz alınarak GitHub API ile doğrulandı:
**34 açık PR**. `main` ile karşılaştırmada **1.967 commit ileri / 34 commit geri**,
GitHub API'nin ilk **300 dosyalık** değişim kesitine ulaşan büyük bir ayrışma var.
Bu sayılar doğrulama anına aittir; merge-uyumluluğu ispatlamaz.

## 1. PR bağımlılık sırası ve seçilecek hat

| Aşama | PR / iş | Doğrulanmış baz | Durum / karar |
|---|---|---|---|
| Temel | [#16](https://github.com/AlpTimucin-cpu/ev-cockpit/pull/16) audit main wrapper | `main` | Öncesinde güvenli temel doğrulanmalı |
| CI | [#17](https://github.com/AlpTimucin-cpu/ev-cockpit/pull/17) verified Android Debug | #16 | İnceleme sırası |
| Güvenlik | [#18](https://github.com/AlpTimucin-cpu/ev-cockpit/pull/18) secret scanning | #17 | İnceleme sırası |
| İmzalama | [#19](https://github.com/AlpTimucin-cpu/ev-cockpit/pull/19) protected signing | #18 | Gerçek Release ANAHTARI HALA BLOKLU |
| Temizlik varyantları | [#20](https://github.com/AlpTimucin-cpu/ev-cockpit/pull/20), [#21](https://github.com/AlpTimucin-cpu/ev-cockpit/pull/21) | ikisi de #19 | **İkisini körlemesine merge etme**; #21 ana hat |
| Yol haritası | [#22](https://github.com/AlpTimucin-cpu/ev-cockpit/pull/22) | #21 | Ana hat |
| Alternatif audit | [#23](https://github.com/AlpTimucin-cpu/ev-cockpit/pull/23), [#24](https://github.com/AlpTimucin-cpu/ev-cockpit/pull/24) | ikisi de #22 | Yinelenen değişiklik riski; dosya bazında karşılaştır |
| Faz 0 native | [#25](https://github.com/AlpTimucin-cpu/ev-cockpit/pull/25) | `feature/ev-cockpit` | Asıl native zincirin başlangıcı; #24'ün direkt çocuğu DEĞİL |
| Faz 1 | [#26](https://github.com/AlpTimucin-cpu/ev-cockpit/pull/26) | #25 | Ana hat |
| Faz 2 | [#27](https://github.com/AlpTimucin-cpu/ev-cockpit/pull/27) | #26 | Ana hat |
| Faz 3 | [#28](https://github.com/AlpTimucin-cpu/ev-cockpit/pull/28) | #27 | Ana hat |
| Faz 4 | [#29](https://github.com/AlpTimucin-cpu/ev-cockpit/pull/29) | #28 | Ana hat |
| Faz 5 | [#30](https://github.com/AlpTimucin-cpu/ev-cockpit/pull/30) | #29 | Ana hat |
| Faz 6 | [#31](https://github.com/AlpTimucin-cpu/ev-cockpit/pull/31) | #30 | Ana hat |
| Ayrı güvenlik kilidi | [#32](https://github.com/AlpTimucin-cpu/ev-cockpit/pull/32) | `main` | **Merge koruması öncelikli; imzalı yayın hâlâ bloke** |
| Faz 7 | [#33](https://github.com/AlpTimucin-cpu/ev-cockpit/pull/33) | #31 | Ana hat |
| Faz 8 | [#34](https://github.com/AlpTimucin-cpu/ev-cockpit/pull/34) | #33 | Ana hat |
| Faz 9 | [#35](https://github.com/AlpTimucin-cpu/ev-cockpit/pull/35) | #34 | Ana hat |
| Faz 10 | [#36](https://github.com/AlpTimucin-cpu/ev-cockpit/pull/36) | #35 | Ana hat |
| Faz 11 alternatifleri | [#37](https://github.com/AlpTimucin-cpu/ev-cockpit/pull/37) / [#38](https://github.com/AlpTimucin-cpu/ev-cockpit/pull/38) | ikisi de #36 | **#38 ana hat; #37'yi ayrıca cherry-pick yapma** |
| Kokpit paneli | [#39](https://github.com/AlpTimucin-cpu/ev-cockpit/pull/39) | #38 | Ana hat |
| Faz 12/2 | [#40](https://github.com/AlpTimucin-cpu/ev-cockpit/pull/40) | #39 | Ana hat |
| Faz 12/3 | [#41](https://github.com/AlpTimucin-cpu/ev-cockpit/pull/41) | #40 | Ana hat |
| Faz 12/4 | [#42](https://github.com/AlpTimucin-cpu/ev-cockpit/pull/42) | #41 | Ana hat |
| Faz 12/5 | [#43](https://github.com/AlpTimucin-cpu/ev-cockpit/pull/43) | #42 | Ana hat |
| Faz 12/6 | [#44](https://github.com/AlpTimucin-cpu/ev-cockpit/pull/44) | #43 | Ana hat |
| Faz 12/7 | [#45](https://github.com/AlpTimucin-cpu/ev-cockpit/pull/45) | #44 | Ana hat |
| İş 1 tanılama | [#46](https://github.com/AlpTimucin-cpu/ev-cockpit/pull/46) | #45 | Ana hat |
| İş 2 telefon/araç protokolü | [#47](https://github.com/AlpTimucin-cpu/ev-cockpit/pull/47) | #46 | Ana hat |
| İş 3 güvenlik / hazırlık | Yeni taslak PR (bu dal) | #47 | Sadece CI ve belge onayı; main'e yazma yok |
| Eski medya dalları | [#1](https://github.com/AlpTimucin-cpu/ev-cockpit/pull/1), [#2](https://github.com/AlpTimucin-cpu/ev-cockpit/pull/2) | `feature/fermata-style-car-video`, `feature/mola-32-migration` | **Dışarıda tut**; eski araç videosu, güvenlik ve lisans riski |

## 2. Tek entegrasyon dalı yöntemi

1. **Asla 34 PR'ı tek tek aynı içeriği tekrar tekrar merge ederek ilerleme.**
   Bunların çoğu üst üste kurulmuş; tek güncel native kaynak ağacını **bir kez**
   birleştirmek gerekir. Ayrı audit/hijyen alternatifleri `#20/#21`,
   `#23/#24`, `#37/#38` çakışan değişikliklere neden olabilir.
2. İş 3'ün **aynı HEAD SHA'sı** üzerinde Debug lint/JVM/secret scan ve
   **geçici anahtarlı, yayımlanmayan Release smoke** CI yeşil olsun.
   Açık imzalama güvenlik kilidini kaldırma.
3. Ardından `integration/ev-cockpit-native-20261109` dalını **İş 3'ün
   doğrulanmış HEAD commit'inden** oluştur. Bu dal, native hattın tek
   kanonik anlık görüntüsü olur. `main` hedefine **draft PR** aç; Gitleaks,
   Debug ve Release smoke kontrolleri yeni PR'ın kendi HEAD'inde yine yeşil olsun.
4. **Çakışmaların çözüm sırası:** `#32` güvenlik hold davranışı >
   Gradle wrapper / `settings.gradle.kts` native modül tanımı >
   AndroidManifest ve Android Auto politikası >
   ELM327 salt-okunur tel koruması >
   medya / README / eski ZIP.
   Merge `release.yml` kilidini açarsa işlemi **reddet**.
5. GitHub'daki **"Files changed"** üzerinden büyük diff'i ve gizli bilgi
   taramasını insan gözünden geçir. Gereksiz eski `:app` medya içeriği ve
   araç videosunun otomatik olarak üretim paketi içine girmediğini doğrula.
6. Gerçek Android Auto/ID.4 telefon testleri (İş 2 A–H) **henüz başarılı
   değil**. Bu nedenle draft PR **merge edilmez**; sahip onay vermeden
   `main` güncellenmez. CI yeşil = üretim uygunluğu değildir.

## 3. Çakışma / güvenlik riskleri

| Risk | Şiddet | Karar |
|---|---|---|
| `main` 34 commit farklı; 1.967 commit büyük tarihi farklılık | Kritik | DRAFT entegrasyon PR; otomatik merge YOK |
| Eski anahtarın kamuya açık Git geçmişi / mevcut `release.yml` `if: false` | Kritik | Eski imza devre dışı; sertifika geçişi ayrı onay |
| `settings.gradle.kts` sadece eski `:app` ile native modül farklılıkları | Yüksek | Sadece kanonik native snapshot üzerinden Gradle CI |
| `AndroidManifest.xml`, farklı Car App ve medya şablonları | Yüksek | Araç dışı test yetmez; DHU/ID.4 A–H kanıtı |
| `#37/#38` rota düzeltmesi alternatifleri | Yüksek | Birini seç, diğerini yeniden merge etme |
| `#20/#21`, `#23/#24` çakışan audit/hijyen | Orta–Yüksek | Diff bazlı reconciliation |
| Legacy YouTube/filter APK kaynağı ve medya-hub farkı | Yüksek | Kaynak + gerçek DEX taraması, yalnız native kanıt sınırı |
| Kökte eski `EV_Cockpit_v0.8_Source.zip` (49.559 B) | Orta | Releases varlık taşıma yetkisi yoksa silme YAPMA |
| Başarılı R8 derlemesi gerçek ID.4'te davranışı kanıtlamaz | Yüksek | Gerçek araç kabul sonucu beklenir |

## 4. Kesinlikle yapılmayanlar

- Gerçek imzalama sırrı okunmaz, üretilmez, kullanıma alınmaz.
- Tamamlanmamış gerçek araç kabulü `PASS` olarak gösterilmez.
- Eski medya APK'sının YouTube sınıflarının yokluğu, **yalnız
  `:nativecockpit` APK DEX** taramasıyla kanıtlanmış sayılmaz.
- `main` veya önceki PR'ların **başları** doğrudan değiştirilmez.
- Eski ZIP bir Releases asset'i olarak doğrulanmadan depodan silinmez.
