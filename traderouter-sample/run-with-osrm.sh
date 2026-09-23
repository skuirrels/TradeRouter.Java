#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"

osrm_url="${TRADEROUTER_OSRM_URL:-http://127.0.0.1:5000}"
osrm_url="${osrm_url%/}"
osrm_data_dir="${TRADEROUTER_OSRM_DATA_DIR:-$repo_root/osrm-data}"
osrm_dataset="${TRADEROUTER_OSRM_DATASET:-traderouter-samples}"
custom_pbf_url="${TRADEROUTER_OSRM_PBF_URL:-}"
osrm_image="${TRADEROUTER_OSRM_IMAGE:-ghcr.io/project-osrm/osrm-backend:26.8.0-debian}"
osrm_container="${TRADEROUTER_OSRM_CONTAINER:-traderouter-osrm}"
osrm_data_version="${TRADEROUTER_OSRM_DATA_VERSION:-}"
osmium_image="${TRADEROUTER_OSMIUM_IMAGE:-traderouter-osmium-tool:bookworm}"
sample_regions_file="${TRADEROUTER_OSRM_SAMPLE_REGIONS_FILE:-$script_dir/osrm-sample-regions.tsv}"
local_osrm_url="http://127.0.0.1:5000"
default_probe_coordinate=""
if [[ -f "$sample_regions_file" ]]; then
    default_probe_coordinate="$(awk -F '\t' '$1 !~ /^#/ && NF >= 4 { print $4; exit }' "$sample_regions_file")"
fi
probe_coordinate="${TRADEROUTER_OSRM_PROBE_COORDINATE:-$default_probe_coordinate}"
probe_url="$osrm_url/nearest/v1/driving/$probe_coordinate?number=1"

require_command() {
    if ! command -v "$1" >/dev/null 2>&1; then
        echo "Required command not found: $1" >&2
        exit 1
    fi
}

osrm_is_ready() {
    [[ -n "$probe_coordinate" ]] &&
        curl --silent --max-time 2 "$probe_url" 2>/dev/null | grep -q '"code":"Ok"'
}

require_runnable_osrm_image() {
    if docker run --rm "$osrm_image" osrm-extract --help >/dev/null 2>&1; then
        return
    fi

    echo "The OSRM image could not be run: $osrm_image" >&2
    echo "If Docker downloaded it but reports an input/output error, replace the corrupt local image:" >&2
    echo "  docker image rm $osrm_image" >&2
    echo "  docker pull $osrm_image" >&2
    exit 1
}

file_size() {
    if stat -f '%z' "$1" >/dev/null 2>&1; then
        stat -f '%z' "$1"
    else
        stat -c '%s' "$1"
    fi
}

resolve_download() {
    local requested_url="$1"
    local marker_path="$2"
    local resolved_url

    if [[ -f "$marker_path" ]]; then
        IFS= read -r resolved_url < "$marker_path"
    else
        resolved_url="$(curl --fail --silent --show-error --location --head \
            --output /dev/null --write-out '%{url_effective}' "$requested_url")"
        printf '%s\n' "$resolved_url" > "$marker_path"
    fi

    printf '%s\n' "$resolved_url"
}

remote_file_size() {
    curl --fail --silent --show-error --location --head "$1" |
        awk 'tolower($1) == "content-length:" { gsub("\\r", "", $2); print $2 }' |
        tail -1
}

download_extract() {
    local extract_name="$1"
    local requested_url="$2"
    local extract_path="$osrm_data_dir/$extract_name.osm.pbf"
    local source_marker="$extract_path.source-url"
    local resolved_url
    local expected_size
    local current_size=0
    local completed_size

    resolved_url="$(resolve_download "$requested_url" "$source_marker")"
    expected_size="$(remote_file_size "$resolved_url")"
    if [[ -z "$expected_size" || ! "$expected_size" =~ ^[0-9]+$ ]]; then
        echo "Could not determine the size of $resolved_url." >&2
        exit 1
    fi

    if [[ -f "$extract_path" ]]; then
        current_size="$(file_size "$extract_path")"
    fi

    if (( current_size > expected_size )); then
        echo "$extract_path is larger than its selected snapshot; refusing to overwrite it." >&2
        exit 1
    fi

    if (( current_size < expected_size )); then
        echo "Downloading $extract_name to $extract_path"
        echo "Existing bytes: $current_size of $expected_size. The download will resume rather than restart."
        curl --fail --location --retry 3 --continue-at - "$resolved_url" --output "$extract_path"
    else
        echo "$extract_name is already fully downloaded at $extract_path"
    fi

    completed_size="$(file_size "$extract_path")"
    if (( completed_size != expected_size )); then
        echo "$extract_name is incomplete: $completed_size of $expected_size bytes." >&2
        exit 1
    fi

    downloaded_pbf_path="$extract_path"
    downloaded_pbf_version="$(basename "$resolved_url" .osm.pbf)"
}

