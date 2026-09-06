"""Read-only verification of server file sets before importing or changing a backend."""

from __future__ import annotations

import hashlib
from pathlib import Path, PurePosixPath
import zipfile

from .archive import inspect_executable_header, sha256_file, validate_zip
from .errors import ValidationError
from .model import LockFile
from .store import MAX_ARCHIVE_SIZE, _checked_inspection


def verify_local(lock: LockFile, sources: dict[str, Path]) -> list[dict]:
    if set(sources) != set(lock.platforms):
        raise ValidationError("local archive platforms must exactly match the lock")
    results = []
    for platform, artifact in lock.platforms.items():
        artifact.validate(platform)
        if not artifact.archive_sha256 or not artifact.executable_sha256:
            raise ValidationError(
                "local verification requires archive and executable hashes"
            )
        source = sources[platform]
        size = source.stat().st_size
        if size <= 0 or size > MAX_ARCHIVE_SIZE:
            raise ValidationError(f"local archive size is invalid for {platform}")
        archive_hash = sha256_file(source)
        if archive_hash != artifact.archive_sha256:
            raise ValidationError(f"archive SHA-256 mismatch for {platform}")
        infos, file_list_hash = validate_zip(source, platform)
        matches = [
            info
            for info in infos
            if not info.is_dir()
            and PurePosixPath(info.filename).name == artifact.executable
        ]
        if len(matches) != 1:
            raise ValidationError(
                f"expected exactly one {artifact.executable} in {platform} archive"
            )
        digest = hashlib.sha256()
        with zipfile.ZipFile(source) as archive, archive.open(matches[0]) as binary:
            header = binary.read(4096)
            executable_info = inspect_executable_header(header, platform)
            digest.update(header)
            while chunk := binary.read(1024 * 1024):
                digest.update(chunk)
        values = {
            "platform": platform,
            "version": artifact.version,
            "archive_sha256": archive_hash,
            "archive_size": size,
            "executable_sha256": digest.hexdigest(),
            "executable_size": matches[0].file_size,
            "file_format": executable_info.file_format,
            "architecture": executable_info.architecture,
            "package_file_list_hash": file_list_hash,
            "status": "verified",
        }
        _checked_inspection(artifact, values)
        results.append(values)
    return results
