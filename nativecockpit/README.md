# EV Cockpit — Native Android

The `:nativecockpit` module is a Kotlin/Compose application with MapLibre navigation, real Fused Location Provider GPS, read-only ELM327 Bluetooth Classic SPP telemetry, Android Auto navigation, radio, and optional in-cabin active audio.

Vehicle measurements require a paired physical adapter. Missing GPS or OBD values remain unavailable; the application does not generate fake positions, simulated vehicle speeds, or default BMS charge values.

The **release** build uses R8 minification and resource shrinking. Signed installation requires the owner's existing keystore; an unsigned R8 task is used for automated shrinker verification.

```bash
gradle :nativecockpit:testDebugUnitTest
gradle :nativecockpit:assembleDebug
gradle :nativecockpit:minifyReleaseWithR8
```

The integration is not yet production-certified on a physical vehicle. GPS/OBD connections, Android Auto, audio routing, media playback, and signed release startup must be tested on real hardware.
