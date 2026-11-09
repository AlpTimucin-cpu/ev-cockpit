# EV Cockpit — İş 3: Eski kaynak arşivi ve son yayın güvenliği

**Durum:** Hazırlık / denetim. **Resmî Release veya main birleşmesi yapılmadı.**

## 1. Neden iki ZIP inceleniyor?

9 Kasım 2026 tarihinde güvenli entegrasyon dalının ağaç içeriğinde **iki ayrı** kaynak
arşivi bulundu:

| Kök dosya | Git blob SHA | Git'te bayt |
| --- | --- | ---: |
| `EV_Cockpit_v0.8_Source.zip` | `480e143f5d0e48ca309f6b4075d6d75732fdc79b` | 49.559 |
| `EV_Cockpit_Legacy_Source_v0.8.zip` | `ca680dd2ca018a306d46e15e3a84acca591ce2cb` | 49.422 |

Bunlar farklı dosyalardır; **aynı kaynak kodu oldukları varsayılmaz**.
İkisi de eski v0.8 dönemine aittir; yeni `nativecockpit` için
karşılık gelen güncel kaynak veya imzalı Release olarak sunulamaz.

`scripts/audit_legacy_archives.py` ikisini **çıkarıp çalıştırmadan**:
ZIP dizin/traversal, bozuk CRC, şifreli giriş, sembolik bağlantı,
yıkıcı sıkıştırma oranı, açık özel anahtar dosyası ve seçili yüksek
güvenli token örüntüleri yönünden inceler. Her biri için **gerçek
SHA-256, sıkıştırılmış bayt, giriş sayısı ve redakte bulgu kodları**
üretir. Tam bir güvenlik taraması veya kaynak-lisans denetimi olduğu
iddia edilmez.

**Denetim çıktısı:** GitHub Actions > ilgili Debug çalışması >
Artifacts > `native-phase0-reports` >
`build/legacy-source-archives-audit.json`. Rapor üretilmeden hiçbir
ZIP'in Releases'e taşındığı veya güvenli bulunduğu iddia edilmez.

## 2. Arşivi telefondan taşıma — doğrulama sonrası

1. Kaynak ZIP'i **aynı GitHub commit'inden** telefona indir;
   `EV_Cockpit_v0.8_Source.zip` dosyasını
   `EV_Cockpit_Legacy_Source_v0.8.zip` ile karıştırma.
2. Önce ilgili Actions raporundaki **her iki ZIP için PASS**, bayt
   boyutları ve SHA-256 değerlerini kontrol et. Herhangi bir `BLOCK`
   durumunda işlem durur; bulgu yok sayılmaz.
3. GitHub mobil tarayıcıda depo > **Releases** > **Draft a new release**.
   İmza/üretim sürümü adı kullanma. Açıklamaya
   `LEGACY SOURCE ARCHIVE (v0.8) — NOT APK, NOT CURRENT NATIVECOCKPIT`
   ve kaynak commit SHA'sını yaz. Yeni tag gerekiyorsa anlamlı
   `legacy-source-v0.8` türü ayrı bir ad seç; mevcut `v1.0.2`
   üretim APK'sının kaynaklarıyla eşdeğer olduğunu iddia etme.
4. **Publish release** düğmesine basma. Önce ZIP'i taslağa ekle
   (mobil tarayıcıda gerekirse **Masaüstü site** görünümü) ve
   yüklenen dosyanın boyut/SHA-256 doğrulamasını karşılaştır.
   GitHub UI yüklenen asset için SHA-256 sağlamıyorsa doğrulama
   **yapılmadı** olarak kalır; yalnız ada bakarak PASS verme.
5. Eski ZIP için telif/lisans ve sır denetimi ayrıca tamamlanmalı.
   Bunlar sağlanmadan taslak kaynak yayını kamuya açma.
6. **GitHub'da yeni asset'in doğrulanmış bağlantısı olmadan**
   kök ZIP silinmeyecek. Doğrulanınca silme, yalnız başka
   bir geliştirme dalı / taslak PR / CI ile önerilecek; Git geçmişi
   silinmiş sayılmayacak.

