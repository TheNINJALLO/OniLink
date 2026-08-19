from __future__ import annotations

from dataclasses import dataclass
import hashlib
import os
from pathlib import Path, PurePosixPath
import shutil
import stat
import struct
import tempfile
import zipfile

from .errors import SecurityError, ValidationError


REQUIRED_COMMON = {"server.properties", "allowlist.json", "permissions.json"}
MAX_EXTRACTED_SIZE = 8 * 1024 * 1024 * 1024


@dataclass(frozen=True, slots=True)
class ExecutableInfo:
    file_format: str
    architecture: str


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def safe_member_name(name: str) -> PurePosixPath:
    if "\\" in name or "\x00" in name:
        raise SecurityError(f"unsafe ZIP member name {name!r}")
    path = PurePosixPath(name)
    if path.is_absolute() or any(part in {"", ".", ".."} for part in path.parts):
        raise SecurityError(f"unsafe ZIP member path {name!r}")
    if path.parts and ":" in path.parts[0]:
        raise SecurityError(f"drive-qualified ZIP member path {name!r}")
    return path


def validate_zip(path: Path, platform: str) -> tuple[list[zipfile.ZipInfo], str]:
    try:
        with zipfile.ZipFile(path) as archive:
            infos = archive.infolist()
            if not infos:
                raise ValidationError("BDS ZIP is empty")
            normalized: list[tuple[str, int]] = []
            basenames: set[str] = set()
            extracted_size = 0
            for info in infos:
                member = safe_member_name(info.filename)
                mode = info.external_attr >> 16
                if stat.S_ISLNK(mode):
                    raise SecurityError(
                        f"symlink ZIP member is forbidden: {info.filename}"
                    )
                normalized.append((member.as_posix(), info.file_size))
                extracted_size += info.file_size
                if extracted_size > MAX_EXTRACTED_SIZE:
                    raise ValidationError(
                        f"BDS ZIP expands beyond the {MAX_EXTRACTED_SIZE} byte limit"
                    )
                if not info.is_dir():
                    basenames.add(member.name)
            executable = (
                "bedrock_server" if platform == "linux-x86_64" else "bedrock_server.exe"
            )
            required = REQUIRED_COMMON | {executable}
            missing = required - basenames
            if missing:
                raise ValidationError(
                    f"BDS package is missing required files: {sorted(missing)}"
                )
            bad = archive.testzip()
            if bad is not None:
                raise ValidationError(f"ZIP CRC validation failed for {bad}")
    except zipfile.BadZipFile as exc:
        raise ValidationError(f"invalid BDS ZIP: {exc}") from exc
    digest = hashlib.sha256()
    for name, size in sorted(normalized):
        digest.update(name.encode("utf-8"))
        digest.update(b"\0")
        digest.update(str(size).encode("ascii"))
        digest.update(b"\n")
    return infos, digest.hexdigest()


def inspect_executable(path: Path, platform: str) -> ExecutableInfo:
    data = path.read_bytes()[:4096]
    if platform == "linux-x86_64":
        if len(data) < 20 or data[:4] != b"\x7fELF":
            raise ValidationError("Linux executable is not ELF")
        if data[4] != 2:
            raise ValidationError("Linux executable is not ELF64")
        byte_order = "little" if data[5] == 1 else "big" if data[5] == 2 else None
        if byte_order is None or int.from_bytes(data[18:20], byte_order) != 62:
            raise ValidationError("Linux executable is not x86_64")
        return ExecutableInfo("ELF64", "x86_64")
    if len(data) < 64 or data[:2] != b"MZ":
        raise ValidationError("Windows executable is not PE")
    pe_offset = struct.unpack_from("<I", data, 0x3C)[0]
    if pe_offset + 26 > len(data) or data[pe_offset : pe_offset + 4] != b"PE\0\0":
        raise ValidationError("Windows executable has an invalid PE header")
    machine = struct.unpack_from("<H", data, pe_offset + 4)[0]
    optional_magic = struct.unpack_from("<H", data, pe_offset + 24)[0]
    if machine != 0x8664 or optional_magic != 0x20B:
        raise ValidationError("Windows executable is not PE32+ x86_64")
    return ExecutableInfo("PE32+", "x86_64")


def extract_safely(archive_path: Path, destination: Path) -> None:
    if destination.exists():
        raise SecurityError(
            f"refusing to overwrite existing extraction directory {destination}"
        )
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(
        tempfile.mkdtemp(prefix=destination.name + ".partial-", dir=destination.parent)
    )
    try:
        with zipfile.ZipFile(archive_path) as archive:
            for info in archive.infolist():
                member = safe_member_name(info.filename)
                mode = info.external_attr >> 16
                if stat.S_ISLNK(mode):
                    raise SecurityError(
                        f"symlink ZIP member is forbidden: {info.filename}"
                    )
                target = temporary.joinpath(*member.parts)
                resolved = target.resolve()
                if (
                    temporary.resolve() not in resolved.parents
                    and resolved != temporary.resolve()
                ):
                    raise SecurityError(
                        f"ZIP member escapes extraction root: {info.filename}"
                    )
                if info.is_dir():
                    target.mkdir(parents=True, exist_ok=True)
                else:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with archive.open(info) as source, target.open("xb") as sink:
                        shutil.copyfileobj(source, sink, length=1024 * 1024)
                    if mode & stat.S_IXUSR:
                        target.chmod(target.stat().st_mode | stat.S_IXUSR)
        temporary.replace(destination)
    except Exception:
        shutil.rmtree(temporary, ignore_errors=True)
        raise


def find_required_file(root: Path, name: str) -> Path:
    matches = [path for path in root.rglob(name) if path.is_file()]
    if len(matches) != 1:
        raise ValidationError(
            f"expected exactly one {name!r} in extracted package, found {len(matches)}"
        )
    return matches[0]
