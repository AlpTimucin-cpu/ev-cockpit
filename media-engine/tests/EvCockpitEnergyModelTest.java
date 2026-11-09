import me.aap.fermata.ev.EvCockpitEnergyModel;

/** Standalone regression tests: no Android device required. */
public final class EvCockpitEnergyModelTest {
    private static int n;
    private static void ok(boolean check,String message){
        if(!check)throw new AssertionError(message);
        n++;
    }
    public static void main(String[] args){
        ok(EvCockpitEnergyModel.estimate(Double.NaN,60)==null,"no route");
        ok(EvCockpitEnergyModel.estimate(-1,60)==null,"negative route");
        ok(EvCockpitEnergyModel.estimate(5000,60)==null,"implausible route");
        var noSoc=EvCockpitEnergyModel.estimate(210,Double.NaN);
        ok(noSoc!=null&&!noSoc.hasSoc,"no fake OBD");
        ok(Double.isNaN(noSoc.lowRequiredChargeKwh),"no charged kWh without verified soc");
        ok(noSoc.indicativeCost(12,15)==null,"no billing without soc");
        var route=EvCockpitEnergyModel.estimate(210,65);
        ok(route.hasSoc,"valid soc");
        ok(Math.abs(route.lowRouteKwh-33.6)<0.001,"low energy");
        ok(Math.abs(route.highRouteKwh-50.4)<0.001,"high energy");
        ok(Math.abs(route.lowRequiredChargeKwh-7.6)<0.001,"reserve deficit");
        ok(Math.abs(route.highRequiredChargeKwh-24.4)<0.001,"reserve high");
        ok(route.indicativeCost(Double.NaN,17)==null,"invalid tariff");
        ok(route.indicativeCost(18,15)==null,"inverted tariff");
        double[] cost=route.indicativeCost(12,15);
        ok(cost.length==2&&cost[0]>0&&cost[1]>=cost[0],"cost range");
        var full=EvCockpitEnergyModel.estimate(5,100);
        ok(full.lowRequiredChargeKwh==0&&full.highRequiredChargeKwh==0,
            "full battery short drive no additional charge");
        ok(EvCockpitEnergyModel.estimate(30,101).hasSoc==false,"invalid high soc");
        ok(EvCockpitEnergyModel.estimate(30,-1).hasSoc==false,"invalid negative soc");
        System.out.println("PASS "+n+" automatic energy model tests");
    }
}
