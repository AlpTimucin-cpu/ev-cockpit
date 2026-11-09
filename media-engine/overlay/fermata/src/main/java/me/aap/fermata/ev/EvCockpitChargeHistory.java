package me.aap.fermata.ev;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Local, read-only charge-session detector for electric vehicle.
 *
 * Inspired by charging-session analysis patterns in the GPL-3.0
 * IDDataLogger and MIT VW-Dash projects; independently implemented.
 *
 * Samples must be from a verified, time-stamped BMS measurement.
 * The class NEVER claims measured delivered kWh or a reliable SoH.
 * A charging session ends only after two consecutive non-charging
 * mode readings; short link outages do not create fake sessions.
 */
public final class EvCockpitChargeHistory {
    public static final class Session {
        public final long startedAtMs;
        public final long endedAtMs;
        public final double socStart;
        public final double socEnd;
        public final String chargingMethod;

        public Session(long startedAtMs,long endedAtMs,
                       double socStart,double socEnd,String chargingMethod) {
            this.startedAtMs=startedAtMs;
            this.endedAtMs=endedAtMs;
            this.socStart=socStart;
            this.socEnd=socEnd;
            this.chargingMethod=chargingMethod;
        }
        public double socChangePercent() {return socEnd-socStart;}
        public long durationMinutes() {return (endedAtMs-startedAtMs)/60000;}
        public boolean plausible() {
            return startedAtMs>0 && endedAtMs>startedAtMs &&
                endedAtMs-startedAtMs <= 36L*3600000 &&
                Double.isFinite(socStart) && Double.isFinite(socEnd) &&
                socStart>=0 && socStart<=100 && socEnd>=socStart &&
                socEnd<=100 && socEnd-socStart<=100 &&
                ("AC".equals(chargingMethod)||"DC".equals(chargingMethod));
        }
    }

    private static final long MAX_SOC_AGE_MS=300000L;
    private static final long MAX_MODE_GAP_MS=720000L;
    private static final long MIN_DURATION_MS=60000L;
    private final List<Session> sessions=new ArrayList<>();
    private long lastSocAt=-1, lastModeAt=-1, startAt=-1;
    private double lastSoc=Double.NaN, startSoc=Double.NaN, endSoc=Double.NaN;
    private int nonChargingPolls;
    private String method;
    private boolean charging;

    public synchronized void acceptSoc(long ms,double soc) {
        if(ms<=lastSocAt || !Double.isFinite(soc) || soc<0 || soc>100)return;
        lastSocAt=ms;lastSoc=soc;
        if(charging && ms>=startAt && soc>=startSoc-1.0){
            endSoc=Math.max(startSoc,soc);
        }
    }

    /**
     * mode 4=AC, 6=DC, 1=driving, 0=idle (per public VW MEB community catalog).
     * Missing and unknown statuses must be passed as -1, not assumed idle.
     * Returns a newly completed session or null.
     */
    public synchronized Session acceptMode(long ms,int mode) {
        if(ms<=lastModeAt || ms<=0)return null;
        if(charging && lastModeAt>0 && ms-lastModeAt>MAX_MODE_GAP_MS){
            // Missing telemetry for too long: do not invent a session end.
            charging=false;nonChargingPolls=0;reset();
        }
        lastModeAt=ms;
        final boolean isCharge=(mode==4||mode==6);
        if(isCharge){
            nonChargingPolls=0;
            if(!charging && lastSocAt>0 && ms>=lastSocAt &&
               ms-lastSocAt<=MAX_SOC_AGE_MS) {
                charging=true;startAt=ms;startSoc=lastSoc;endSoc=lastSoc;
                method=mode==4?"AC":"DC";
            }else if(charging && mode==6)method="DC";
            return null;
        }
        if(mode!=0 && mode!=1)return null; // Ignore unrecognised ECU response.
        if(!charging)return null;
        if(++nonChargingPolls<2)return null;
        charging=false;nonChargingPolls=0;
        if(startAt<0 || ms<=startAt || ms-startAt<MIN_DURATION_MS ||
           lastSocAt<startAt || ms-lastSocAt>MAX_SOC_AGE_MS ||
           !Double.isFinite(startSoc) || !Double.isFinite(endSoc) ||
           endSoc-startSoc<0.2) {
            reset();
            return null;
        }
        Session session=new Session(startAt,ms,startSoc,endSoc,method);
        reset();
        if(!session.plausible())return null;
        sessions.add(0,session);
        if(sessions.size()>30)sessions.remove(sessions.size()-1);
        return session;
    }

    /** Abort an unverified partial session on a physical OBD disconnect. */
    public synchronized void telemetryLost(){
        charging=false;
        nonChargingPolls=0;
        lastModeAt=-1;
        lastSocAt=-1;
        lastSoc=Double.NaN;
        reset();
    }
    public synchronized boolean isCharging() {return charging;}
    public synchronized double observedSocChange() {
        if(!charging || !Double.isFinite(startSoc) || !Double.isFinite(endSoc))
            return Double.NaN;
        return Math.max(0,endSoc-startSoc);
    }
    public synchronized String method(){return method;}
    public synchronized List<Session> completed() {
        return Collections.unmodifiableList(new ArrayList<>(sessions));
    }
    public synchronized void importCompleted(Session session){
        if(session==null || !session.plausible())return;
        for(Session old:sessions){
            if(old.startedAtMs==session.startedAtMs)return;
        }
        sessions.add(session);
        sessions.sort((a,b)->Long.compare(b.endedAtMs,a.endedAtMs));
        while(sessions.size()>30)sessions.remove(sessions.size()-1);
    }
    public synchronized void clear(){sessions.clear();}
    private void reset(){
        startAt=-1;startSoc=Double.NaN;endSoc=Double.NaN;method=null;
    }
}
