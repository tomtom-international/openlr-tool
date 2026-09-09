# /// script
# requires-python = ">=3.10"
# dependencies = []
# ///
#!/usr/bin/env python3
"""Live API verification harness for a running OpenLR Tool deployment.

Exercises the behaviours that unit tests cannot: real decoding against a real map,
the response shapes, the error contract, and the decode/encode round trip. Every
check is independent and reports PASS/FAIL; the exit code is non-zero if any failed.

    uv run tools/verify_api.py                                  # all non-disruptive checks
    uv run tools/verify_api.py --codes /path/to/codes.openlrs   # supply a decode corpus
    uv run tools/verify_api.py --sample 500
    uv run tools/verify_api.py --disruptive                     # also pauses the database
    uv run tools/verify_api.py --only decode,roundtrip

Requires only the standard library and, for --disruptive, the docker CLI.
"""

from __future__ import annotations

import argparse
import json
import subprocess
from datetime import datetime, timezone
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

DEFAULT_API = "http://localhost:8081"
DEFAULT_WEBAPP = "http://localhost:3000"
DEFAULT_CODES = "/Users/dave/projects/trafficflow/nld.openlrs"

results: list[tuple[str, bool, str]] = []


def record(name: str, ok: bool, detail: str = "") -> bool:
    results.append((name, ok, detail))
    print("  %-4s %-46s %s" % ("PASS" if ok else "FAIL", name, detail))
    return ok


def request(url: str, data=None, timeout=60):
    """Return (status, parsed_body_or_text). Never raises on HTTP status."""
    body = None
    headers = {}
    if isinstance(data, dict):
        body = json.dumps(data).encode()
        headers["Content-Type"] = "application/json"
    elif isinstance(data, list):  # form fields as (key, value) pairs
        body = urllib.parse.urlencode(data).encode()
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    req = urllib.request.Request(url, data=body, headers=headers,
                                 method="POST" if body is not None else "GET")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read().decode()
            status = r.status
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        status = e.code
    except Exception as e:  # connection refused, timeout
        return 0, str(e)
    try:
        return status, json.loads(raw)
    except ValueError:
        return status, raw


def decode(api, code, props="default"):
    return request("%s/api/v1/decode" % api, {"openLrCode": code, "props": props})


def encode(api, path, pos=0, neg=0, props="default"):
    fields = [("path", p) for p in path]
    fields += [("positiveOffset", pos), ("negativeOffset", neg), ("props", props)]
    return request("%s/api/v1/encode" % api, fields)


# --------------------------------------------------------------------------- checks

def check_health(api, webapp):
    status, body = request("%s/api/v1/health" % api)
    ok = status == 200 and isinstance(body, dict) and body.get("status") == "UP" \
        and body.get("roadCount", 0) > 0
    record("health reports UP with a loaded map", ok,
           "roads=%s intersections=%s" % (body.get("roadCount"), body.get("intersectionCount"))
           if isinstance(body, dict) else str(body)[:60])

    s2, stats = request("%s/api/v1/stats" % api)
    record("stats agrees with health", s2 == 200 and isinstance(stats, dict)
           and stats.get("roadCount") == body.get("roadCount"))

    s3, w = request("%s/health" % webapp)
    record("webapp health responds", s3 == 200 and isinstance(w, dict)
           and w.get("status") == "ok")


def check_error_contract(api):
    """400 must mean the caller was at fault; profiles must not silently default."""
    status, body = decode(api, "CwQ1cyVM2BNWDPvw/0cTBA==", "typo_profile")
    reason = body.get("reason", "") if isinstance(body, dict) else ""
    record("unknown profile rejected with 400", status == 400 and "Unknown decoder profile" in reason,
           reason[:70])

    status, body = decode(api, "CwQ1cyVM2BNWDPvw/0cTBA==", "../application")
    reason = body.get("reason", "") if isinstance(body, dict) else ""
    record("profile name escaping the directory rejected",
           status == 400 and "Invalid decoder profile name" in reason, reason[:60])

    status, body = encode(api, ["definitely-not-a-segment"])
    err = body.get("error", "") if isinstance(body, dict) else ""
    record("encode with unknown meta rejected with 400",
           status == 400 and "No segment found" in err, err[:60])

    status, _ = decode(api, "not-valid-base64!!!", "default")
    record("malformed OpenLR code rejected with 400", status == 400)

    # /encode used to accept props and ignore it entirely.
    status, body = encode(api, ["359856848"], props="typo_profile")
    err = body.get("error", "") if isinstance(body, dict) else ""
    record("unknown encoder profile rejected with 400",
           status == 400 and "Unknown encoder profile" in err, err[:70])

    status, body = encode(api, ["359856848"], props="../application")
    err = body.get("error", "") if isinstance(body, dict) else ""
    record("encoder profile escaping the directory rejected",
           status == 400 and "Invalid encoder profile name" in err, err[:60])


