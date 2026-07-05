#!/usr/bin/env python3
"""
Import speed cameras from OpenStreetMap via Overpass API.

Usage:
    python import_osm.py --country ua
    python import_osm.py --country pl
    python import_osm.py --country de
    python import_osm.py --country all
    python import_osm.py --country ua --api-url https://speed.komhub.top/api/cameras
    python import_osm.py --country all --api-url https://speed.komhub.top/api/cameras --dry-run
"""

import argparse
import json
import math
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Any

# ── Configuration ──────────────────────────────────────────────────────────────

COUNTRIES = {
    "ua": {
        "name": "Ukraine",
        "relation_id": 7264866,
        "bbox": (44.0, 22.0, 52.5, 40.5),  # min_lat, min_lon, max_lat, max_lon
    },
    "pl": {
        "name": "Poland",
        "relation_id": 130271,
        "bbox": (49.0, 14.0, 55.0, 24.0),
    },
    "de": {
        "name": "Germany",
        "relation_id": 51477,
        "bbox": (47.0, 5.5, 55.0, 15.0),
    },
}

OVERPAST_URL = "https://overpass-api.de/api/interpreter"
REQUEST_TIMEOUT = 180  # seconds (must match server-side timeout)
DUPLICATE_RADIUS_M = 50  # meters — cameras within this distance are deduplicated
DEFAULT_SPEED_LIMIT = 50  # km/h when no maxspeed tag is present
MAX_RETRIES = 2
RETRY_DELAY_S = 5
TILE_FACTOR = 2  # split into TILE_FACTOR x TILE_FACTOR sub-bboxes on timeout

# ── Helpers ────────────────────────────────────────────────────────────────────


def build_bbox_query(bbox: tuple[float, float, float, float], timeout_s: int = 170) -> str:
    """Build an Overpass QL query with a bbox."""
    min_lat, min_lon, max_lat, max_lon = bbox
    return (
        f"[out:json][timeout:{timeout_s}];\n"
        f'node["highway"="speed_camera"]({min_lat},{min_lon},{max_lat},{max_lon});\n'
        "out body;"
    )


def build_bbox_count_query(bbox: tuple[float, float, float, float], timeout_s: int = 60) -> str:
    """Build an Overpass QL count query for a bbox."""
    min_lat, min_lon, max_lat, max_lon = bbox
    return (
        f"[out:json][timeout:{timeout_s}];\n"
        f'node["highway"="speed_camera"]({min_lat},{min_lon},{max_lat},{max_lon});\n'
        "out count;"
    )


def build_area_query(relation_id: int, timeout_s: int = 170) -> str:
    """Build an Overpass QL query using the relation's area ID."""
    area_id = 3600000000 + relation_id
    return (
        f"[out:json][timeout:{timeout_s}];\n"
        f"area(id:{area_id})->.searchArea;\n"
        'node["highway"="speed_camera"](area.searchArea);\n'
        "out body;"
    )


def split_bbox(
    bbox: tuple[float, float, float, float], factor: int
) -> list[tuple[float, float, float, float]]:
    """Split a bbox into a factor×factor grid of sub-bboxes."""
    min_lat, min_lon, max_lat, max_lon = bbox
    lat_step = (max_lat - min_lat) / factor
    lon_step = (max_lon - min_lon) / factor
    tiles: list[tuple[float, float, float, float]] = []
    for i in range(factor):
        for j in range(factor):
            sub_min_lat = min_lat + i * lat_step
            sub_max_lat = min_lat + (i + 1) * lat_step
            sub_min_lon = min_lon + j * lon_step
            sub_max_lon = min_lon + (j + 1) * lon_step
            tiles.append((sub_min_lat, sub_min_lon, sub_max_lat, sub_max_lon))
    return tiles


