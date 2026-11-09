package com.evcockpit.mediahub

/**
 * Architecture/source register. Intentionally pure Kotlin so the verified contracts
 * remain testable without Android, external APKs, proprietary SDKs or car hardware.
 *
 * "NATIVE" means implemented INSIDE EV Cockpit; "EXTERNAL" uses only public,
 * user-initiated Android actions. "REFERENCE" is a design idea, not an integration.
 */
enum class IntegrationKind { NATIVE, EXTERNAL, DIAGNOSTIC, REFERENCE }

data class IntegrationSource(
    val id: String,
    val name: String,
    val kind: IntegrationKind,
    val detail: String,
    val packages: List<String> = emptyList(),
    val projectUrl: String = ""
)

object IntegrationCatalog {
    val sources = listOf(
        IntegrationSource(
            "turkey_map", "Türkiye Haritası ve Şarj Noktaları", IntegrationKind.NATIVE,
            "MapLibre/OpenFreeMap telefonda harita; manuel Türkiye adres araması, " +
                "görünür semtte OSM ve isteğe bağlı Open Charge Map istasyonları; " +
                "hedefi Google Maps'in dönüş dönüş navigasyonuna aktarır. " +
                "Şarj@TR üzerinden resmî fiyat ve müsaitlik bilgilerine bağlantı sağlar. " +
                "Araç içi yerleşik navigasyonun açılması veya bağımsız rota motoru değildir.",
            projectUrl = "https://openfreemap.org/quick_start/"),
        IntegrationSource(
            "mediaengine_native", "MediaEngine kaynak özellikleri • uygulama içinde", IntegrationKind.NATIVE,
            "HTTPS Xtream Codes M3U içe aktarma, LibVLC 3.7.2 alternatif video motoru, " +
                "Keystore AES-GCM şifreli TV/radyo yayın URL'leri. " +
                "Bunlar kaynak kodda mevcut; başarılı APK derlemesi ve araç testi henüz doğrulanmadı.",
            projectUrl = "https://github.com/moris545454/araba-"),
        IntegrationSource(
            "media3", "Media3 / Fermata tarzı IPTV", IntegrationKind.NATIVE,
            "HLS/M3U8, IPTV kanal seçimi, kalite, altyazı, ses dili ve oynatma sorunlarını izleme."),
        IntegrationSource(
            "aptv", "APTV'den esinlenen kanal rehberi", IntegrationKind.NATIVE,
            "M3U tvg-id, XMLTV/XMLTV.GZ EPG, Şimdi/Sonra ve kanal favorileri. APTV'nin kendisi Apple/CarPlay uygulamasıdır.",
            projectUrl = "https://aptv.app/"),
        IntegrationSource(
            "web", "AABrowser + Web Medya", IntegrationKind.EXTERNAL,
            "Media Hub çok sekmeli HTTPS tarayıcısı ve yüklüyse AABrowser'a URL gönderme. Araç ekranında ayrı test gerekir.",
            packages = listOf("com.kododake.aabrowser"),
            projectUrl = "https://github.com/kododake/AABrowser"),
        IntegrationSource(
            "screenonauto", "ScreenOnAuto", IntegrationKind.EXTERNAL,
            "Yüklüyse belgelenmiş start/stop Intent arayüzü üzerinden yansıtma isteği; telefon izni ve araç desteği ayrıca gerekir.",
            packages = listOf("idv.lzn.screenonauto"),
            projectUrl = "https://github.com/slzn/ScreenOnAuto-releases"),
        IntegrationSource(
            "fermata", "Fermata Auto", IntegrationKind.EXTERNAL,
            "Kurulu varyantı tespit etme ve telefon uygulamasını açma; CarAppService kaynak mimarisi incelendi.",
            packages = listOf("me.aap.fermata.auto", "me.aap.fermata.auto.dear.google.why", "me.aap.fermata"),
            projectUrl = "https://github.com/AndreyPavlenko/Fermata"),
        IntegrationSource(
            "fermatax", "FermataX (yüklenen debug APK)", IntegrationKind.EXTERNAL,
            "Kullanıcının 2.2.6 (332) manifestinde Android API 28–36; dışarıya açık VIEW medya etkinliği, AutoFermataMediaService, CarService ve MediaProjection bileşenleri var. Telefon TV oynatıcısından, onayla HTTPS yayını FermataX Debug'a gönderilebilir. Projeksiyon/araç videosu desteklendiği iddia edilmez.",
            packages = listOf("me.app.fermataX.auto.debug", "me.app.fermataX.auto", "me.app.fermataX"),
            projectUrl = "https://github.com/chuoinho/FermataX"),
        IntegrationSource(
            "mediaengine32", "MediaEngine 3.2 (ayrı uygulama)", IntegrationKind.EXTERNAL,
            "Sağlanan ayrı medya uygulamasının Android paket kimliği doğrulandı. " +
                "Yalnızca uygulamayı açma veya seçili HTTPS akışı kullanıcı onayıyla gönderilir. " +
                "3.2 APK'sı GitHub'daki 2.2.2 kaynak/sertifika kaydıyla eşleşmiş sayılmaz. " +
                "Araç ekranında video oynatma doğrulanmadı.",
            packages = listOf("me.app.fermataX.auto.mola32.debug"),
            projectUrl = "https://github.com/moris545454/araba-"),
        IntegrationSource(
            "screen2auto", "Screen2Auto", IntegrationKind.DIAGNOSTIC,
            "Telefon üzerindeki bilinen dağıtım paketlerini algılama; gizli API, ekran hızı veya güvenlik kilidi değişimi yok.",
            packages = listOf("android.loandamaps.it", "android.loandamaps.it.aaad_157ff3")),
        IntegrationSource(
            "shizuku", "Shizuku", IntegrationKind.DIAGNOSTIC,
            "Var olan Shizuku hizmeti ve izin durumunu yalnızca okuma; ayrıcalıklı komut gönderilmez.",
            packages = listOf("moe.shizuku.privileged.api"),
            projectUrl = "https://shizuku.rikka.app/"),
        IntegrationSource(
            "aaad", "AAAD", IntegrationKind.DIAGNOSTIC,
            "Kurulu paket sürümü kontrolü; APK indirme, kurulum ve Android Auto sahte tanıtımı yok.",
            packages = listOf("sksa.aa.customapps"),
            projectUrl = "https://github.com/shmykelsa/AAAD"),
        IntegrationSource(
            "mirrormobile", "MirrorMobile", IntegrationKind.REFERENCE,
            "MediaProjection, AndroidX Car Surface ve durum makinesi araştırıldı. Proje arşivlendi; hareket halinde yansıtmayı durdurur.",
            packages = listOf("com.chiller3.mirrormobile", "com.chiller3.mirrormobile.debug"),
            projectUrl = "https://github.com/chenxiaolong/MirrorMobile"),
        IntegrationSource(
            "carstream", "CarStream", IntegrationKind.DIAGNOSTIC,
            "AAAD ekosistemindeki eski YouTube/web görüntüleme yöntemi; Android 15 ve EV Cockpit uyumu doğrulanmadı.",
            packages = listOf("maps.jaoloonda.android")),
        IntegrationSource(
            "rectv", "REC TV (telefondaki uygulama)", IntegrationKind.EXTERNAL,
            "Kullanıcının sağladığı REC TV 22.0.2 (161) APK'sındaki com.rectv.shot " +
                "paketi yüklüyse yalnızca telefon uygulaması başlatılır. " +
                "Flutter kodu, telifli medya ve üçüncü taraf sunucular kopyalanmaz.",
            packages = listOf("com.rectv.shot")),
        IntegrationSource(
            "sportzx", "SportzX", IntegrationKind.EXTERNAL,
            "Yalnızca telefonda kurulu uygulamaya kısayol. Üçüncü taraf APK, yayın veya telifli içerik entegre edilmez.",
            packages = listOf("com.sportzx.live")),
        IntegrationSource(
            "diplay", "DiPlay 0.2.20 • bağlantı ve medya tanılama", IntegrationKind.DIAGNOSTIC,
            "Gönderilen 0.2.20 APK'sından USB, Wi-Fi Direct, hotspot, Bluetooth, " +
            "MediaCodec, ses tamponu ve yeniden deneme mimarileri incelendi. " +
            "Media Hub'a bağımsız medya tanılama ve sınırlı yeniden deneme eklendi. " +
            "DiPlay doğrudan araç ünitesine kurulan CarPlay alıcısıdır; " +
            "EV Cockpit Android Auto ekranında çalıştığı iddia edilmez.",
            packages = listOf(DiPlayCompatibility.PACKAGE),
            projectUrl = DiPlayCompatibility.REFERENCE),
        IntegrationSource(
            "androidauto", "Android Auto radyo", IntegrationKind.NATIVE,
            "MediaLibraryService / platform MediaBrowser ses kataloğu. Gerçek araç keşfi ve ses çıkışı ayrıca doğrulanmalı.",
            packages = listOf("com.google.android.projection.gearhead"))
    )

    fun byId(id: String): IntegrationSource? = sources.firstOrNull { it.id == id }
}