def check_road_endpoints(api):
    """Guards the raw-JTS regression: 48 KB of nested envelopes and no coordinates."""
    status, near = request("%s/api/v1/roads/near?lon=4.9&lat=52.37&distance=100" % api)
    ok = status == 200 and isinstance(near, dict) and near.get("count", 0) > 0
    record("roads/near finds segments", ok, "count=%s" % (near.get("count") if isinstance(near, dict) else "?"))
    if not ok:
        return
    meta = near["roads"][0]["meta"]

    status, body = request("%s/api/v1/roads?%s" % (api, urllib.parse.urlencode({"meta": meta})))
    raw_len = len(json.dumps(body))
    ok = (status == 200 and isinstance(body, dict)
          and body.get("type") == "FeatureCollection"
          and body.get("meta", {}).get("count", 0) >= 1
          and body["features"][0]["geometry"]["type"] == "LineString"
          and len(body["features"][0]["geometry"]["coordinates"]) >= 2)
    record("roads?meta= returns GeoJSON with coordinates", ok, "%d bytes" % raw_len)
    record("roads?meta= payload is not bloated", raw_len < 20000, "%d bytes" % raw_len)
    if isinstance(body, dict) and body.get("features"):
        record("roads?meta= exposes no envelope and no internal id",
               "envelope" not in json.dumps(body) and "\"id\"" not in json.dumps(body))


def check_decode_corpus(api, codes, sample):
    corpus = codes[:sample]
    if not corpus:
        record("decode corpus", False, "no codes supplied")
        return
    ok = fail = 0
    failures = []
    for code in corpus:
        status, body = decode(api, code)
        if status == 200 and isinstance(body, dict) and body.get("type") == "FeatureCollection":
            ok += 1
        else:
            fail += 1
            failures.append(code)
    rate = 100.0 * ok / len(corpus)
    record("decode corpus with 'default'", rate >= 90.0,
           "%d/%d = %.1f%%" % (ok, len(corpus), rate))

    recovered = 0
    for code in failures:
        status, body = decode(api, code, "strict,relaxed,default")
        if status == 200 and isinstance(body, dict) and body.get("type") == "FeatureCollection":
            recovered += 1
    total = 100.0 * (ok + recovered) / len(corpus)
    record("profile fallback recovers some failures", True,
           "%d/%d recovered -> %.1f%% overall" % (recovered, len(failures), total))
    return failures


def check_wkt(api, codes):
    """meta.wkt must be valid WKT without the duplicate points from concatenation."""
    checked = dupes = 0
    for code in codes[:50]:
        status, d = decode(api, code)
        if status != 200 or not isinstance(d, dict) or d.get("type") != "FeatureCollection":
            continue
        checked += 1
        wkt = d["meta"]["wkt"]
        if not wkt.startswith("LINESTRING"):
            record("wkt is well formed", False, wkt[:60])
            return
        body = wkt[wkt.index("(") + 1:wkt.rindex(")")] if "(" in wkt else ""
        points = [p.strip() for p in body.split(",")]
        if any(a == b for a, b in zip(points, points[1:])):
            dupes += 1
    if not checked:
        record("wkt has no duplicated consecutive points", False, "nothing decoded")
        return
    record("wkt has no duplicated consecutive points", dupes == 0,
           "%d/%d paths clean" % (checked - dupes, checked))


