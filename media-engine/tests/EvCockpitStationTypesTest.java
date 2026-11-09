package me.aap.fermata.ev;

/** EPDK TYPE2/CCS/CHAdeMO and Turkish search; no platform dependency. */
public final class EvCockpitStationTypesTest {
    private static void yes(boolean value) { if(!value) throw new AssertionError("Expected true"); }
    private static void no(boolean value) { if(value) throw new AssertionError("Expected false"); }
    public static void main(String[] args) {
        yes(EvCockpitStationTypes.ac("Type 2", "AC"));
        yes(EvCockpitStationTypes.ac("TYPE2", ""));
        yes(EvCockpitStationTypes.dc("CCS 2", "DC"));
        yes(EvCockpitStationTypes.dc("CHAdeMO", "DC"));
        yes(EvCockpitStationTypes.dc("Combo Type 2","DC"));
        no(EvCockpitStationTypes.ac("Combo Type 2", "DC"));
        no(EvCockpitStationTypes.dc("Type 2","AC"));
        no(EvCockpitStationTypes.ac("",""));
        no(EvCockpitStationTypes.dc("",""));
        yes(EvCockpitStationTypes.searchable("NİĞDE").equals("nigde"));
        yes(EvCockpitStationTypes.searchable("Çeşme Şarj İstasyonu").equals("cesme sarj istasyonu"));
        System.out.println("PASS EPDK AC/DC classifier, CCS precedence and Turkish search");
    }
}