**Mevcut GitHub bağlantısının sınırı:** Release oluşturma, binary asset
yükleme, repository About/Topics değiştirme yetkisi yok. Bu yüzden
asistanın kaynak ZIP'i Releases'e taşıdığı iddia edilemez.

## 3. Depo açıklaması ve Topics — telefonda yapılacak

Depo: https://github.com/AlpTimucin-cpu/ev-cockpit

**About > dişli / Edit repository details > Description**:

> EV Cockpit — independent Kotlin/Compose electric-vehicle navigation,
> MapLibre/OSRM, Android Auto, EPDK charging, Open-Meteo forecasts and
> read-only ELM327 diagnostics. Preview; vehicle acceptance not verified.

**Topics:** `android`, `kotlin`, `jetpack-compose`,
`android-auto`, `maplibre`, `electric-vehicles`, `osrm`,
`open-meteo`, `obd2`, `ev-charging`.

Bunları kaydettikten sonra GitHub'ın About bölümünde gerçekten
göründüğünü kontrol et. Asistanın yerinize kaydetmesi bu bağlantıda
desteklenmediği için **güncellendi denmez**.

## 4. Tarihî anahtar riski — yayından önce zorunlu

- GitHub'da, eski gelişim geçmişini içeren taslak PR #50'nin Gitleaks
  taraması **1.963 commit'te 4 olası bulgu** raporladı. Bunların
  her birinin gerçek sır mı, yanlış pozitif mi olduğu ve rotasyon
  durumu **ayrı ayrı doğrulanmadı**.
- PR #51, yalnızca **güncel kaynak snapshot** commit'inde başarılı
  Gitleaks sonucu verdi; eski commitleri/sızıntıları geriye dönük
  temizlemez. Kamuya açık tarih ve eski APK'lar ayrıca incelenmelidir.
- Eski TomTom anahtarları kullanıldıysa TomTom geliştirici
  panelinden **iptal et ve yenisini oluştur**, kullanım/kota kayıtlarını
  denetle; anahtar değerlerini ChatGPT'ye veya Issue'ya yapıştırma.
- GitHub > **Settings > Secrets and variables > Actions** altında eski
  imzalama ve servis secret adlarını değerlendir; olası açığa çıkmış
  gerçek değerleri **emekli et**, yeni credential gerekiyorsa
  yalnız güvenli ortamda üret. Secret'ı silmek eskiden yayımlanmış
  özel anahtarı geri çekmez.
- Android **imza özel anahtarı** kamuya açıldıysa güvenilir Release
  anahtarı olarak bir daha kullanma. Yeni sertifikayla aynı
  `applicationId`'yi sideload güncellemek imza uyumsuzluğu
  nedeniyle başarısız olabilir; Google Play App Signing kullanılıyorsa
  ilgili resmi anahtar yükseltme süreçleri ayrı incelenmelidir.
  Yeni kimlik/anahtar geçişi sahibin açık onayı olmadan yapılmaz.
- Gerçek ID.4 Android Auto ekranı/GPS/OBD/ses için İş 2 A–H
  testleri tamamlanana kadar Release kilidi `if: false` **kalır**.
  Eski `auto-release.yml` de kapalı kalır.

## 5. Başarılı olmasının ne anlama gelmediği

Arşiv ZIP denetiminin geçmesi; kodun derlendiğini, Android Auto
Play uygunluğunu, ID.4 BMS SoC'nin doğrulandığını veya eski
`v1.0.0` / `v1.0.2` APK'larının güvenli olduğunu **göstermez**.
Mevcut taslak entegrasyon PR #51 merge edilmedi ve yeni üretim
Release yayımlanmadı.

Geri alma: yeni arşiv denetimi PR'ını kapat; `main`, PR #51,
GitHub Releases ve kökteki her iki ZIP aynen kalır.
