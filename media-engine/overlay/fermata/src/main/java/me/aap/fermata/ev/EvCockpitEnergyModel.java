package me.aap.fermata.ev;

/** Explicitly modelled, NOT measured, estimates for VW EV Cockpit Pure.
 * Do not interpret this as Car Scanner consumption, SOH or a battery gauge.
 * Battery reference: 52 usable kWh is a profile ASSUMPTION until VIN validated.
 * Consumption reference: deliberately broad 16..24 kWh/100km model band.
 */
public final class EvCockpitEnergyModel {
    public static final double ASSUMED_USABLE_KWH=52;
    public static final double LOW_KWH_PER_100KM=16;
    public static final double HIGH_KWH_PER_100KM=24;
    public static final double ASSUMED_RESERVE_PERCENT=15;

    private EvCockpitEnergyModel(){}

    public static Result estimate(double routeKm,double verifiedSoc){
        if(!Double.isFinite(routeKm)||routeKm<=0||routeKm>4000)return null;
        double low=routeKm*LOW_KWH_PER_100KM/100.0;
        double high=routeKm*HIGH_KWH_PER_100KM/100.0;
        boolean hasSoc=Double.isFinite(verifiedSoc)&&verifiedSoc>=0&&verifiedSoc<=100;
        double available=hasSoc?Math.max(0,(verifiedSoc-ASSUMED_RESERVE_PERCENT)*
            ASSUMED_USABLE_KWH/100.0):Double.NaN;
        double deficitLow=hasSoc?Math.max(0,low-available):Double.NaN;
        double deficitHigh=hasSoc?Math.max(0,high-available):Double.NaN;
        return new Result(low,high,deficitLow,deficitHigh,hasSoc);
    }
    public static final class Result {
        public final double lowRouteKwh,highRouteKwh,lowRequiredChargeKwh,highRequiredChargeKwh;
        public final boolean hasSoc;
        Result(double low,double high,double lowCharge,double highCharge,boolean hasSoc){
            this.lowRouteKwh=low;this.highRouteKwh=high;
            this.lowRequiredChargeKwh=lowCharge;this.highRequiredChargeKwh=highCharge;
            this.hasSoc=hasSoc;
        }
        /** Published operator price only; excludes losses, station-specific fees. */
        public double[] indicativeCost(double minTlPerKwh,double maxTlPerKwh){
            if(!hasSoc||!Double.isFinite(minTlPerKwh)||!Double.isFinite(maxTlPerKwh)||
                minTlPerKwh<=0||maxTlPerKwh<minTlPerKwh)return null;
            return new double[]{
                lowRequiredChargeKwh*minTlPerKwh,
                highRequiredChargeKwh*maxTlPerKwh};
        }
    }
}