extract_sample_region() {
    local source_path="$1"
    local region_name="$2"
    local bounding_box="$3"
    local region_path="$osrm_data_dir/$region_name.osm.pbf"
    local bounding_box_marker="$region_path.bbox"
    local recorded_bounding_box=""

    if [[ -f "$bounding_box_marker" ]]; then
        IFS= read -r recorded_bounding_box < "$bounding_box_marker"
    fi
    if [[ -f "$region_path" && "$recorded_bounding_box" == "$bounding_box" ]]; then
        echo "$region_name is already extracted at $region_path"
        sample_region_path="$region_path"
        return
    fi

    echo "Extracting $region_name from $(basename "$source_path") using bounding box $bounding_box"
    if command -v osmium >/dev/null 2>&1; then
        osmium extract --overwrite --set-bounds --bbox "$bounding_box" \
            "$source_path" -o "$region_path"
    else
        docker run --rm -t -v "$osrm_data_dir:/data" "$osmium_image" \
            extract --overwrite --set-bounds --bbox "$bounding_box" \
            "/data/$(basename "$source_path")" -o "/data/$(basename "$region_path")"
    fi
    printf '%s\n' "$bounding_box" > "$bounding_box_marker"
    sample_region_path="$region_path"
}

merge_sample_extracts() {
    local merged_path="$1"
    shift
    local partial_path="$merged_path.partial"
    local container_paths=()
    local source_path

    echo "Merging the configured extracts for the sample road routes."
    if command -v osmium >/dev/null 2>&1; then
        osmium merge --overwrite --output-format pbf \
            "$@" -o "$partial_path"
        mv -f -- "$partial_path" "$merged_path"
        return
    fi

    if [[ -z "${TRADEROUTER_OSMIUM_IMAGE:-}" ]]; then
        docker build \
            --tag "$osmium_image" \
            --file "$script_dir/osmium-tool.Dockerfile" \
            "$script_dir"
    fi

    for source_path in "$@"; do
        container_paths+=("/data/$(basename "$source_path")")
    done

    docker run --rm -t -v "$osrm_data_dir:/data" "$osmium_image" \
        merge --overwrite \
        --output-format pbf \
        "${container_paths[@]}" \
        -o "/data/$(basename "$partial_path")"
    mv -f -- "$partial_path" "$merged_path"
}

