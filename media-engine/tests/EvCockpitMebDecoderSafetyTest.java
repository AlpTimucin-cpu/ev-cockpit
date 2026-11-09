import me.aap.fermata.ev.EvCockpitMebDecoder;

/** Pure-Java regression; never substitute canned MEB values for vehicle validation. */
public final class EvCockpitMebDecoderSafetyTest {
    public static void main(String[] args){
        if(EvCockpitMebDecoder.isVehicleProfileValidated())
            throw new AssertionError("Vehicle calibration must not be enabled without VIN/Car Scanner verification");
        String positive="7E8 04 62 02 8C 96 >";
        if(!EvCockpitMebDecoder.hasPositiveReply("028C",positive))
            throw new AssertionError("DID acknowledgement must remain inspectable");
        if(EvCockpitMebDecoder.hasPositiveReply("028C","NO DATA >"))
            throw new AssertionError("NO DATA is not a positive reply");
        if(EvCockpitMebDecoder.hasPositiveReply("028C","7F 22 31 >"))
            throw new AssertionError("UDS negative is not a positive reply");
        if(EvCockpitMebDecoder.bmsSoc(positive)!=null)
            throw new AssertionError("Unvalidated SoC must never enter trip planner");
        if(EvCockpitMebDecoder.chargeModeCode("62 74 48 04")!=null)
            throw new AssertionError("Unvalidated charge mode must not create sessions");
        for(EvCockpitMebDecoder.Sensor sensor:EvCockpitMebDecoder.SENSORS){
            if(EvCockpitMebDecoder.decode(sensor,"62 "+sensor.did+" FF FF FF FF")!=null)
                throw new AssertionError("Unvalidated telemetry shown: "+sensor.did);
        }
        System.out.println("PASS: ELM327 raw handshake is diagnostic only; unvalidated values blocked");
    }
}
