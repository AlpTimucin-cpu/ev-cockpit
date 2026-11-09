import importlib.util
from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location("epdk",
    ROOT/"scripts"/"update_epdk_public_stations.py")
epdk=importlib.util.module_from_spec(spec)
spec.loader.exec_module(epdk)

class EpdkPublicStationsTest(unittest.TestCase):
    def test_public_stations_only(self):
        basic={"sarjIstasyonuNo":"EPDK-42","sarjIstasyonuAdi":"Deneme",
          "enlem":37.9,"boylam":34.6,"adres":"Örnek adres",
          "hizmetSekli":"HALKA_ACIK","sarjAgiIsletmecisiUnvan":"Örnek",
          "soketler":[{"soketNo":"1","soketTipi":"CCS",
          "soketTuru":"DC","soketGucu":120}]}
        result=epdk.normalize_station(basic)
        self.assertIsNotNone(result)
        self.assertEqual("EPDK-42",result["official_station_id"])
        self.assertEqual(1,result["socket_count"])
        self.assertEqual("120",result["sockets"][0]["power_raw"])
        self.assertIsNone(epdk.normalize_station(
            {**basic, "hizmetSekli":"OZEL"}))
    def test_bad_coordinates_or_ids_skipped(self):
        row={"hizmetSekli":"HALKA_ACIK","sarjIstasyonuNo":"A",
             "enlem":"0","boylam":"0"}
        self.assertIsNone(epdk.normalize_station(row))
        self.assertIsNone(epdk.normalize_station(
            {**row,"sarjIstasyonuNo":"","enlem":37.9,"boylam":34.6}))
    def test_fail_closed_on_zero_or_few_records(self):
        with self.assertRaises(ValueError):
            epdk.compile_epdk({"statusCode":200,"numRows":0,"data":[]})
        with self.assertRaises(ValueError):
            epdk.compile_epdk({"statusCode":500,"numRows":10000,
                "data":[{}]*10000})
