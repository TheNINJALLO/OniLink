from __future__ import annotations

from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from pathlib import Path
import json
import re
from typing import Any


PLATFORMS = ("linux-x86_64", "windows-x86_64")
DOWNLOAD_TYPES = {
    "stable": {
        "linux-x86_64": "serverBedrockLinux",
        "windows-x86_64": "serverBedrockWindows",
    },
    "preview": {
        "linux-x86_64": "serverBedrockPreviewLinux",
        "windows-x86_64": "serverBedrockPreviewWindows",
    },
}


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


@dataclass(slots=True)
class Artifact:
    download_type: str
    version: str
    original_filename: str
    download_url: str
    executable: str
    archive_sha256: str | None = None
    archive_size: int | None = None
    executable_sha256: str | None = None
    executable_size: int | None = None
    file_format: str | None = None
    architecture: str | None = None
    package_file_list_hash: str | None = None
    download_time_utc: str | None = None

    def validate(self, platform: str) -> None:
        if platform not in PLATFORMS:
            raise ValueError(f"unsupported platform {platform!r}")
        if not isinstance(self.version, str) or not re.fullmatch(
            r"\d+\.\d+\.\d+(?:\.\d+)?", self.version
        ):
            raise ValueError("artifact version must be a three- or four-part version")
        if (
            not isinstance(self.original_filename, str)
            or self.original_filename in {"", ".", ".."}
            or any(c in self.original_filename for c in "/\\:\x00")
            or self.original_filename.endswith((".", " "))
        ):
            raise ValueError("artifact filename must be a plain filename")
        expected = (
            "bedrock_server" if platform == "linux-x86_64" else "bedrock_server.exe"
        )
        if self.executable != expected:
            raise ValueError(f"{platform} executable must be {expected!r}")
        for name in ("archive_sha256", "executable_sha256", "package_file_list_hash"):
            value = getattr(self, name)
            if value is not None and (
                not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{64}", value)
            ):
                raise ValueError(f"invalid {name}")
        for name in ("archive_size", "executable_size"):
            value = getattr(self, name)
            if value is not None and (type(value) is not int or value <= 0):
                raise ValueError(f"invalid {name}")

    @classmethod
    def from_dict(cls, value: dict[str, Any]) -> "Artifact":
        if not isinstance(value, dict):
            raise ValueError("artifact must be an object")
        allowed = set(cls.__dataclass_fields__)
        unknown = set(value) - allowed
        if unknown:
            raise ValueError(f"unknown artifact keys: {sorted(unknown)}")
        return cls(**value)


@dataclass(slots=True)
class LockFile:
    schema: int
    channel: str
    resolved_at_utc: str
    paired_version: bool
    platforms: dict[str, Artifact] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        result = asdict(self)
        return result

    @classmethod
    def from_dict(cls, value: dict[str, Any]) -> "LockFile":
        expected = {
            "schema",
            "channel",
            "resolved_at_utc",
            "paired_version",
            "platforms",
        }
        if set(value) != expected:
            raise ValueError(f"lock keys must be exactly {sorted(expected)}")
        if type(value["schema"]) is not int or value["schema"] != 1:
            raise ValueError(f"unsupported lock schema {value['schema']!r}")
        if value["channel"] not in DOWNLOAD_TYPES:
            raise ValueError(f"unsupported channel {value['channel']!r}")
        platforms = value["platforms"]
        if not isinstance(platforms, dict) or not platforms:
            raise ValueError("lock platforms must be a non-empty object")
        unknown = set(platforms) - set(PLATFORMS)
        if unknown:
            raise ValueError(f"unsupported platforms: {sorted(unknown)}")
        artifacts = {name: Artifact.from_dict(item) for name, item in platforms.items()}
        for name, artifact in artifacts.items():
            artifact.validate(name)
            wanted = DOWNLOAD_TYPES[value["channel"]][name]
            if artifact.download_type != wanted:
                raise ValueError(
                    f"{name} uses {artifact.download_type!r}; expected {wanted!r} for {value['channel']}"
                )
        paired = len({artifact.version for artifact in artifacts.values()}) == 1
        if (
            type(value["paired_version"]) is not bool
            or value["paired_version"] != paired
        ):
            raise ValueError("paired_version does not match the platform versions")
        return cls(
            schema=1,
            channel=value["channel"],
            resolved_at_utc=value["resolved_at_utc"],
            paired_version=bool(value["paired_version"]),
            platforms=artifacts,
        )


def read_lock(path: Path) -> LockFile:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(value, dict):
            raise ValueError("lock root must be an object")
        return LockFile.from_dict(value)
    except (OSError, json.JSONDecodeError, TypeError, ValueError) as exc:
        raise ValueError(f"invalid lock file {path}: {exc}") from exc


def write_lock(path: Path, lock: LockFile) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".partial")
    temporary.write_text(
        json.dumps(lock.to_dict(), indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    temporary.replace(path)
