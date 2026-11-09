package me.aap.fermata.ev;

import java.time.LocalDate;

/** Offline regression: honest charge-stop and market-price scenarios. */
public final class EvCockpitTripChargePlanTest {
    private static void check(boolean ok,String msg){
        if(!ok)throw new AssertionError(msg);
    }
    public static void main(String[] args){
        LocalDate today=LocalDate.parse("2026-10-25");
        var unknown=EvCockpitTripChargePlan.estimate(525,Double.NaN,"",today);
        check(!unknown.measuredSoc&&unknown.startSoc==80,"unknown OBD is 80% scenario");
        check(unknown.minStops==2&&unknown.maxStops==3,
            "525km / 52kWh must require 2-3 model charging stops at 80% start");
        check(unknown.priceEstimateAvailable && !unknown.operatorRate,
            "market reference may be used only as indicative scenario");
        check(unknown.costMinTry>380&&unknown.costMaxTry>1500,
            "52+ kWh purchased and model market cost on 525km");
        var full=EvCockpitTripChargePlan.estimate(525,Double.NaN,"",today,100);
        check(!full.measuredSoc && full.startSoc==100,
            "100% initial energy must be a selected scenario, not measured SoC");
        check(full.chargeMinKwh<unknown.chargeMinKwh &&
              full.chargeMaxKwh<unknown.chargeMaxKwh,
            "100% choice must reduce purchase energy compared with 80%");
        check(full.costMinTry<unknown.costMinTry &&
              full.costMaxTry<unknown.costMaxTry,
            "100% scenario must reduce indicative TL charges");
        var verifiedOverride=EvCockpitTripChargePlan.estimate(525,61,"",today,100);
        check(verifiedOverride.measuredSoc && verifiedOverride.startSoc==61,
            "real measured SoC always overrides a selected 100% assumption");
        boolean rejectsUnknown=false;
        try{EvCockpitTripChargePlan.estimate(525,Double.NaN,"",today,90);}
        catch(IllegalArgumentException e){rejectsUnknown=true;}
        check(rejectsUnknown,"only 80 and 100 are valid scenario choices");
        var measured=EvCockpitTripChargePlan.estimate(525,40,"",today);
        check(measured.measuredSoc&&measured.startSoc==40,"fresh SoC takes precedence");
        check(measured.minStops==3&&measured.maxStops==4,
            "lower real starting SoC should increase model stop count");
        var quick=EvCockpitTripChargePlan.estimate(12,80,"",today);
        check(quick.minStops==0&&quick.maxStops==0&&
              quick.costMinTry==0&&quick.costMaxTry==0,
              "short trip has no estimated public charge purchase");
        var trugo=EvCockpitTripChargePlan.estimate(525,80,"TRUGO",today);
        check(trugo.operatorRate&&trugo.priceEstimateAvailable&&
              trugo.priceBasis.contains("Trugo"),
              "published known operator should supersede market band");
        var expired=EvCockpitTripChargePlan.estimate(525,Double.NaN,"",LocalDate.parse("2026-12-01"));
        check(!expired.priceEstimateAvailable,
              "dated market band must expire instead of impersonating real prices");
        boolean rejected=false;
        try{EvCockpitTripChargePlan.estimate(-5,80,"",today);}
        catch(IllegalArgumentException e){rejected=true;}
        check(rejected,"invalid road rejected");
        System.out.println("PASS: charge count, measured/unknown SoC, dated market price and operator tariff");
    }
}
