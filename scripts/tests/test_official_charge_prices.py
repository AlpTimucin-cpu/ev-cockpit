import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("official_prices",
    ROOT / "scripts" / "update_official_charge_prices.py")
prices = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prices)


class OfficialChargePricesTest(unittest.TestCase):
    def test_trugo_explicit_ac_dc(self):
        page = """
        <h1>Fiyatlandırma</h1>
        <h3>DC cihazlar için</h3><p>1 kWh ücreti</p><span>14,98 TL</span>
        <h3>AC cihazlar için</h3><p>1 kWh ücreti</p><span>9,95 TL</span>
        """
        self.assertEqual({"AC": 9.95, "DC": 14.98}, prices.parse_trugo(page))

    def test_trugo_english_range_preserves_both_endpoints(self):
        page = """
        <h3>For DC chargers</h3><p>Cost per kWh</p><b>12.00 TRY - 14.98 TRY</b>
        <h3>For AC chargers</h3><p>Cost per kWh</p><b>9.95 TRY</b>
        """
        self.assertEqual(
            {"AC": 9.95, "DC_MIN": 12.0, "DC_MAX": 14.98},
            prices.parse_trugo(page))

    def test_zes_retains_two_dc_bands(self):
        page = """
        <h1>Fiyatlandırma</h1>
        <p>AC</p><div>10,99₺ / kWh</div>
        <p>DC-1 DC-2</p><div>12,99₺ / kWh - 16,49₺ / kWh</div>
        <p>Fiyatlar lokasyon bazlı değişebilir</p>
        """
        self.assertEqual(
            {"AC":10.99, "DC_1":12.99, "DC_2":16.49},
            prices.parse_zes(page))

    def test_missing_prices_fail_closed(self):
        with self.assertRaises(ValueError):
            prices.parse_trugo("<p>Yeni kampanyalarımız</p>")
        with self.assertRaises(ValueError):
            prices.parse_zes("<p>Fiyatlar uygulamada</p>")

    def test_script_injection_is_not_parsed(self):
        page = """
        <script>DC cihazlar için 1 kWh ücreti 1,00 TL
        AC cihazlar için 1 kWh ücreti 1,00 TL</script>
        <h1>Fiyatlar uygulamada</h1>
        """
        with self.assertRaises(ValueError):
            prices.parse_trugo(page)

    def test_provider_host_is_explicit_allowlist(self):
        self.assertEqual(2, len(prices.OPERATOR_SOURCES))
        self.assertTrue(all(url.startswith("https://") for _,url,_ in
            prices.OPERATOR_SOURCES.values()))


if __name__ == "__main__":
    unittest.main()
