#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
sample_dir="$repo_root/traderouter-sample"

if grep -R -n -E 'RoadRouteOverride|SuppliedRoadRoute|distanceKm\([[:space:]]*[0-9]' "$sample_dir" --include='*.java'; then
  echo "Sample road distances must use the internal estimator or a configured RoadRouteProvider such as OSRM." >&2
  echo "Fixed road-route overrides are not permitted." >&2
  exit 1
fi
