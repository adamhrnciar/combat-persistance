#!/usr/bin/env python3
"""Save a local Modrinth traffic snapshot without publishing private analytics.

Public counts:
    python3 tools/marketing_snapshot.py

Include the last 30 complete UTC days of private analytics:
    python3 tools/marketing_snapshot.py --analytics-env .env

The optional file must contain MODRINTH_TOKEN=<token>. Output goes under the
git-ignored build/marketing directory. Credentials are never saved in reports.
"""

import argparse
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import sys
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
PROJECT = "Q56AIoib"
API = "https://api.modrinth.com"


def fetch(endpoint, payload=None, token=None):
    headers = {"User-Agent": "adamhrnciar/combat-persistance traffic-snapshot"}
    if token:
        headers["Authorization"] = token
    data = None
    if payload is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(payload).encode()
    request = urllib.request.Request(API + endpoint, data=data, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=45) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"Modrinth returned HTTP {error.code} for {endpoint}") from None
    except urllib.error.URLError:
        raise RuntimeError("Could not connect to Modrinth") from None


def read_token(path):
    for line in path.read_text().splitlines():
        key, separator, value = line.partition("=")
        if separator and key.strip() == "MODRINTH_TOKEN":
            token = value.strip().strip("\"'")
            if token:
                return token
    raise RuntimeError("The credential file has no nonempty MODRINTH_TOKEN entry")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--analytics-env", type=Path)
    args = parser.parse_args()
    now = datetime.now(timezone.utc)
    project = fetch(f"/v2/project/{PROJECT}")
    versions = fetch(f"/v2/project/{PROJECT}/version")
    report = {
        "captured_at": now.isoformat(),
        "project": {key: project[key] for key in (
            "id", "slug", "downloads", "followers", "monetization_status")},
        "versions": [{key: version[key] for key in (
            "id", "version_number", "game_versions", "downloads", "date_published")}
            for version in versions],
    }
    if args.analytics_env:
        token = read_token(args.analytics_env)
        end = now.replace(hour=0, minute=0, second=0, microsecond=0)
        query = {
            "project_ids": [PROJECT],
            "time_range": {
                "start": (end - timedelta(days=30)).isoformat(),
                "end": end.isoformat(),
                "resolution": {"slices": 30},
            },
            "return_metrics": {name: {"bucket_by": ["project_id"]} for name in (
                "project_views", "project_downloads", "project_revenue")},
        }
        report["analytics_query"] = query
        try:
            report["analytics"] = fetch("/v3/analytics", query, token)
        except RuntimeError as error:
            report["analytics_error"] = str(error)
            print(f"Private analytics unavailable: {error}", file=sys.stderr)
    output_dir = ROOT / "build" / "marketing"
    output_dir.mkdir(parents=True, exist_ok=True)
    output = output_dir / f"snapshot-{now.strftime('%Y%m%dT%H%M%S%fZ')}.json"
    descriptor = os.open(output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w") as stream:
        json.dump(report, stream, indent=2)
        stream.write("\n")
    print(f"Saved {output}")
    if "analytics_error" in report:
        return 1
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, KeyError, RuntimeError) as error:
        print(f"Snapshot failed: {error}", file=sys.stderr)
        sys.exit(1)
