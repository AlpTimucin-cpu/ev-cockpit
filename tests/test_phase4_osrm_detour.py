"""Phase 4 source gates: private OSRM route provenance and bounded charging evidence."""
from pathlib import Path
import unittest
SRC = Path(__file__).resolve().parents[1] / "nativecockpit/src/main/java/com/evcockpit"
class Phase4DetourTest(unittest.TestCase):
    def text(self, file):
        return (SRC / file).read_text(encoding="utf-8")
    def test_route_source_and_request_bound(self):
        route = self.text("domain/model/RouteSnapshot.kt")
        source = self.text("data/repository/OsrmRouteRepository.kt")
        verify = self.text("data/repository/OsrmRoadDetourRepository.kt")
        assert "osrmBaseUrl: String?" in route
        assert "osrmBaseUrl = root" in source
        assert "sourceMatches(route, endpoint)" in verify
        assert "route.osrmBaseUrl == endpoint.baseUrl" in verify
        assert "verificationMutex.withLock" in verify
        assert "cachedAtMs" in verify
        assert "selectCandidates(route, candidates)" in verify
        assert "api.route(request)" in verify  # read-only OSRM call; no RouteRepository mutation
    def test_android_auto_epdk_age_and_ends_snapping(self):
        aa = self.text("car/EyaCarNavigationScreen.kt")
        planner = self.text("presentation/charging/RouteChargePlanViewModel.kt")
        parser = self.text("data/routing/OsrmDetourResponseParser.kt")
        assert "EpdkSourceAgePolicy.assess(chargingCatalog.verifiedAtUtc).needsCaution" in aa
        assert "sameOsrmOrigin" in planner
        assert "listOf(0, 2)" in parser
        assert "0.0..500.0" in parser
if __name__ == "__main__":
    unittest.main()
