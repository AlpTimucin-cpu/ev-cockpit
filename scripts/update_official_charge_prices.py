#!/usr/bin/env python3
"""Daily *official-site-only* charging tariff verifier.

This updater deliberately does NOT scrape sarjharitasi.tr, access undocumented
operator mobile APIs, or guess prices. It accepts only prices extracted using
conservative, provider-specific patterns from explicitly approved official
operator pages. Data remains stamped with source URL and verification time.

If a page changes or becomes unreachable, the prior VERIFIED result remains
with its original timestamp; applications must consider prices >36h STALE.
"""
from __future__ import annotations
import argparse
from datetime import datetime, timezone
from html.parser import HTMLParser
import json
from pathlib import Path
import re
import sys
from urllib.parse import urlsplit
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parent.parent
DEST = ROOT / "data" / "charging" / "operator_prices.json"
UA = "EV-Cockpit-OfficialTariffMonitor/1.0 (+https://github.com/AlpTimucin-cpu/ID4-Media-Hub)"
OPERATOR_SOURCES = {
    "trugo": ("Trugo", "https://www.trugo.com.tr/price", "trugo.com.tr"),
    "zes": ("ZES", "https://zes.net/tr/fiyatlandirma", "zes.net"),
}
MAX_HTML_BYTES = 1_400_000


