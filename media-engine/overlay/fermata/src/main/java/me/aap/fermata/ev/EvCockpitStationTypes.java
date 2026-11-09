package me.aap.fermata.ev;

import java.text.Normalizer;
import java.util.Locale;

/** Pure string/socket classifier for EPDK snapshot. No invented live occupancy. */
final class EvCockpitStationTypes {
    private EvCockpitStationTypes() {}

    static String searchable(String raw) {
        if (raw == null) return "";
        String lower = raw.toLowerCase(Locale.forLanguageTag("tr-TR"))
            .replace('ı', 'i');
        return Normalizer.normalize(lower, Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "");
    }
    static boolean dc(String socketType, String current) {
        String kind = searchable(current).toUpperCase(Locale.ROOT);
        String type = searchable(socketType).toUpperCase(Locale.ROOT);
        return kind.equals("DC") || kind.startsWith("DC ") ||
            type.contains("CCS") || type.contains("COMBO") ||
            type.contains("CHADEMO") || type.equals("DC") ||
            type.startsWith("DC ");
    }
    static boolean ac(String socketType, String current) {
        if (dc(socketType,current)) return false;
        String kind=searchable(current).toUpperCase(Locale.ROOT);
        String type=searchable(socketType).toUpperCase(Locale.ROOT);
        return kind.equals("AC") || kind.startsWith("AC ") ||
            type.contains("TYPE 2") || type.contains("TYPE2") ||
            type.contains("MENNEKES") || type.equals("AC") ||
            type.startsWith("AC ");
    }
}
