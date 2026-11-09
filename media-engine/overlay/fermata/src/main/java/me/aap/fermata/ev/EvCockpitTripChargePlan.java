package me.aap.fermata.ev;

import java.time.LocalDate;

/**
 * Route-level charge and TRY scenario. NEVER a live charging-station quote.
 * Measured SoC, when available, takes precedence over the explicit 80% start
 * assumption. Uses 52 usable kWh, 16–24 kWh/100km, 15% minimum arrival,
 * and each 15% -> 80% DC stop adds at most 33.8kWh in the MODEL.
 * This counts theoretical charges, not reachable, available or booked sockets.
 */
public final class EvCockpitTripChargePlan {
    private EvCockpitTripChargePlan(){}
    public static final double ASSUMED_START_SOC=80.0;
    private static final double CHARGE_TARGET_SOC=80.0;
    private static final double MARKET_MIN_TRY_PER_KWH=7.70;
    private static final double MARKET_MAX_TRY_PER_KWH=16.49;
    private static final LocalDate MARKET_VALIDATED=LocalDate.parse("2026-10-16");
    private static final LocalDate MARKET_EXPIRES=LocalDate.parse("2026-11-16");
    public static final String MARKET_SOURCE="https://www.esarjradar.com/veri/";

    public static final class Estimate {
        public final boolean measuredSoc, priceEstimateAvailable, operatorRate;
        public final double startSoc, chargeMinKwh, chargeMaxKwh;
        public final double costMinTry,costMaxTry;
        public final int minStops,maxStops;
        public final String priceSource, priceBasis, note;
        Estimate(boolean measured,double soc,double lo,double hi,int loStops,int hiStops,
                 double minTry,double maxTry,String source,String basis,boolean operator){
            measuredSoc=measured;startSoc=soc;
            chargeMinKwh=lo;chargeMaxKwh=hi;
            minStops=loStops;maxStops=hiStops;
            costMinTry=minTry;costMaxTry=maxTry;
            priceSource=source;priceBasis=basis;operatorRate=operator;
            priceEstimateAvailable=Double.isFinite(minTry)&&Double.isFinite(maxTry);
            note=(measured?"Ölçülen başlangıç SoC'si ile model":
                "Kullanıcı seçimiyle çıkış SoC senaryosu, gerçek araç ölçümü yok")+
                "; 52 kWh kullanılabilir batarya, %15 rezerv, DC'de %80 hedef"+
                "; menzil ve istasyon erişimi kesin değildir.";
        }
    }

    public static Estimate estimate(double roadKm,double measuredSoc,
                                    String preferredOperator,LocalDate today){
        return estimate(roadKm,measuredSoc,preferredOperator,today,80);
    }
    /** User-picked initial charge when the actual ECU SoC is unavailable. */
    public static Estimate estimate(double roadKm,double measuredSoc,
                                    String preferredOperator,LocalDate today,int scenarioSoc){
        if(!Double.isFinite(roadKm)||roadKm<=0||roadKm>4000||today==null)
            throw new IllegalArgumentException("Invalid route or date");
        if(scenarioSoc!=80&&scenarioSoc!=100)
            throw new IllegalArgumentException("Choose 80 or 100 percent");
        boolean measured=Double.isFinite(measuredSoc)&&measuredSoc>=0&&measuredSoc<=100;
        double soc=measured?measuredSoc:scenarioSoc;
        double pack=EvCockpitEnergyModel.ASSUMED_USABLE_KWH;
        double reserve=EvCockpitEnergyModel.ASSUMED_RESERVE_PERCENT;
        double initialAvailable=Math.max(0,(soc-reserve)*pack/100.0);
        double lowNeed=Math.max(0,roadKm*EvCockpitEnergyModel.LOW_KWH_PER_100KM/100.0-
            initialAvailable);
        double highNeed=Math.max(0,roadKm*EvCockpitEnergyModel.HIGH_KWH_PER_100KM/100.0-
            initialAvailable);
        // This is only an energy-equivalent lower bound on the number of stops.
        // We do not assert that any physical stop is actually reachable.
        double perStop=(CHARGE_TARGET_SOC-reserve)*pack/100.0;
        int lowStops=(int)Math.ceil(Math.max(0,lowNeed-0.000001)/perStop);
        int highStops=(int)Math.ceil(Math.max(0,highNeed-0.000001)/perStop);
        double rateMin=Double.NaN,rateMax=Double.NaN;
        String source="",basis="";
        boolean network=false;
        if(highNeed<0.0001){
            rateMin=rateMax=0.0;
            basis="Şarj mediaenginesı gerekmiyor (model)";
        }else{
            EvCockpitChargeCost.Tariff networkTariff=
                EvCockpitChargeCost.publishedTariff(preferredOperator,today);
            if(networkTariff!=null){
                rateMin=networkTariff.min;rateMax=networkTariff.max;
                source=networkTariff.source;
                basis=networkTariff.operator+" yayınlanmış ağ tarifesi";
                network=true;
            }else if(!today.isBefore(MARKET_VALIDATED) &&
                     !today.isAfter(MARKET_EXPIRES)){
                // Wide public-market band. It is NOT a particular operator's
                // station/socket price and must be displayed as "pazar senaryosu".
                rateMin=MARKET_MIN_TRY_PER_KWH;
                rateMax=MARKET_MAX_TRY_PER_KWH;
                source=MARKET_SOURCE;
                basis="Türkiye AC/DC pazar aralığı (16.10.2026 verisi); istasyon fiyatı değil";
            }
        }
        return new Estimate(measured,soc,lowNeed,highNeed,lowStops,highStops,
            Double.isFinite(rateMin)?Math.round(lowNeed*rateMin*100)/100.0:Double.NaN,
            Double.isFinite(rateMax)?Math.round(highNeed*rateMax*100)/100.0:Double.NaN,
            source,basis,network);
    }
}
