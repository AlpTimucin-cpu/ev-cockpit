package me.aap.fermata.ev;

/**
 * Conservative, transparent route charge-stop prefilter. All numbers are a
 * VEHICLE PROFILE MODEL (not BMS measured consumption or actual drive range).
 * A station is never claimed to be available/reachable on real roads:
 * connector availability, actual detour, gradients and weather are unknown.
 */
public final class EvCockpitChargeAdvisor {
    private EvCockpitChargeAdvisor(){}
    public static final double RESERVE_SOC=EvCockpitEnergyModel.ASSUMED_RESERVE_PERCENT;
    // Straight-line lateral offset can underestimate road travel severely.
    // Keep an extra 10-km *model* margin and double the lateral approximation.
    public static final double EXTRA_BUFFER_KM=10.0;
    public static final class Assessment {
        public final boolean hasSoc, chargeLikely, modelCandidate;
        public final double conservativeRangeKm, targetStopKm;
        public final double projectedArrivalSocLower, projectedArrivalSocUpper;
        public final double stationProgressKm, offRouteKm, score;
        Assessment(boolean hasSoc,boolean need,boolean candidate,
                   double rangeKm,double targetKm,double low,double high,
                   double progress,double lateral,double score){
            this.hasSoc=hasSoc;
            chargeLikely=need;
            modelCandidate=candidate;
            conservativeRangeKm=rangeKm;
            targetStopKm=targetKm;
            projectedArrivalSocLower=low;
            projectedArrivalSocUpper=high;
            stationProgressKm=progress;
            offRouteKm=lateral;
            this.score=score;
        }
    }
    public static Assessment assess(double routeKm,double soc,
                                    double alongKm,double offRouteKm){
        if(!Double.isFinite(routeKm)||routeKm<=0 ||
           !Double.isFinite(alongKm)||alongKm<0||alongKm>routeKm ||
           !Double.isFinite(offRouteKm)||offRouteKm<0)
            throw new IllegalArgumentException("Invalid route candidate");
        boolean freshSoc=Double.isFinite(soc)&&soc>=0&&soc<=100;
        if(!freshSoc){
            return new Assessment(false,false,false,Double.NaN,Double.NaN,
                Double.NaN,Double.NaN,alongKm,offRouteKm,Double.POSITIVE_INFINITY);
        }
        double availPercent=Math.max(0,soc-RESERVE_SOC);
        double worstKm=availPercent*EvCockpitEnergyModel.ASSUMED_USABLE_KWH /
            EvCockpitEnergyModel.HIGH_KWH_PER_100KM;
        double bestKm=availPercent*EvCockpitEnergyModel.ASSUMED_USABLE_KWH /
            EvCockpitEnergyModel.LOW_KWH_PER_100KM;
        // Model "charge likely" is triggered when worst-case profile falls below reserve.
        boolean needed=routeKm>worstKm;
        double stationDist=alongKm+2*offRouteKm+EXTRA_BUFFER_KM;
        boolean candidate=needed && stationDist<=worstKm && alongKm>0;
        // Prefer a stop ~70% into the conservative reserve-aware range,
        // with a penalty for stops whose lateral offset suggests a detour.
        double targetKm=Math.min(routeKm*.85,worstKm*.70);
        double score=candidate?
            Math.abs(alongKm-targetKm)+4.0*offRouteKm:
            Double.POSITIVE_INFINITY;
        double low=soc-EvCockpitEnergyModel.HIGH_KWH_PER_100KM/100.0*
            stationDist/EvCockpitEnergyModel.ASSUMED_USABLE_KWH*100.0;
        double high=soc-EvCockpitEnergyModel.LOW_KWH_PER_100KM/100.0*
            stationDist/EvCockpitEnergyModel.ASSUMED_USABLE_KWH*100.0;
        return new Assessment(true,needed,candidate,worstKm,targetKm,
            low,high,alongKm,offRouteKm,score);
    }
}