build_sample_dataset() {
    local merged_path="$1"
    local recipe_marker="$merged_path.sample-regions"
    local recipe_signature
    local recorded_signature=""
    local extract_name
    local requested_url
    local bounding_box
    local probe
    local region_name
    local version
    local sample_region_paths=()
    local sample_versions=()

    if [[ ! -f "$sample_regions_file" ]]; then
        echo "Sample-region configuration not found: $sample_regions_file" >&2
        exit 1
    fi
    recipe_signature="$(cksum "$sample_regions_file" | awk '{ print $1 ":" $2 }')"
    if [[ -f "$recipe_marker" ]]; then
        IFS= read -r recorded_signature < "$recipe_marker"
    fi
    if [[ -f "$merged_path" && "$recorded_signature" == "$recipe_signature" ]]; then
        echo "The configured sample road dataset is already built at $merged_path"
        return
    fi

    while IFS=$'\t' read -r extract_name requested_url bounding_box probe; do
        if [[ -z "$extract_name" || "$extract_name" == \#* ]]; then
            continue
        fi
        if [[ -z "$requested_url" || -z "$bounding_box" ]]; then
            echo "Invalid sample-region row for $extract_name in $sample_regions_file" >&2
            exit 1
        fi
        if [[ ! "$extract_name" =~ ^[A-Za-z0-9._-]+$ ]]; then
            echo "Invalid extract name in $sample_regions_file: $extract_name" >&2
            exit 1
        fi

        download_extract "$extract_name" "$requested_url"
        version="$downloaded_pbf_version"
        region_name="$extract_name-sample-region"
        extract_sample_region "$downloaded_pbf_path" "$region_name" "$bounding_box"
        sample_region_paths+=("$sample_region_path")
        sample_versions+=("$version")
    done < "$sample_regions_file"

    if (( ${#sample_region_paths[@]} == 0 )); then
        echo "No sample regions were configured in $sample_regions_file" >&2
        exit 1
    fi

    merge_sample_extracts "$merged_path" "${sample_region_paths[@]}"
    printf '%s\n' "$recipe_signature" > "$recipe_marker"
    if [[ -z "$osrm_data_version" ]]; then
        osrm_data_version="$(IFS=+; echo "${sample_versions[*]}")"
    fi
}

require_command curl
require_command java
require_command mvn

if ! osrm_is_ready; then
    if [[ "$osrm_url" != "$local_osrm_url" ]]; then
        echo "OSRM is not reachable at $osrm_url." >&2
        echo "Automatic container startup is supported only for $local_osrm_url." >&2
        exit 1
    fi

    require_command docker
    if ! docker info >/dev/null 2>&1; then
        echo "Docker is installed but its daemon is unavailable. Start Docker Desktop and retry." >&2
        exit 1
    fi
    require_runnable_osrm_image

    if [[ ! "$osrm_dataset" =~ ^[A-Za-z0-9._-]+$ ]]; then
        echo "TRADEROUTER_OSRM_DATASET may contain only letters, numbers, dots, underscores and hyphens." >&2
        exit 1
    fi

    mkdir -p "$osrm_data_dir"
    osrm_data_dir="$(cd -- "$osrm_data_dir" && pwd)"
    dataset_base="$osrm_data_dir/$osrm_dataset"
    pbf_path="$dataset_base.osm.pbf"
    data_version_path="$pbf_path.data-version"

    if [[ -z "$osrm_data_version" && -f "$data_version_path" ]]; then
        IFS= read -r osrm_data_version < "$data_version_path"
    fi

    if [[ ! -f "$dataset_base.osrm.ebg" ]]; then
        if [[ -n "$custom_pbf_url" ]]; then
            if [[ ! -f "$pbf_path" ]]; then
                download_extract "$osrm_dataset" "$custom_pbf_url"
                if [[ -z "$osrm_data_version" ]]; then
                    osrm_data_version="$downloaded_pbf_version"
                fi
            fi
        else
            build_sample_dataset "$pbf_path"
        fi

        if [[ ! -f "$data_version_path" ]]; then
            printf '%s\n' "$osrm_data_version" > "$data_version_path"
        fi

        if [[ -z "$osrm_data_version" ]]; then
            osrm_data_version="$osrm_dataset"
        fi

        echo "Extracting the road network from $pbf_path"
        docker run --rm -t -v "$osrm_data_dir:/data" "$osrm_image" \
            osrm-extract -p /opt/car.lua "/data/$osrm_dataset.osm.pbf"
    fi

    if [[ ! -f "$dataset_base.osrm.partition" || ! -f "$dataset_base.osrm.cells" ]]; then
        echo "Partitioning the OSRM network."
        docker run --rm -t -v "$osrm_data_dir:/data" "$osrm_image" \
            osrm-partition "/data/$osrm_dataset.osrm"
    fi

    if [[ ! -f "$dataset_base.osrm.cell_metrics" ]]; then
        echo "Customizing the OSRM network."
        docker run --rm -t -v "$osrm_data_dir:/data" "$osrm_image" \
            osrm-customize "/data/$osrm_dataset.osrm"
    fi

    if docker inspect "$osrm_container" >/dev/null 2>&1; then
        if [[ "$(docker inspect --format '{{.State.Running}}' "$osrm_container")" != "true" ]]; then
            echo "Starting existing container $osrm_container."
            docker start "$osrm_container" >/dev/null
        fi
    else
        echo "Starting OSRM container $osrm_container."
        docker run --rm -d --name "$osrm_container" \
            -p 127.0.0.1:5000:5000 \
            -v "$osrm_data_dir:/data" \
            "$osrm_image" \
            osrm-routed --algorithm mld "/data/$osrm_dataset.osrm" >/dev/null
    fi

    echo "Waiting for the OSRM HTTP service."
    for _ in {1..30}; do
        if osrm_is_ready; then
            break
        fi
        sleep 1
    done

    if ! osrm_is_ready; then
        echo "OSRM did not become ready at $osrm_url." >&2
        docker logs --tail 50 "$osrm_container" >&2 || true
        exit 1
    fi
fi

echo "OSRM is ready at $osrm_url. Running the TradeRouter.Java sample."
export TRADEROUTER_OSRM_URL="$osrm_url"
if [[ -n "$osrm_data_version" ]]; then
    export TRADEROUTER_OSRM_DATA_VERSION="$osrm_data_version"
fi
mvn -q -f "$repo_root/pom.xml" -DskipTests install
exec mvn -q -f "$repo_root/pom.xml" -pl traderouter-sample exec:java -Dexec.args="$*"
