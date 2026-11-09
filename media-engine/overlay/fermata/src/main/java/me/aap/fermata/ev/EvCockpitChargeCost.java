package me.aap.fermata.ev;

import java.time.LocalDate;
import java.util.Locale;

/**
 * Route-energy and PUBLIC NETWORK TARIFF estimate, never a charging invoice.
 * Known tariffs are dated, intentionally expire, and are network-level only.
 * Station/socket prices, waiting fees, card discounts and car charging losses
 * are unknown. No manual price or battery input is requested.
 */
final class EvCockpitChargeCost {
    private EvCockpitChargeCost(){}
    static final double PACK_KWH=52.0, RESERVE_PERCENT=15.0;
    static final String VERIFIED="2026-10-22";
    static final LocalDate EXPIRES=LocalDate.parse("2026-11-05");

    static final class Tariff {
        final String operator,source;
        final double min,max;
        Tariff(String operator,double min,double max,String source) {
            this.operator=operator;this.min=min;this.max=max;this.source=source;
        }
    }
    static final class Estimate {
        final double purchasedLowKwh,purchasedHighKwh,minTry,maxTry;
        final boolean socKnown, needsStop, tariffKnown;
        final String operator,tariffSource;
        Estimate(double lo,double hi,boolean soc,boolean stop,Tariff t){
            purchasedLowKwh=lo;purchasedHighKwh=hi;
            socKnown=soc;needsStop=stop;tariffKnown=t!=null;
            operator=t!=null?t.operator:"";
            tariffSource=t!=null?t.source:"";
            minTry=t==null?Double.NaN:Math.round(lo*t.min*100)/100.0;
            maxTry=t==null?Double.NaN:Math.round(hi*t.max*100)/100.0;
        }
    }
    static Tariff publishedTariff(String name,LocalDate today){
        if(name==null||today.isAfter(EXPIRES))return null;
        String normalized=name.toLowerCase(new Locale("tr","TR"))
            .replace('ı','i').replace('ş','s').replace('ğ','g');
        if(normalized.contains("trugo"))
            return new Tariff("Trugo",14.98,14.98,
                "https://www.trugo.com.tr/price");
        if(normalized.contains("zes")||normalized.contains("zorlu enerji"))
            return new Tariff("ZES",12.99,16.49,
                "https://zes.net/tr/fiyatlandirma");
        return null;
    }
    static Estimate estimate(double roadKm,double soc,String operator,
                             LocalDate today){
        if(!Double.isFinite(roadKm)||roadKm<=0||roadKm>5000)
            throw new IllegalArgumentException("Geçersiz güzergah uzunluğu");
        boolean known=Double.isFinite(soc)&&soc>=0&&soc<=100;
        if(!known)return new Estimate(Double.NaN,Double.NaN,false,false,null);
        double batteryKwh=soc*PACK_KWH/100.0;
        double reserve=RESERVE_PERCENT*PACK_KWH/100.0;
        double lo=Math.max(0,roadKm*.16+reserve-batteryKwh);
        double hi=Math.max(0,roadKm*.24+reserve-batteryKwh);
        boolean needs=hi>0.01;
        Tariff t=needs?publishedTariff(operator,today):null;
        return new Estimate(lo,hi,true,needs,t);
    }
}
