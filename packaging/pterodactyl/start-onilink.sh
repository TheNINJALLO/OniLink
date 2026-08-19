#!/usr/bin/env bash
set -uo pipefail

readonly ONILINK_REPOSITORY="TheNINJALLO/OniLink"
readonly ONILINK_RELEASE_API="https://api.github.com/repos/${ONILINK_REPOSITORY}/releases/latest"

server_jar="${SERVER_JARFILE:-OniLink.jar}"
config_file="${CONFIG_FILE:-config.properties}"
update_directory=""

log() {
    printf '[OniLink updater] %s\n' "$*"
}

cleanup() {
    if [[ -n "${update_directory}" && -d "${update_directory}" ]]; then
        rm -f -- "${update_directory}/release.json" \
            "${update_directory}/SHA256SUMS" \
            "${update_directory}/OniLink.jar" \
            "${update_directory}/start-onilink.sh" \
            "${update_directory}/onilink.properties.example" \
            "${update_directory}/OniLink.jar.previous" \
            "${update_directory}/.onilink-version"
        rmdir -- "${update_directory}" 2>/dev/null || true
    fi
}

download() {
    local url=$1
    local destination=$2
    curl --fail --silent --show-error --location \
        --retry 3 --retry-delay 2 --connect-timeout 15 --max-time 120 \
        --header "User-Agent: OniLink-Pterodactyl-Updater" \
        --header "Accept: application/vnd.github+json" \
        --header "X-GitHub-Api-Version: 2022-11-28" \
        "${url}" --output "${destination}"
}

update_onilink() {
    update_directory=$(mktemp -d "${PWD}/.onilink-update.XXXXXX") || {
        log "WARNING: could not create a temporary update directory."
        return 1
    }
    trap cleanup EXIT

    if ! download "${ONILINK_RELEASE_API}" "${update_directory}/release.json"; then
        log "WARNING: GitHub release lookup failed; keeping the installed JAR."
        return 1
    fi

    local release_tag
    release_tag=$(sed -nE 's/.*"tag_name"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/p' \
        "${update_directory}/release.json" | head -n 1)
    case "${release_tag}" in
        *[!A-Za-z0-9._-]*|'')
            log "WARNING: the release service returned an invalid tag; keeping the installed JAR."
            return 1
            ;;
    esac

    local release_url="https://github.com/${ONILINK_REPOSITORY}/releases/download/${release_tag}"
    local installed_tag=""
    if [[ -f .onilink-version ]]; then
        installed_tag=$(head -n 1 .onilink-version)
    fi
    if [[ "${installed_tag}" == "${release_tag}" ]]; then
        log "Verifying installed stable release ${release_tag}..."
    else
        log "Stable update available: ${installed_tag:-none} -> ${release_tag}."
    fi
    if ! download "${release_url}/SHA256SUMS" "${update_directory}/SHA256SUMS" || \
       ! download "${release_url}/OniLink.jar" "${update_directory}/OniLink.jar" || \
       ! download "${release_url}/start-onilink.sh" "${update_directory}/start-onilink.sh" || \
       ! download "${release_url}/onilink.properties.example" "${update_directory}/onilink.properties.example"; then
        log "WARNING: release download failed; keeping the installed JAR."
        return 1
    fi

    local asset
    local expected_sha
    local downloaded_sha
    for asset in OniLink.jar start-onilink.sh onilink.properties.example; do
        expected_sha=$(awk -v wanted="${asset}" '$2 == wanted { print tolower($1); exit }' \
            "${update_directory}/SHA256SUMS")
        downloaded_sha=$(sha256sum "${update_directory}/${asset}" | awk '{ print tolower($1) }')
        if [[ ! "${expected_sha}" =~ ^[0-9a-f]{64}$ ]] || [[ "${downloaded_sha}" != "${expected_sha}" ]]; then
            log "WARNING: ${asset} checksum validation failed; keeping the installed release."
            return 1
        fi
    done

    downloaded_sha=$(sha256sum "${update_directory}/OniLink.jar" | awk '{ print tolower($1) }')

    local installed_sha=""
    if [[ -f "${server_jar}" ]]; then
        installed_sha=$(sha256sum "${server_jar}" | awk '{ print tolower($1) }')
    fi
    if [[ "${installed_sha}" == "${downloaded_sha}" ]]; then
        log "OniLink ${release_tag} is already installed."
    else
        if [[ -f "${server_jar}" ]]; then
            cp -p -- "${server_jar}" "${update_directory}/OniLink.jar.previous" || return 1
            mv -f -- "${update_directory}/OniLink.jar.previous" "${server_jar}.previous" || return 1
        fi
        mv -f -- "${update_directory}/OniLink.jar" "${server_jar}" || return 1
        log "Installed verified OniLink ${release_tag}; the prior JAR is ${server_jar}.previous."
    fi

    local staged_sha
    local current_sha
    staged_sha=$(sha256sum "${update_directory}/onilink.properties.example" | awk '{ print tolower($1) }')
    current_sha=""
    if [[ -f onilink.properties.example ]]; then
        current_sha=$(sha256sum onilink.properties.example | awk '{ print tolower($1) }')
    fi
    if [[ "${current_sha}" != "${staged_sha}" ]]; then
        if [[ -f onilink.properties.example ]]; then
            cp -p -- onilink.properties.example onilink.properties.example.previous || return 1
        fi
        mv -f -- "${update_directory}/onilink.properties.example" onilink.properties.example || return 1
        log "Updated the configuration reference; preserved active ${config_file}."
    fi

    staged_sha=$(sha256sum "${update_directory}/start-onilink.sh" | awk '{ print tolower($1) }')
    current_sha=$(sha256sum "$0" | awk '{ print tolower($1) }')
    if [[ "${current_sha}" != "${staged_sha}" ]]; then
        cp -p -- "$0" start-onilink.sh.previous || return 1
        mv -f -- "${update_directory}/start-onilink.sh" start-onilink.sh || return 1
        chmod 0755 start-onilink.sh || return 1
        log "Updated start-onilink.sh; the new updater takes over on the next restart."
    fi

    printf '%s\n' "${release_tag}" > "${update_directory}/.onilink-version" || return 1
    mv -f -- "${update_directory}/.onilink-version" .onilink-version || return 1
    return 0
}

update_onilink || true
cleanup
trap - EXIT

if [[ ! -s "${server_jar}" ]]; then
    log "ERROR: no usable ${server_jar} exists and the automatic update did not succeed."
    exit 1
fi

log "Starting ${server_jar} with ${config_file}."
exec java -Xms128M -XX:MaxRAMPercentage=95.0 -jar "${server_jar}" "${config_file}"