def fetch_overpass(query: str, timeout: int = REQUEST_TIMEOUT) -> list[dict[str, Any]]:
    """Send a query to the Overpass API and return the list of element dicts."""
    timeout_s = min(timeout, REQUEST_TIMEOUT)
    data = urllib.parse.urlencode({"data": query}).encode("utf-8")
    req = urllib.request.Request(OVERPAST_URL, data=data)
    req.add_header("Content-Type", "application/x-www-form-urlencoded")
    req.add_header("User-Agent", "SpeedcamAssistant/1.0 (import_osm.py)")

    try:
        with urllib.request.urlopen(req, timeout=timeout_s) as resp:
            body = resp.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")[:500] if exc.fp else ""
        raise RuntimeError(
            f"Overpass API HTTP {exc.code}: {exc.reason}\n{detail}"
        ) from exc
    except urllib.error.URLError as exc:
        raise RuntimeError(f"Overpass API connection error: {exc.reason}") from exc

    parsed = json.loads(body)
    return parsed.get("elements", [])


def count_in_bbox(bbox: tuple[float, float, float, float]) -> int | None:
    """Quick count query for a bbox. Returns None on failure."""
    query = build_bbox_count_query(bbox)
    try:
        elements = fetch_overpass(query, timeout=60)
        if elements and "tags" in elements[0] and "total" in elements[0]["tags"]:
            return int(elements[0]["tags"]["total"])
    except Exception:
        pass
    return None


def fetch_bbox_with_retry(
    bbox: tuple[float, float, float, float],
    label: str = "",
    max_retries: int = MAX_RETRIES,
) -> list[dict[str, Any]]:
    """Fetch nodes for a bbox, retrying and splitting into tiles on timeout."""
    # First try the full bbox
    for attempt in range(1, max_retries + 2):
        try:
            query = build_bbox_query(bbox)
            tag = f"{label} " if label else ""
            print(f"    [{tag}bbox, attempt {attempt}] Querying ...", end=" ", flush=True)
            elements = fetch_overpass(query)
            print(f"got {len(elements)} nodes.")
            return elements
        except RuntimeError as exc:
            if "HTTP 504" in str(exc) or "HTTP 502" in str(exc):
                print(f"timeout (attempt {attempt})")
                if attempt <= max_retries:
                    wait = RETRY_DELAY_S * attempt
                    print(f"      Retrying in {wait}s ...")
                    time.sleep(wait)
                else:
                    print(f"      Splitting bbox into {TILE_FACTOR}×{TILE_FACTOR} tiles ...")
                    return _fetch_tiled(bbox, label)
            else:
                print(f"error: {exc}")
                if attempt <= max_retries:
                    wait = RETRY_DELAY_S * attempt
                    print(f"      Retrying in {wait}s ...")
                    time.sleep(wait)
                else:
                    raise
    return []


def _fetch_tiled(
    bbox: tuple[float, float, float, float], label: str = ""
) -> list[dict[str, Any]]:
    """Split bbox into tiles and fetch each one."""
    tiles = split_bbox(bbox, TILE_FACTOR)
    all_elements: list[dict[str, Any]] = []
    total_expected = count_in_bbox(bbox)
    expected_str = f" (~{total_expected} total)" if total_expected is not None else ""

    print(f"      Split into {len(tiles)} tiles{expected_str}")

    for idx, tile in enumerate(tiles, 1):
        # Try each tile with retries
        for attempt in range(1, MAX_RETRIES + 2):
            try:
                tile_label = f"{label} tile {idx}/{len(tiles)}" if label else f"tile {idx}/{len(tiles)}"
                print(f"      [{tile_label}, attempt {attempt}] Querying ...", end=" ", flush=True)
                query = build_bbox_query(tile, timeout_s=120)
                elements = fetch_overpass(query, timeout=120)
                print(f"got {len(elements)} nodes.")
                all_elements.extend(elements)
                break  # success, move to next tile
            except Exception as exc:
                if "HTTP 504" in str(exc) or "HTTP 502" in str(exc):
                    print(f"timeout (attempt {attempt})")
                    if attempt <= MAX_RETRIES:
                        wait = RETRY_DELAY_S * attempt
                        print(f"        Retrying in {wait}s ...")
                        time.sleep(wait)
                    else:
                        print(f"        ✗ Tile {idx} failed after {MAX_RETRIES + 1} attempts, skipping.")
                else:
                    print(f"error: {exc}")
                    if attempt <= MAX_RETRIES:
                        wait = RETRY_DELAY_S * attempt
                        print(f"        Retrying in {wait}s ...")
                        time.sleep(wait)
                    else:
                        print(f"        ✗ Tile {idx} failed, skipping.")

    return all_elements


