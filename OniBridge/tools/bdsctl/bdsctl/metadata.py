from __future__ import annotations

import json
from pathlib import PurePosixPath
import re
from typing import Any
from urllib.parse import unquote, urlparse

from .errors import MetadataError, SecurityError
from .model import Artifact, DOWNLOAD_TYPES, LockFile, utc_now
from .transport import HttpTransport, validate_url


METADATA_URL = "https://net-secondary.web.minecraft-services.net/api/v1.0/download/links"
VERSION_RE = re.compile(r"(?<!\d)(\d+\.\d+\.\d+(?:\.\d+)?)(?!\d)")


def version_from_url(url: str) -> tuple[str, str]:
    parsed = urlparse(url)
    if parsed.scheme != "https" or not parsed.hostname:
        raise SecurityError("download URL must be absolute HTTPS")
    validate_url(url)
    filename = PurePosixPath(unquote(parsed.path)).name
    match = VERSION_RE.search(filename)
    if not filename or not match:
        raise MetadataError(f"cannot extract BDS version from official filename {filename!r}")
    return match.group(1), filename


def parse_metadata(payload: bytes, content_type: str, channel: str, platforms: tuple[str, ...]) -> LockFile:
    media_type = content_type.partition(";")[0].strip().lower()
    if media_type not in {"application/json", "text/json"}:
        raise MetadataError(f"metadata content type is {content_type!r}, expected JSON")
    try:
        root: Any = json.loads(payload.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise MetadataError(f"malformed official metadata: {exc}") from exc
    if not isinstance(root, dict):
        raise MetadataError("official metadata root must be an object")
    links = root.get("links")
    if not isinstance(links, list):
        result = root.get("result")
        links = result.get("links") if isinstance(result, dict) else None
    if not isinstance(links, list):
        raise MetadataError("official metadata has no links array")

    wanted = {DOWNLOAD_TYPES[channel][platform]: platform for platform in platforms}
    found: dict[str, Artifact] = {}
    for entry in links:
        if not isinstance(entry, dict):
            continue
        download_type = entry.get("downloadType")
        if download_type not in wanted:
            continue
        platform = wanted[download_type]
        if platform in found:
            raise MetadataError(f"duplicate official metadata entry for {download_type}")
        url = entry.get("downloadUrl")
        if not isinstance(url, str):
            raise MetadataError(f"{download_type} has no string downloadUrl")
        version, filename = version_from_url(url)
        found[platform] = Artifact(
            download_type=download_type,
            version=version,
            original_filename=filename,
            download_url=url,
            executable="bedrock_server" if platform == "linux-x86_64" else "bedrock_server.exe",
        )
    missing = set(platforms) - set(found)
    if missing:
        types = [DOWNLOAD_TYPES[channel][platform] for platform in sorted(missing)]
        raise MetadataError(f"official metadata is missing required entries: {', '.join(types)}")
    versions = {artifact.version for artifact in found.values()}
    return LockFile(1, channel, utc_now(), len(versions) == 1, found)


def resolve(channel: str, platforms: tuple[str, ...], transport: HttpTransport | None = None) -> LockFile:
    transport = transport or HttpTransport()
    response = transport.get_bytes(METADATA_URL, max_size=2 * 1024 * 1024)
    return parse_metadata(response.body, response.content_type, channel, platforms)
