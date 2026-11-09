#!/usr/bin/env python3
"""Read-only, one-request schema discovery for EPDK's published REST service.

EPDK publishes GET /sarjIstasyonlari, with a JSON string in the request body
in its Swagger 2.0 document (unusual, so do not guess POST or private APIs).
This diagnostic NEVER persists raw station records or account information.
It only prints top-level JSON type, collection sizes and safe FIELD NAMES.
"""
import json
import sys
from pathlib import Path
from datetime import datetime, timezone
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError

URL = "https://apigateway.epdk.gov.tr/sarjIstasyonlari"
FILTER = {}  # Test unfiltered documented GET body; no private API calls
MAX_BYTES = 20_000_000


def inspect_shape(root):
    # Strict schema-only summary. No station values, addresses or records.
    status = {
        "checked_at_utc": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "source": URL,
        "root_type": type(root).__name__,
        "root_keys": [], "arrays": [], "objects": [],
        "status_code": None, "row_count": None, "result_type": None,
        "result_length": None, "result_json_type": None
    }
    if isinstance(root, list):
        status["arrays"].append({
            "path": "root", "count": len(root),
            "field_names": sorted(root[0])[:60]
                if root and isinstance(root[0], dict) else []
        })
    elif isinstance(root, dict):
        status["root_keys"] = sorted(root)[:60]
        rows = root.get("data")
        if isinstance(rows,list) and rows:
            from collections import Counter
            types = Counter(str(row.get("hizmetSekli", ""))[:50]
                for row in rows if isinstance(row,dict))
            status["service_categories"] = dict(types.most_common(12))
            sample = next((row for row in rows if isinstance(row,dict)),{})
            status["coordinate_types"] = {
                field: type(sample.get(field)).__name__
                for field in ("enlem","boylam")
            }
            sockets = next((row.get("soketler") for row in rows
                if isinstance(row,dict) and row.get("soketler")),None)
            status["socket_collection_type"] = type(sockets).__name__
            if isinstance(sockets,list):
                status["socket_example_count"] = len(sockets)
                if sockets and isinstance(sockets[0],dict):
                    status["socket_field_names"] = sorted(sockets[0])[:40]
        columns = root.get("columnNames")
        if isinstance(columns,list):
            status["column_names"] = [str(name)[:80] for name in columns[:40]
                if isinstance(name,str)]
        status["status_code"] = root.get("statusCode") if isinstance(
            root.get("statusCode"), (int, str)) else None
        status["row_count"] = root.get("numRows") if isinstance(
            root.get("numRows"), int) else None
        value = root.get("result")
        status["result_type"] = type(value).__name__
        status["result_length"] = len(value) if isinstance(value,
            (list, dict, str)) else None
        if isinstance(value, str):
            try:
                decoded = json.loads(value)
                status["result_json_type"] = type(decoded).__name__
                if isinstance(decoded,list):
                    status["result_length"] = len(decoded)
            except ValueError:
                pass
        for name, value in root.items():
            if isinstance(value, list):
                status["arrays"].append({
                    "path": str(name)[:70], "count": len(value),
                    "field_names": sorted(value[0])[:60]
                        if value and isinstance(value[0], dict) else []
                })
            elif isinstance(value, dict):
                status["objects"].append({
                    "path": str(name)[:70], "field_names": sorted(value)[:45]
                })
    print(json.dumps(status, ensure_ascii=False))
    dest = Path(__file__).resolve().parent.parent / "data/charging/epdk_schema.json"
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text(json.dumps(status, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main():
    # The GET body matches Swagger, not an undocumented internal API.
    request = Request(
        URL, data=json.dumps(FILTER).encode("utf-8"), method="GET",
        headers={"Accept": "application/json", "Content-Type": "application/json",
                 "User-Agent": "EV-Cockpit/0.60 (official EPDK API integration)"})
    try:
        with urlopen(request, timeout=25) as response:
            print("http_status:", response.status)
            print("content_type:", response.headers.get("Content-Type", "unknown").split(";")[0])
            payload = response.read(MAX_BYTES + 1)
            if len(payload) > MAX_BYTES:
                print("response_exceeds_limit")
                return 2
            inspect_shape(json.loads(payload.decode("utf-8")))
            return 0
    except HTTPError as error:
        print("http_status:", error.code)
    except (URLError, ValueError, TimeoutError) as error:
        print("request_failed:", type(error).__name__)
    return 1


if __name__ == "__main__":
    sys.exit(main())
