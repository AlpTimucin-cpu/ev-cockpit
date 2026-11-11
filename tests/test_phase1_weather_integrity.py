"""Phase 1 integrity checks. Source assertions are not evidence of live network success."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "nativecockpit/src/main/java/com/evcockpit"

def source(path):
    return (BASE / path).read_text(encoding="utf-8")

class WeatherIntegrityTest(unittest.TestCase):
    def test_open_meteo_utc_current_and_max_eight_route_stops(self):
        repo = source("data/weather/OpenMeteoWeatherRepository.kt")
        current = source("data/weather/CurrentWeatherParser.kt")
        route = source("domain/usecase/RouteForecastPolicy.kt")
        self.assertIn('timeFormat: String = "unixtime"', repo)
        self.assertIn('timezone: String = "GMT"', repo)
        self.assertIn("listOf(actualGpsCoordinate) + sites.map { it.coordinate }", repo)
        self.assertIn("CurrentWeatherParser.match(it, actualGpsCoordinate, receivedAt)", repo)
        self.assertIn("objects.size != allCoordinates.size", repo)
        self.assertIn("RouteForecastPolicy.sample(route, now)", repo)
        self.assertIn("private const val MAX_STOPS = 8", route)
        self.assertIn("Instant.ofEpochSecond(validSeconds)", current)
        self.assertIn("if (abs(nowMs - validMs) >", current)
        self.assertNotIn("current_weather", repo)

    def test_foreground_cadence_diagnostics_and_real_gps(self):
        vm = source("presentation/weather/RouteWeatherViewModel.kt")
        car = source("car/EyaCarNavigationScreen.kt")
        hub = source("diagnostics/DiagnosticsHub.kt")
        self.assertIn(".distinctUntilChangedBy { it?.routeId }", vm)
        self.assertIn("30L * 60_000L", vm)
        self.assertIn("SharingStarted.WhileSubscribed", vm)
        self.assertIn("weather.routeWeather(projected, fix.coordinate, manual = manual)", vm)
        self.assertIn("weather.routeWeather(matched, fix.coordinate, manual = manual)", car)
        self.assertIn('setTitle("Havayı yenile")', car)
        self.assertIn("maybeRefreshWeather(route, fix, System.currentTimeMillis(), manual = true)", car)
        self.assertIn("weatherAttemptMillis = atMillis", hub)
        self.assertNotIn("ACCESS_BACKGROUND_LOCATION", vm)

    def test_phone_and_car_keep_source_and_template_limits(self):
        cockpit = source("presentation/cockpit/CockpitScreen.kt")
        sheet = source("presentation/weather/WeatherDetailSheet.kt")
        car = source("car/CarWeatherPresentationPolicy.kt")
        self.assertIn("WeatherDetailSheet(", cockpit)
        self.assertIn("© Open-Meteo", sheet)
        self.assertIn("MODEL/TAHMİN", sheet)
        self.assertIn("atLocalTime(point.forecastEtaEpochMs)", sheet)
        self.assertIn("state.currentAgeMinutes(nowMs)", sheet)
        self.assertIn("take((hostLimit - rows.size).coerceAtLeast(0))", car)
        self.assertNotIn("WebView", car)
        self.assertNotIn("ExoPlayer", car)

if __name__ == "__main__":
    unittest.main()
