import me.aap.fermata.ev.EvCockpitChargeHistory;

/** No external test framework required. Run with java -ea. */
public final class EvCockpitChargeHistoryTest {
    private static int passed=0;
    private static void check(boolean ok,String label){
        if(!ok)throw new AssertionError(label);
        passed++;
    }
    private static final long T=1_700_000_000_000L;

    public static void main(String[] args){
        normalCharge();
        unknownModeAndDebounce();
        staleTelemetry();
        invalidSamples();
        disconnectAborts();
        duplicateSessions();
        invalidSessions();
        System.out.println("EvCockpitChargeHistory: "+passed+" checks passed.");
    }

    private static void normalCharge(){
        EvCockpitChargeHistory h=new EvCockpitChargeHistory();
        h.acceptSoc(T,20);
        check(h.acceptMode(T+1000,4)==null,"start does not complete");
        check(h.isCharging(),"AC started");
        h.acceptSoc(T+65000,35);
        check(h.acceptMode(T+70000,0)==null,"first stop not enough");
        EvCockpitChargeHistory.Session s=h.acceptMode(T+80000,0);
        check(s!=null,"second stop completes");
        check("AC".equals(s.chargingMethod),"charge method AC");
        check(Math.abs(s.socChangePercent()-15.0)<0.01,"SoC delta");
        check(s.plausible(),"plausible session");
        check(!h.isCharging(),"stopped");
        check(h.completed().size()==1,"saved in memory");
    }
    private static void unknownModeAndDebounce(){
        EvCockpitChargeHistory h=new EvCockpitChargeHistory();
        h.acceptSoc(T,25);
        h.acceptMode(T+100,6);
        h.acceptSoc(T+70000,42);
        check(h.acceptMode(T+71000,-1)==null,"unsupported ignored");
        check(h.acceptMode(T+72000,0)==null,"first pause");
        check(h.acceptMode(T+73000,6)==null,"charging resumes without split");
        check(h.acceptMode(T+74000,0)==null,"first stop again");
        EvCockpitChargeHistory.Session s=h.acceptMode(T+75000,0);
        check(s!=null && "DC".equals(s.chargingMethod),"DC final session");
    }
    private static void staleTelemetry(){
        EvCockpitChargeHistory h=new EvCockpitChargeHistory();
        h.acceptSoc(T,30);
        h.acceptMode(T+500,4);
        h.acceptSoc(T+70000,44);
        check(h.acceptMode(T+14*60000,0)==null,"gap cannot finish");
        check(!h.isCharging(),"stale mode clears active state");
        check(h.completed().isEmpty(),"no false history");
        EvCockpitChargeHistory h2=new EvCockpitChargeHistory();
        h2.acceptSoc(T,30);
        h2.acceptMode(T+500,4);
        h2.acceptSoc(T+70000,40);
        h2.acceptMode(T+8*60000,0);
        check(h2.acceptMode(T+8*60000+1000,0)==null,"stale soc cannot end");
        check(h2.completed().isEmpty(),"no stale soc session");
    }
    private static void invalidSamples(){
        EvCockpitChargeHistory h=new EvCockpitChargeHistory();
        h.acceptSoc(T,Double.NaN);
        h.acceptSoc(T+100,-12);
        h.acceptSoc(T+200,107);
        h.acceptMode(T+1000,6);
        check(!h.isCharging(),"cannot start without plausible SoC");
        h.acceptSoc(T+90000,40);
        h.acceptMode(T+90001,6);
        check(h.isCharging(),"valid SoC starts");
        h.acceptSoc(T+93000,Double.POSITIVE_INFINITY);
        check(Double.isFinite(h.observedSocChange()),"invalid float ignored");
        h.acceptMode(T+200000,0);
        check(h.acceptMode(T+201000,0)==null,"no false zero-delta charge");
        check(h.completed().isEmpty(),"zero delta rejected");
    }
    private static void disconnectAborts(){
        EvCockpitChargeHistory h=new EvCockpitChargeHistory();
        h.acceptSoc(T,21);
        h.acceptMode(T+1000,4);
        h.acceptSoc(T+90000,31);
        check(h.isCharging(),"charge detected before OBD loss");
        h.telemetryLost();
        check(!h.isCharging(),"disconnect stops incomplete session");
        h.acceptMode(T+120000,0);
        check(h.acceptMode(T+121000,0)==null,"post-disconnect idle is not session end");
        check(h.completed().isEmpty(),"no fabricated disconnected session");
    }
    private static void duplicateSessions(){
        EvCockpitChargeHistory h=new EvCockpitChargeHistory();
        EvCockpitChargeHistory.Session s=new EvCockpitChargeHistory.Session(T,T+600000,11,55,"DC");
        h.importCompleted(s);h.importCompleted(s);
        check(h.completed().size()==1,"no duplicated session");
        check(h.completed().get(0).durationMinutes()==10,"duration");
        h.clear();check(h.completed().isEmpty(),"clear records");
    }
    private static void invalidSessions(){
        check(!new EvCockpitChargeHistory.Session(T,T-1,20,40,"DC").plausible(),"backwards dates");
        check(!new EvCockpitChargeHistory.Session(T,T+60000,80,20,"AC").plausible(),"negative SoC");
        check(!new EvCockpitChargeHistory.Session(T,T+60000,10,45,"???").plausible(),"unknown charging type");
        check(!new EvCockpitChargeHistory.Session(T,T+37*3600000L,10,45,"AC").plausible(),"implausible length");
    }
}
