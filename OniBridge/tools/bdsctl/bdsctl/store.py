from __future__ import annotations

import json
import os
from pathlib import Path
import shutil

from .archive import (
    extract_safely,
    find_required_file,
    inspect_executable,
    sha256_file,
    validate_zip,
)
from .errors import SecurityError, ValidationError
from .model import Artifact, LockFile, utc_now
from .transport import HttpTransport


MAX_ARCHIVE_SIZE = 2 * 1024 * 1024 * 1024


def require_eula(environment: dict[str, str] | None = None) -> None:
    environment = os.environ if environment is None else environment
    if environment.get("MINECRAFT_EULA_ACCEPTED") != "TRUE":
        raise SecurityError(
            "BDS download refused: set MINECRAFT_EULA_ACCEPTED=TRUE only after independently reviewing "
            "and accepting the applicable Minecraft server terms"
        )


def artifact_root(cache: Path, artifact: Artifact, platform: str) -> Path:
    return cache / "bds" / artifact.version / platform


def _write_json(path: Path, value: object) -> None:
    temporary = path.with_name(path.name + ".partial")
    temporary.write_text(
        json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    temporary.replace(path)


def _inspect_and_record(
    lock: LockFile,
    artifact: Artifact,
    platform: str,
    root: Path,
    archive_path: Path,
    acquisition_method: str,
    supplied_filename: str | None = None,
) -> None:
    extracted = root / "extracted"
    _, file_list_hash = validate_zip(archive_path, platform)
    extract_safely(archive_path, extracted)
    executable = find_required_file(extracted, artifact.executable)
    executable_info = inspect_executable(executable, platform)
    artifact.archive_sha256 = sha256_file(archive_path)
    artifact.archive_size = archive_path.stat().st_size
    artifact.executable_sha256 = sha256_file(executable)
    artifact.executable_size = executable.stat().st_size
    artifact.file_format = executable_info.file_format
    artifact.architecture = executable_info.architecture
    artifact.package_file_list_hash = file_list_hash
    artifact.download_time_utc = utc_now()
    _write_json(
        root / "metadata.json",
        {
            "channel": lock.channel,
            "download_type": artifact.download_type,
            "version": artifact.version,
            "original_filename": artifact.original_filename,
            "resolved_download_url": artifact.download_url,
            "download_time_utc": artifact.download_time_utc,
            "acquisition_method": acquisition_method,
            "supplied_filename": supplied_filename,
        },
    )
    _write_json(
        root / "hashes.json",
        {
            "archive_sha256": artifact.archive_sha256,
            "archive_size": artifact.archive_size,
            "executable_sha256": artifact.executable_sha256,
            "executable_size": artifact.executable_size,
            "file_format": artifact.file_format,
            "architecture": artifact.architecture,
            "package_file_list_hash": artifact.package_file_list_hash,
        },
    )


def acquire(
    lock: LockFile,
    cache: Path,
    transport: HttpTransport | None = None,
    max_size: int = MAX_ARCHIVE_SIZE,
    require_existing_hashes: bool = False,
) -> None:
    require_eula()
    transport = transport or HttpTransport()
    for platform, artifact in lock.platforms.items():
        if require_existing_hashes and (
            not artifact.archive_sha256 or not artifact.executable_sha256
        ):
            raise ValidationError(
                f"fetch requires a complete lock with archive and executable hashes for {platform}"
            )
        root = artifact_root(cache, artifact, platform)
        root_existed = root.exists()
        archive_dir = root / "archive"
        extracted = root / "extracted"
        archive_path = archive_dir / artifact.original_filename
        if root.exists() and extracted.exists():
            verify_artifact(
                artifact,
                platform,
                cache,
                require_lock_hash=artifact.archive_sha256 is not None,
            )
            continue
        if root.exists() and any(root.iterdir()):
            raise SecurityError(
                f"refusing to overwrite non-empty artifact directory {root}"
            )
        archive_dir.mkdir(parents=True, exist_ok=True)
        partial = archive_path.with_name(archive_path.name + ".partial")
        try:
            response = transport.get_bytes(artifact.download_url, max_size=max_size)
            if response.body.lstrip().lower().startswith((b"<!doctype html", b"<html")):
                raise ValidationError("download is HTML, not a BDS ZIP")
            partial.write_bytes(response.body)
            archive_hash = sha256_file(partial)
            if artifact.archive_sha256 and archive_hash != artifact.archive_sha256:
                raise ValidationError(f"archive SHA-256 mismatch for {platform}")
            partial.replace(archive_path)
            artifact.download_url = response.final_url
            _inspect_and_record(
                lock, artifact, platform, root, archive_path, "official-download"
            )
        except Exception:
            partial.unlink(missing_ok=True)
            archive_path.unlink(missing_ok=True)
            if extracted.exists():
                shutil.rmtree(extracted)
            if not root_existed and root.exists():
                shutil.rmtree(root)
            raise


def import_local(
    lock: LockFile,
    cache: Path,
    sources: dict[str, Path],
    max_size: int = MAX_ARCHIVE_SIZE,
) -> None:
    """Validate and cache user-downloaded archives tied to freshly resolved official metadata."""
    require_eula()
    if set(sources) != set(lock.platforms):
        raise ValidationError(
            "local archive platforms must exactly match the resolved lock"
        )
    for platform, artifact in lock.platforms.items():
        source = sources[platform].resolve()
        if not source.is_file():
            raise ValidationError(f"local archive is missing for {platform}: {source}")
        if source.stat().st_size <= 0 or source.stat().st_size > max_size:
            raise ValidationError(f"local archive size is invalid for {platform}")
        root = artifact_root(cache, artifact, platform)
        root_existed = root.exists()
        archive_dir = root / "archive"
        extracted = root / "extracted"
        archive_path = archive_dir / artifact.original_filename
        if root.exists() and any(root.iterdir()):
            raise SecurityError(
                f"refusing to overwrite non-empty artifact directory {root}"
            )
        archive_dir.mkdir(parents=True, exist_ok=True)
        partial = archive_path.with_name(archive_path.name + ".partial")
        try:
            copied = 0
            with source.open("rb") as input_file, partial.open("xb") as output_file:
                while chunk := input_file.read(1024 * 1024):
                    copied += len(chunk)
                    if copied > max_size:
                        raise ValidationError(
                            f"local archive exceeds {max_size} byte limit"
                        )
                    output_file.write(chunk)
            partial.replace(archive_path)
            _inspect_and_record(
                lock,
                artifact,
                platform,
                root,
                archive_path,
                "user-supplied-official-page-download",
                source.name,
            )
        except Exception:
            partial.unlink(missing_ok=True)
            archive_path.unlink(missing_ok=True)
            if extracted.exists():
                shutil.rmtree(extracted)
            if not root_existed and root.exists():
                shutil.rmtree(root)
            raise


def verify_artifact(
    artifact: Artifact, platform: str, cache: Path, require_lock_hash: bool = True
) -> dict[str, object]:
    root = artifact_root(cache, artifact, platform)
    archive_path = root / "archive" / artifact.original_filename
    extracted = root / "extracted"
    if not archive_path.is_file() or not extracted.is_dir():
        raise ValidationError(f"cached {platform} artifact is incomplete at {root}")
    archive_hash = sha256_file(archive_path)
    if require_lock_hash and not artifact.archive_sha256:
        raise ValidationError(f"lock has no archive SHA-256 for {platform}")
    if artifact.archive_sha256 and archive_hash != artifact.archive_sha256:
        raise ValidationError(f"cached archive SHA-256 mismatch for {platform}")
    _, file_list_hash = validate_zip(archive_path, platform)
    executable = find_required_file(extracted, artifact.executable)
    executable_hash = sha256_file(executable)
    if require_lock_hash and not artifact.executable_sha256:
        raise ValidationError(f"lock has no executable SHA-256 for {platform}")
    if artifact.executable_sha256 and executable_hash != artifact.executable_sha256:
        raise ValidationError(f"cached executable SHA-256 mismatch for {platform}")
    info = inspect_executable(executable, platform)
    if (
        artifact.package_file_list_hash
        and file_list_hash != artifact.package_file_list_hash
    ):
        raise ValidationError(f"package file-list hash mismatch for {platform}")
    return {
        "platform": platform,
        "version": artifact.version,
        "archive_sha256": archive_hash,
        "executable_sha256": executable_hash,
        "file_format": info.file_format,
        "architecture": info.architecture,
        "status": "verified",
    }


def clean_partials(cache: Path) -> int:
    count = 0
    if not cache.exists():
        return count
    for path in sorted(cache.rglob("*.partial")):
        if path.is_file():
            path.unlink()
            count += 1
    for path in sorted(cache.rglob("*.partial-*"), reverse=True):
        if path.is_dir():
            shutil.rmtree(path)
            count += 1
    return count