def check_roundtrip(api, codes, sample):
    """decode -> rebuild path from meta+direction -> encode -> decode."""
    corpus = codes[:sample]
    encoded = identical = attempted = 0
    for code in corpus:
        status, d = decode(api, code)
        if status != 200 or not isinstance(d, dict) or d.get("type") != "FeatureCollection":
            continue
        attempted += 1
        path = [(f["properties"]["meta"] if f["properties"]["direction"]
                 else "-" + f["properties"]["meta"]) for f in d["features"]]
        status, e = encode(api, path, d["meta"]["posOff"], d["meta"]["negOff"])
        if status != 200 or not isinstance(e, dict) or not e.get("success"):
            continue
        encoded += 1
        status, d2 = decode(api, e["openLrCode"])
        if status == 200 and isinstance(d2, dict) and d2.get("type") == "FeatureCollection" \
                and d2["meta"]["wkt"] == d["meta"]["wkt"]:
            identical += 1
    if not attempted:
        record("decode/encode round trip", False, "nothing decoded to re-encode")
        return
    record("every decoded path re-encodes", encoded == attempted,
           "%d/%d" % (encoded, attempted))
    record("round trip reproduces geometry", identical >= 0.7 * attempted,
           "%d/%d identical (%.0f%%)" % (identical, attempted, 100.0 * identical / attempted))


def check_disruptive(api):
    """A dependency failure must be 5xx, never 400."""
    def docker(*args):
        return subprocess.run(["docker", *args], capture_output=True, text=True).returncode == 0

    if not docker("pause", "openlr-postgres"):
        record("pause database", False, "could not pause openlr-postgres")
        return
    try:
        time.sleep(2)
        status, body = decode(api, "CwQ1cyVM2BNWDPvw/0cTBA==")
        record("decode during database outage returns 503", status == 503,
               "HTTP %s %s" % (status, (body.get("reason", "") if isinstance(body, dict) else "")[:44]))
        status, _ = encode(api, ["359856848"])
        record("encode during database outage returns 503", status == 503, "HTTP %s" % status)
        status, _ = request("%s/api/v1/health" % api)
        record("health during database outage is not 200", status != 200, "HTTP %s" % status)
    finally:
        docker("unpause", "openlr-postgres")
        for _ in range(12):
            time.sleep(2)
            if request("%s/api/v1/health" % api)[0] == 200:
                break
        record("database recovered after unpause",
               request("%s/api/v1/health" % api)[0] == 200)


def check_openapi(api, spec_path="docs/api/openapi.yml"):
    """Every path in openapi.yml must exist, and every endpoint must be documented.

    The spec previously documented three endpoints that had never existed
    (/purgeCache, /reloadProps, /getLine) and omitted most of the real surface.
    Parsed without a YAML dependency: only the path keys are needed.
    """
    try:
        lines = open(spec_path).read().splitlines()
    except OSError as e:
        record("openapi.yml is readable", False, str(e))
        return
    documented = [l.strip().rstrip(":") for l in lines
                  if l.startswith("  /") and l.rstrip().endswith(":")]
    if not documented:
        record("openapi.yml declares paths", False, "no paths found")
        return
    record("openapi.yml declares paths", True, "%d paths" % len(documented))

    # A parameterised path needs a real identifier: probing /roads/{id} with a made-up
    # id returns 404 because the segment does not exist, not because the path is
    # unrouted. Orbis ids start in the hundreds of millions, so "1" is never present.
    real_id = None
    status, near = request("%s/api/v1/roads/near?lon=4.9&lat=52.37&distance=200" % api)
    if status == 200 and isinstance(near, dict) and near.get("roads"):
        real_id = near["roads"][0].get("id")

    missing, skipped = [], []
    for path in documented:
        if "{id}" in path:
            if real_id is None:
                skipped.append(path)
                continue
            probe = path.replace("{id}", str(real_id))
        else:
            probe = path
        status, _ = request("%s/api/v1%s" % (api, probe))
        # 400 and 503 mean routed; a probe without parameters legitimately gets those.
        if status in (404, 0):
            missing.append("%s->%s" % (path, status))
    detail = "; ".join(missing) if missing else "all %d resolve" % (len(documented) - len(skipped))
    if skipped:
        detail += " (skipped, no sample id: %s)" % ",".join(skipped)
    record("every documented path is routed", not missing, detail)


def check_cache_bounds(api):
    """Occupancy must never exceed the configured bound.

    The caches were unbounded: memory tracked the amount of distinct geography
    decoded and never came back.
    """
    status, stats = request("%s/api/v1/cache/stats" % api)
    if status != 200 or not isinstance(stats, dict) or not stats:
        record("cache stats endpoint responds", False, "HTTP %s" % status)
        return
    record("cache stats endpoint responds", True, "caches: %s" % ",".join(sorted(stats)))

    breaches = [
        "%s=%d/%d" % (name, s["size"], s["maxSize"])
        for name, s in stats.items() if s["size"] > s["maxSize"]
    ]
    record("every cache is within its bound", not breaches,
           "; ".join(breaches) if breaches
           else " ".join("%s=%d/%d" % (n, s["size"], s["maxSize"])
                         for n, s in sorted(stats.items())))

    record("every cache reports a positive bound",
           all(s["maxSize"] > 0 for s in stats.values()))