def lat_lon_distance_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Haversine distance in metres between two (lat, lon) points."""
    R = 6_371_000  # Earth radius in metres
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlambda = math.radians(lon2 - lon1)

    a = (
        math.sin(dphi / 2) ** 2
        + math.cos(phi1) * math.cos(phi2) * math.sin(dlambda / 2) ** 2
    )
    return R * 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))


def deduplicate(
    cameras: list[dict[str, Any]], radius_m: float = DUPLICATE_RADIUS_M
) -> list[dict[str, Any]]:
    """Return cameras with close duplicates removed (keeps first occurrence)."""
    kept: list[dict[str, Any]] = []
    for cam in cameras:
        lat, lng = cam["lat"], cam["lng"]
        is_duplicate = False
        for existing in kept:
            if lat_lon_distance_m(lat, lng, existing["lat"], existing["lng"]) < radius_m:
                is_duplicate = True
                break
        if not is_duplicate:
            kept.append(cam)
    return kept


def parse_camera(element: dict[str, Any]) -> dict[str, Any]:
    """Extract a normalised camera dict from an Overpass element."""
    tags = element.get("tags", {})
    lat = element.get("lat", 0.0)
    lon = element.get("lon", 0.0)

    # Extract speed limit
    raw_speed = tags.get("maxspeed")
    speed_limit = DEFAULT_SPEED_LIMIT
    if raw_speed:
        # Sometimes values like "50", "70 km/h", "30 mph"
        cleaned = raw_speed.lower().replace("km/h", "").replace("mph", "").strip()
        try:
            speed_limit = int(float(cleaned))
        except (ValueError, TypeError):
            speed_limit = DEFAULT_SPEED_LIMIT

    # Description: prefer 'name' tag, fall back to street name
    description = tags.get("name") or tags.get("addr:street") or ""

    return {
        "lat": lat,
        "lng": lon,
        "speed_limit": speed_limit,
        "description": description,
        "source": "osm",
        "raw_tags": tags,  # kept for debugging / future enrichment
    }


def fetch_country(country_code: str, country_info: dict[str, Any]) -> list[dict[str, Any]]:
    """Fetch and parse speed cameras for a single country.

    Strategy:
      1. Try area (relation) query — fast, uses pre-computed area.
      2. Try full bbox query — catches everything.
      3. If bbox times out, split into tiles automatically.
    """
    print(f"\n  ── {country_info['name']} ({country_code.upper()}) ──")
    print(f"  Relation ID: {country_info['relation_id']}")

    bbox = country_info["bbox"]

    # ── Strategy 1: area (relation) ──
    print(f"  [strategy 1] Area (relation) ...", end=" ", flush=True)
    try:
        query = build_area_query(country_info["relation_id"])
        elements = fetch_overpass(query)
        if elements:
            print(f"got {len(elements)} nodes ✓")
            cameras = [parse_camera(el) for el in elements]
            before = len(cameras)
            unique = deduplicate(cameras)
            print(f"  Parsed: {before} → Unique: {len(unique)} (Δ {before - len(unique)} duplicates)")
            clean = [c for c in unique if c.pop("raw_tags", None) or True]
            return clean
        else:
            print("0 nodes (area data may be stale), trying bbox ...")
    except Exception as exc:
        print(f"✗ {exc}")
        print(f"  Falling back to bbox ...")

    # ── Strategy 2: bbox with auto-tiling ──
    print(f"  [strategy 2] Bbox with auto-tiling ...")
    elements = fetch_bbox_with_retry(bbox, label=country_code.upper())

    if not elements:
        print(f"  No nodes found.")
        return []

    cameras = [parse_camera(el) for el in elements]
    before = len(cameras)
    unique = deduplicate(cameras)
    print(f"  Parsed: {before} → Unique: {len(unique)} (Δ {before - len(unique)} duplicates)")

    clean = []
    for c in unique:
        c.pop("raw_tags", None)
        clean.append(c)
    return clean


def post_to_api(
    cameras: list[dict[str, Any]], api_url: str, dry_run: bool = False
) -> dict[str, Any]:
    """POST cameras one by one to the API."""
    if dry_run:
        print(f"\n  [DRY-RUN] Would POST {len(cameras)} cameras to {api_url}")
        return {"dry_run": True, "count": len(cameras)}

    success = 0
    failed = 0
    print(f"\n  POSTing {len(cameras)} cameras to {api_url} ...")
    
    for i, cam in enumerate(cameras):
        payload = json.dumps(cam, ensure_ascii=False).encode("utf-8")
        req = urllib.request.Request(
            api_url,
            data=payload,
            method="POST",
        )
        req.add_header("Content-Type", "application/json")
        req.add_header("User-Agent", "SpeedcamAssistant/1.0 (import_osm.py)")
        
        try:
            with urllib.request.urlopen(req, timeout=10) as resp:
                if resp.status in (200, 201):
                    success += 1
                else:
                    failed += 1
        except Exception:
            failed += 1
        
        if (i + 1) % 100 == 0:
            print(f"    Progress: {i + 1}/{len(cameras)} ({success} OK, {failed} failed)")
    
    print(f"  Done: {success} imported, {failed} failed")
    return {"imported": success, "failed": failed}


# ── CLI ────────────────────────────────────────────────────────────────────────


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Import speed cameras from OpenStreetMap via Overpass API",
    )
    parser.add_argument(
        "--country",
        type=str,
        choices=["ua", "pl", "de", "all"],
        default="all",
        help="Target country code (default: all)",
    )
    parser.add_argument(
        "--api-url",
        type=str,
        default=None,
        help="Optional API endpoint to POST results to (e.g. https://speed.komhub.top/api/cameras)",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        default=False,
        help="With --api-url: show what would be sent without actually POSTing",
    )
    parser.add_argument(
        "--tile-factor",
        type=int,
        default=TILE_FACTOR,
        help=f"Split bbox into N×N tiles on timeout (default: {TILE_FACTOR})",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> None:
    args = parse_args(argv)

    # Allow overriding tile factor via CLI
    global TILE_FACTOR
    TILE_FACTOR = args.tile_factor

    if args.country == "all":
        selected = COUNTRIES
    else:
        selected = {args.country: COUNTRIES[args.country]}

    all_cameras: list[dict[str, Any]] = []

    print("=" * 60)
    print("  Speed Camera Import from OpenStreetMap")
    print("=" * 60)

    for code, info in selected.items():
        try:
            cameras = fetch_country(code, info)
            all_cameras.extend(cameras)
        except Exception as exc:
            print(f"  ✗ ERROR for {info['name']}: {exc}", file=sys.stderr)

    print("\n" + "─" * 60)
    print(f"  TOTAL: {len(all_cameras)} unique speed cameras imported")
    print("─" * 60)

    if not all_cameras:
        print("  No cameras found. Nothing to do.")
        sys.exit(0)

    if args.api_url:
        try:
            result = post_to_api(all_cameras, args.api_url, dry_run=args.dry_run)
            if not args.dry_run:
                print(f"  API response: {json.dumps(result, ensure_ascii=False, indent=2)}")
        except Exception as exc:
            print(f"  ✗ Failed to POST to API: {exc}", file=sys.stderr)
            # Still output JSON to stdout so the data isn't lost
            print("\n  Camera data (stdout fallback):")
            print(json.dumps(all_cameras, ensure_ascii=False, indent=2))
    else:
        # Just print JSON to stdout
        print()
        print(json.dumps(all_cameras, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()