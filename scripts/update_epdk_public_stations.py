#!/usr/bin/env python3
"""Publish only EPDK's HALKA_ACIK EV stations to a compact public snapshot.

Uses published Swagger GET-with-JSON-body endpoint. We do not request the
operator's private/credentialed endpoint or infer live availability/prices.
Fail closed: if schema/count/coordinates fail, do not replace last snapshot.
"""
import gzip
import json
import math
from pathlib import Path
from datetime import datetime, timezone
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parent.parent
DEST = ROOT / "data" / "charging" / "epdk_public_stations.json.gz"
URL = "https://apigateway.epdk.gov.tr/sarjIstasyonlari"
MAX_RESPONSE = 30_000_000
MAX_STATIONS = 100_000
MAX_SOCKET_PER_STATION = 24


def numeric(value):
    try:
        if isinstance(value, bool):
            return None
        number = float(str(value).replace(",", "."))
        return number if math.isfinite(number) else None
    except (TypeError, ValueError):
        return None


def compact_sockets(raw):
    result = []
    if not isinstance(raw, list):
        return result
    for socket in raw[:MAX_SOCKET_PER_STATION]:
        if not isinstance(socket, dict):
            continue
        kind = str(socket.get("soketTipi") or "").strip()[:60]
        current = str(socket.get("soketTuru") or "").strip()[:50]
        identifier = str(socket.get("soketNo") or "").strip()[:48]
        power_raw = str(socket.get("soketGucu") or "").strip()[:24]
        if not kind and not current:
            continue
        result.append({
            "socket_id": identifier,
            "type": kind, "kind": current,
            # Raw EPDK value: no unverified assumption about its units.
            "power_raw": power_raw
        })
    return result


def normalize_station(row):
    if not isinstance(row, dict) or row.get("hizmetSekli") != "HALKA_ACIK":
        return None
    lat, lon = numeric(row.get("enlem")), numeric(row.get("boylam"))
    if lat is None or lon is None or not 35 <= lat <= 43 or not 25 <= lon <= 46:
        return None
    station_id = str(row.get("sarjIstasyonuNo") or "").strip()[:70]
    if not station_id:
        return None
    name = str(row.get("sarjIstasyonuAdi") or "").strip()[:150]
    operator = str(row.get("sarjAgiIsletmecisiUnvan") or "").strip()[:160]
    address = str(row.get("adres") or "").strip()[:260]
    license_id = str(row.get("sarjAgiIsletmecisiLisansNo") or "").strip()[:60]
    sockets = compact_sockets(row.get("soketler"))
    return {
        "official_station_id": station_id,
        "name": name or "Şarj istasyonu",
        "operator": operator,
        "operator_license": license_id,
        "brand": str(row.get("marka") or "").strip()[:130],
        "station_operator": str(row.get("sarjIstasyonuIsletmecisi") or "").strip()[:160],
        "green_station_raw": str(row.get("yesilSarjIstasyonuMu")
                                 if row.get("yesilSarjIstasyonuMu") is not None
                                 else "").strip()[:30],
        "service_kind": "HALKA_ACIK",
        "distribution_license": str(
            row.get("olumluGorusVerenDagitimSirketiLisansNo") or "").strip()[:80],
        "distribution_company": str(
            row.get("olumluGorusVerenDagitimSirketiLisansUnvani") or "").strip()[:160],
        "distribution_approval_doc": str(
            row.get("dagitimSirketiOlumluGorusBelgeNumarasi") or "").strip()[:90],
        "lat": round(lat, 7),
        "lon": round(lon, 7),
        "address": address,
        "sockets": sockets,
        "socket_count": len(sockets),
        "source": "EPDK",
        "public": True
    }


def compile_epdk(payload):
    if not isinstance(payload, dict) or payload.get("statusCode") != 200:
        raise ValueError("Unverified EPDK response status")
    items = payload.get("data")
    count = payload.get("numRows")
    if not isinstance(items, list) or not 10_000 <= len(items) <= MAX_STATIONS:
        raise ValueError("EPDK records unexpectedly missing")
    if count != len(items):
        raise ValueError("EPDK declared row count disagrees with records")
    raw_public = sum(
        isinstance(x, dict) and x.get("hizmetSekli") == "HALKA_ACIK"
        for x in items
    )
    result, seen = [], set()
    for row in items:
        item = normalize_station(row)
        if item is None or item["official_station_id"] in seen:
            continue
        seen.add(item["official_station_id"])
        result.append(item)
    # Loss of coordinates or unexpected service labels requires manual review.
    if not raw_public >= 5_000 or len(result) < raw_public * 0.80:
        raise ValueError("Too few valid, geocoded public stations")
    result.sort(key=lambda x: x["official_station_id"])
    return {
        "schema_version": 1,
        "source_url": URL,
        "verified_at_utc": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "all_rows_count": len(items),
        "public_rows_count": raw_public,
        "geocoded_public_rows_count": len(result),
        "prices_included": False,
        "live_status_included": False,
        "stations": result
    }


def get_official():
    req = Request(URL, method="GET", data=b"{}",
        headers={"Accept":"application/json","Content-Type":"application/json",
                 "User-Agent":"EV-Cockpit/0.60 (official EPDK open API)"})
    with urlopen(req, timeout=50) as response:
        if response.status != 200:
            raise ValueError("EPDK HTTP not OK")
        data = response.read(MAX_RESPONSE + 1)
        if len(data) > MAX_RESPONSE:
            raise ValueError("EPDK result exceeds safe limit")
        return json.loads(data.decode("utf-8"))


def main():
    document = compile_epdk(get_official())
    DEST.parent.mkdir(parents=True, exist_ok=True)
    raw = json.dumps(document, ensure_ascii=False, separators=(",", ":"))
    compressed = gzip.compress(raw.encode("utf-8"), compresslevel=9, mtime=0)
    # Avoid overwriting if the output is unexpectedly huge or tiny.
    if not 100_000 <= len(compressed) <= 10_000_000:
        raise ValueError("Unreasonable exported snapshot size")
    DEST.write_bytes(compressed)
    meta = {k: document[k] for k in (
        "schema_version", "source_url", "verified_at_utc",
        "all_rows_count", "public_rows_count", "geocoded_public_rows_count",
        "prices_included", "live_status_included")}
    meta["compressed_bytes"] = len(compressed)
    meta_path = DEST.parent / "epdk_public_snapshot_meta.json"
    meta_path.write_text(json.dumps(meta, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8")
    print("EPDK total rows:", document["all_rows_count"])
    print("EPDK public rows:", document["public_rows_count"])
    print("EPDK geocoded public rows:", document["geocoded_public_rows_count"])
    print("Compressed bytes:", len(compressed))


if __name__ == "__main__":
    main()
