"""Phase 9 regression gate for real ELM327 transport and diagnostic safety.

Static checks complement JUnit; hardware still requires a parked-car UAT.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "nativecockpit/src/main/java/com/evcockpit"


def read(relative):
    return (SRC / relative).read_text(encoding="utf-8")


class Phase9ObdSafetyTest(unittest.TestCase):
    def test_no_stale_speed_after_bad_pid_or_disconnect(self):
        repo = read("data/repository/Elm327VehicleRepository.kt")
        state = read("domain/model/ObdConnectionState.kt")
        assert "lastSpeedKmh: Double? = null" in state
        assert "private fun clearSpeedObservation()" in repo
        assert "_engineering.value.copy(speedKmh = TimedValue())" in repo
        assert "lastSpeedAtMs = null, lastSpeedKmh = null" in repo
        assert "clearSpeedObservation()" in repo
        assert "lastSpeedKmh = speed" in repo

    def test_manual_raw_dids_require_recent_stationary_obd_measurement(self):
        repo = read("data/repository/Elm327VehicleRepository.kt")
        ui = read("presentation/obd/ObdSettingsDialog.kt")
        guard = read("domain/usecase/ObdDiagnosticSafetyPolicy.kt")
        assert "ObdDiagnosticSafetyPolicy.canRequest(" in repo
        assert "ObdDiagnosticSafetyPolicy.canExecute(" in repo
        assert "ObdDiagnosticSafetyPolicy.canRequest(" in ui
        assert "enabled=manualDiagnosticReady" in ui
        assert "MAX_SPEED_AGE_MS = 3_000L" in guard
        assert "lastSpeedKmh" in guard
        assert "state.lastPollSucceeded" in guard

    def test_serial_boundary_and_reset_fail_closed(self):
        repo = read("data/repository/Elm327VehicleRepository.kt")
        wire = read("data/obd/ObdWireCommandPolicy.kt")
        restorer = read("data/obd/ObdProtocolRestorer.kt")
        assert "require(ObdWireCommandPolicy.allowed(command))" in repo
        assert "ObdWireCommandPolicy.acknowledged(adapterCommand, reply)" in repo
        assert "ObdProtocolRestorer.restore(" in repo
        assert "catch (fatal: ObdProtocolRestoreException)" in repo
        assert "throw fatal" in repo
        assert "ATFCSM0" in restorer and "ATCRA" in restorer
        assert "close()" in restorer
        assert "MebUdsParser.ALLOWED_REQUESTS" in wire

    def test_bms_remains_raw_only(self):
        repo = read("data/repository/Elm327VehicleRepository.kt")
        parser = read("data/obd/MebUdsParser.kt")
        assert 'protocol.readRawOnce("010D")' in repo
        assert "MebUdsParser.parse(" in repo
        assert "return result(MebResponseStatus.VALID_RAW, bytes)" in parser
        assert "socEvidence" not in repo
        assert "EngineeringMath.update(" not in repo


if __name__ == "__main__":
    unittest.main()