class TextExtractor(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.fragments = []
        self.ignore = 0

    def handle_starttag(self, tag, attrs):
        if tag in ("script", "style", "noscript"):
            self.ignore += 1

    def handle_endtag(self, tag):
        if tag in ("script", "style", "noscript") and self.ignore:
            self.ignore -= 1

    def handle_data(self, data):
        if not self.ignore and data.strip():
            self.fragments.append(data.strip())


def visible_text(html):
    parser = TextExtractor()
    parser.feed(html)
    return re.sub(r"\s+", " ", " ".join(parser.fragments))


def parse_amount(s):
    amount = float(s.replace(".", "").replace(",", ".")) if "," in s else float(s)
    if not (1.0 <= amount <= 100.0):
        raise ValueError("Implausible tariff")
    return amount


def _match(regex, text):
    m = re.search(regex, text, re.IGNORECASE)
    if not m:
        raise ValueError("Official tariff labels not recognized: parser needs review")
    return parse_amount(m.group(1))


def parse_trugo(html):
    txt = visible_text(html)
    def band(kind):
        # Trugo sometimes serves Turkish, sometimes English, and may publish
        # a location-dependent *range*. Keep both endpoints, not a fake
        # nationwide fixed price.
        label = (r"(?:DC\s+cihazlar\s+için|For\s+DC\s+chargers)"
                 if kind == "DC" else
                 r"(?:AC\s+cihazlar\s+için|For\s+AC\s+chargers)")
        pattern = (label + r".{0,150}?(?:1\s*kWh\s+ücreti|Cost\s+per\s+kWh)"
                   r".{0,70}?(\d{1,3}[.,]\d{2})"
                   r"(?:\s*(?:TRY|TL|₺))?(?:\s*-\s*(\d{1,3}[.,]\d{2}))?")
        found = re.search(pattern, txt, re.IGNORECASE)
        if not found:
            raise ValueError("Trugo " + kind + " tariff not recognized")
        minimum = parse_amount(found.group(1))
        maximum = parse_amount(found.group(2)) if found.group(2) else None
        if maximum is not None and maximum < minimum:
            raise ValueError("Unexpected tariff range")
        return minimum, maximum
    ac, ac_max = band("AC")
    dc, dc_max = band("DC")
    result = {"AC": ac}
    if ac_max is not None:
        result["AC_MIN"] = result.pop("AC")
        result["AC_MAX"] = ac_max
    if dc_max is not None:
        result["DC_MIN"] = dc
        result["DC_MAX"] = dc_max
    else:
        result["DC"] = dc
    return result


def parse_zes(html):
    txt = visible_text(html)
    ac = _match(r"AC\s+(\d{1,3},\d{2})\s*[₺TL]*\s*/\s*kWh", txt)
    pair = re.search(
        r"DC-1\s+DC-2\s+(\d{1,3},\d{2})\s*[₺TL]*\s*/\s*kWh\s*-\s*(\d{1,3},\d{2})\s*[₺TL]*\s*/\s*kWh",
        txt, re.IGNORECASE)
    if not pair:
        raise ValueError("ZES DC-1/DC-2 tariff labels not recognized")
    return {"AC": ac, "DC_1": parse_amount(pair.group(1)), "DC_2": parse_amount(pair.group(2))}


PARSERS = {"trugo": parse_trugo, "zes": parse_zes}


def fetch_official(url, domain):
    parsed = urlsplit(url)
    if parsed.scheme != "https" or not (parsed.hostname == domain or
        parsed.hostname == "www." + domain):
        raise ValueError("Unexpected official source host")
    request = Request(url, headers={"User-Agent": UA, "Accept": "text/html", "Accept-Language": "tr-TR,tr;q=0.9,en;q=0.8"})
    with urlopen(request, timeout=23) as response:
        destination = urlsplit(response.geturl())
        if destination.scheme != "https" or destination.hostname not in {domain, "www." + domain}:
            raise ValueError("Cross-domain redirect blocked")
        raw = response.read(MAX_HTML_BYTES + 1)
        if len(raw) > MAX_HTML_BYTES:
            raise ValueError("Excessively large page")
        return raw.decode("utf-8", errors="replace")


def load_previous():
    if DEST.exists():
        try:
            prior = json.loads(DEST.read_text(encoding="utf-8"))
            if prior.get("schema_version") == 1:
                return prior
        except (OSError, ValueError):
            pass
    return {"schema_version": 1, "operators": [], "metadata": {}}


def scan(fetch=fetch_official):
    previous = load_previous()
    kept = {r["operator_id"]: r for r in previous["operators"]
            if isinstance(r, dict) and r.get("operator_id") in OPERATOR_SOURCES}
    result = []
    problems = []
    now = datetime.now(timezone.utc).isoformat(timespec="seconds")
    for operator_id, (name, url, domain) in OPERATOR_SOURCES.items():
        old = kept.get(operator_id)
        try:
            page = fetch(url, domain)
            prices = PARSERS[operator_id](page)
            if not prices:
                raise ValueError("No tariffs")
            entries = [{
                "connector_band": band, "price_try_per_kwh": amount,
                "price_scope": "operator_published_baseline",
                "location_specific_price": None,
                "verified_at_utc": now,
                "source_url": url
            } for band, amount in sorted(prices.items())]
            result.append({
                "operator_id": operator_id, "operator": name,
                "official_url": url, "checked_at_utc": now,
                "verified_at_utc": now, "tariffs": entries,
                "notice": ("Tarife yalnızca işletmecinin yayımladığı genel referanstır. "
                           "İstasyona, sokete, üyeliğe veya kampanyaya göre farklı fiyat olabilir.")
            })
        except (OSError, UnicodeError, ValueError, TimeoutError) as e:
            problems.append(operator_id + ": " + type(e).__name__ + " — " + str(e)[:100])
            if old is not None:
                # Preserve original verification time; NEVER stamp stale data as new.
                old["checked_at_utc"] = now
                result.append(old)
    return {
        "schema_version": 1,
        "metadata": {
            "last_attempt_utc": now,
            "record_type": "official_operator_tariffs",
            "operator_count": len(result),
            "last_refresh_had_errors": bool(problems),
            "price_valid_hours": 36,
            "availability_included": False,
            "station_specific_prices_included": False,
            "source_note": "Do not treat a baseline as the actual socket checkout price."
        },
        "operators": result
    }, problems


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    doc, errors = scan()
    if args.dry_run:
        print(json.dumps(doc["metadata"], ensure_ascii=False))
    else:
        DEST.parent.mkdir(parents=True, exist_ok=True)
        DEST.write_text(json.dumps(doc, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print("Official operator tariff snapshot updated:", len(doc["operators"]))
    for error in errors:
        print("WARNING:", error, file=sys.stderr)
    # Missing/failed official tariff parsers are a clear CI warning, not a false success.
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