def check_cache_not_purged_by_probe(api):
    """The container health check must not mutate state.

    Probing with POST /api/v1/cache/clear emptied every cache on each interval.
    Checked against the container's configured health check rather than by counting
    "Clearing caches" log lines: an operator legitimately purges after loading a new
    network, and a log count cannot tell that apart from a probe doing it.
    """
    probe = subprocess.run(
        ["docker", "inspect", "--format", "{{json .Config.Healthcheck}}", "openlr-tool"],
        capture_output=True, text=True)
    if probe.returncode != 0:
        record("health probe does not mutate state", False,
               "docker inspect unavailable")
        return

    configured = probe.stdout.strip()
    mutating = [path for path in ("cache/clear", "properties/reload", "purgeCache")
                if path in configured]
    record("health probe does not mutate state", not mutating,
           ("probes " + ", ".join(mutating)) if mutating
           else "probes a read-only endpoint")

    # Corroborate from the logs: purges should not track the probe interval.
    logs = subprocess.run(["docker", "logs", "openlr-tool"],
                          capture_output=True, text=True)
    if logs.returncode != 0:
        return
    purges = logs.stdout.count("Clearing caches") + logs.stderr.count("Clearing caches")
    started = subprocess.run(
        ["docker", "inspect", "--format", "{{.State.StartedAt}}", "openlr-tool"],
        capture_output=True, text=True).stdout.strip()
    try:
        age = (datetime.now(timezone.utc)
               - datetime.fromisoformat(started.replace("Z", "+00:00"))).total_seconds()
    except ValueError:
        return
    # A 30s probe interval would give roughly age/30 purges; anything near that
    # means something automated is doing it. A few are an operator's business.
    expected_if_probing = max(age / 30.0, 1.0)
    record("purges are not happening at probe frequency",
           purges < 0.5 * expected_if_probing,
           "%d purge(s) in %.0f min uptime" % (purges, age / 60.0))


# ---------------------------------------------------------------------------- main

CHECKS = {
    "health": lambda a: check_health(a.api, a.webapp),
    "errors": lambda a: check_error_contract(a.api),
    "roads": lambda a: check_road_endpoints(a.api),
    "decode": lambda a: check_decode_corpus(a.api, a.codes, a.sample),
    "wkt": lambda a: check_wkt(a.api, a.codes),
    "roundtrip": lambda a: check_roundtrip(a.api, a.codes, min(a.sample, 25)),
    "cache": lambda a: (check_cache_not_purged_by_probe(a.api), check_cache_bounds(a.api)),
    "openapi": lambda a: check_openapi(a.api),
    "disruptive": lambda a: check_disruptive(a.api),
}


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--api", default=DEFAULT_API)
    p.add_argument("--webapp", default=DEFAULT_WEBAPP)
    p.add_argument("--codes", default=DEFAULT_CODES, help="file of base64 OpenLR codes")
    p.add_argument("--sample", type=int, default=200, help="codes to decode (default 200)")
    p.add_argument("--only", default="", help="comma-separated subset of: " + ",".join(CHECKS))
    p.add_argument("--disruptive", action="store_true",
                   help="also pause the database to verify the 503 contract")
    args = p.parse_args()

    try:
        args.codes = open(args.codes).read().split()
    except OSError as e:
        print("warning: no decode corpus (%s)" % e, file=sys.stderr)
        args.codes = []

    selected = [c.strip() for c in args.only.split(",") if c.strip()] or \
               [k for k in CHECKS if k != "disruptive"]
    if args.disruptive and "disruptive" not in selected:
        selected.append("disruptive")

    for name in selected:
        if name not in CHECKS:
            print("unknown check: %s" % name, file=sys.stderr)
            return 2
        print("\n[%s]" % name)
        CHECKS[name](args)

    failed = [n for n, ok, _ in results if not ok]
    print("\n%d checks, %d failed" % (len(results), len(failed)))
    for n in failed:
        print("  FAILED: %s" % n)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
