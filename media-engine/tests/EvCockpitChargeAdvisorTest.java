import me.aap.fermata.ev.EvCockpitChargeAdvisor;

/** Model-only tests: no Android runtime, keys or live vehicle are required. */
public final class EvCockpitChargeAdvisorTest {
    private static int tests;
    private static void check(boolean ok,String msg){
        if(!ok)throw new AssertionError(msg);
        tests++;
    }
    public static void main(String[] args){
        var plausible=EvCockpitChargeAdvisor.assess(210,65,75,1);
        check(plausible.hasSoc,"verified SoC needed");
        check(plausible.chargeLikely,"210km trip with 65% should suggest charging in worst profile");
        check(plausible.modelCandidate,"75km CCS can be model candidate");
        check(plausible.conservativeRangeKm>108&&plausible.conservativeRangeKm<109,
            "reserve-aware worst-band range");
        check(plausible.projectedArrivalSocLower<plausible.projectedArrivalSocUpper,
            "SoC interval ordered");
        var late=EvCockpitChargeAdvisor.assess(210,65,120,1);
        check(!late.modelCandidate,"late station cannot be proposed as reachable");
        var farFromRoad=EvCockpitChargeAdvisor.assess(210,65,90,12);
        check(!farFromRoad.modelCandidate,"lateral offset+reserve must be considered");
        var unknown=EvCockpitChargeAdvisor.assess(210,Double.NaN,75,1);
        check(!unknown.hasSoc&&!unknown.modelCandidate&&!unknown.chargeLikely,
            "never recommend when recent BMS SoC is absent");
        check(Double.isNaN(unknown.projectedArrivalSocLower),
            "no fabricated SoC projection");
        var shortTrip=EvCockpitChargeAdvisor.assess(40,65,20,0);
        check(!shortTrip.chargeLikely&&!shortTrip.modelCandidate,
            "do not recommend charging for short feasible trip");
        var emptyBattery=EvCockpitChargeAdvisor.assess(210,15,10,0);
        check(emptyBattery.chargeLikely&&!emptyBattery.modelCandidate,
            "reserve-only battery should not promise reachable station");
        check(EvCockpitChargeAdvisor.assess(210,65,75,1).score<
              EvCockpitChargeAdvisor.assess(210,65,30,1).score,
            "prefer reasonable charging progress");
        boolean invalid=false;
        try{EvCockpitChargeAdvisor.assess(-20,65,10,0);}catch(IllegalArgumentException e){invalid=true;}
        check(invalid,"invalid route must be rejected");
        System.out.println("PASS "+tests+" reserve-aware charge suggestion tests");
    }
}
