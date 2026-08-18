from __future__ import annotations

import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import shutil
import sys
import zipfile

FORBIDDEN_NAMES = {"bedrock_server", "bedrock_server.exe", "server.properties", "allowlist.json", "permissions.json"}
FORBIDDEN_SUFFIXES = {".pdb", ".dmp"}
MAX_ARCHIVE_DEPTH = 4
MAX_ARCHIVE_MEMBER = 512 * 1024 * 1024
MAX_ARCHIVE_EXPANDED = 1024 * 1024 * 1024
SAFE_RELEASE_TOKEN = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}\Z")


def reject_name(name: str) -> None:
    normalized = name.replace("\\", "/")
    nested = Path(normalized)
    if nested.name.lower() in FORBIDDEN_NAMES or nested.suffix.lower() in FORBIDDEN_SUFFIXES:
        raise ValueError(f"forbidden BDS-owned/restricted release file: {name}")


def inspect_zip(source: Path | io.BytesIO, label: str, depth: int = 0) -> None:
    if depth > MAX_ARCHIVE_DEPTH:
        raise ValueError(f"nested archive depth exceeds {MAX_ARCHIVE_DEPTH}: {label}")
    expanded = 0
    with zipfile.ZipFile(source) as archive:
        for info in archive.infolist():
            reject_name(info.filename)
            expanded += info.file_size
            if info.file_size > MAX_ARCHIVE_MEMBER or expanded > MAX_ARCHIVE_EXPANDED:
                raise ValueError(f"archive expansion limit exceeded: {label}")
            if info.is_dir() or info.file_size == 0:
                continue
            with archive.open(info) as member:
                data = member.read(MAX_ARCHIVE_MEMBER + 1)
            if len(data) > MAX_ARCHIVE_MEMBER:
                raise ValueError(f"archive member exceeds limit: {label}!{info.filename}")
            nested_source = io.BytesIO(data)
            if zipfile.is_zipfile(nested_source):
                nested_source.seek(0)
                inspect_zip(nested_source, f"{label}!{info.filename}", depth + 1)


def reject_forbidden(path: Path) -> None:
    reject_name(path.name)
    if zipfile.is_zipfile(path):
        inspect_zip(path, str(path))


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            value.update(chunk)
    return value.hexdigest()


def validate_release_token(label: str, value: str) -> None:
    if not SAFE_RELEASE_TOKEN.fullmatch(value):
        raise ValueError(f"{label} contains unsafe characters")


def read_json(path: Path) -> dict:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"JSON root must be an object: {path}")
    return value


def profile_paths(version: str) -> dict[str, Path]:
    root = Path("OniBridge/profiles") / version
    return {
        "linux-x86_64": root / "linux-x86_64.json",
        "windows-x86_64": root / "windows-x86_64.json",
    }


def create_profile_bundle(version: str, destination: Path) -> None:
    profiles = profile_paths(version)
    missing = [str(path) for path in profiles.values() if not path.is_file()]
    if missing:
        raise ValueError(f"compatibility profiles are missing: {missing}")
    with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for profile in profiles.values():
            reject_forbidden(profile)
            archive.write(profile, arcname=f"profiles/{version}/{profile.name}")
            runtime = profile.with_name(profile.stem + ".runtime.json")
            if runtime.is_file():
                reject_forbidden(runtime)
                archive.write(runtime, arcname=f"profiles/{version}/{runtime.name}")
    reject_forbidden(destination)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", required=True)
    parser.add_argument("--bds-version", required=True)
    parser.add_argument("--dist", type=Path, default=Path("dist"))
    args = parser.parse_args()
    validate_release_token("version", args.version)
    validate_release_token("BDS version", args.bds_version)
    expected = {
        "OniLink.jar": Path("OniLink/dist/OniLink.jar"),
        "OniBridge-Geyser.jar": Path("OniBridge-Geyser/dist/OniBridge-Geyser.jar"),
        "egg-onilink.json": Path("packaging/pterodactyl/egg-onilink.json"),
        "start-onilink.sh": Path("packaging/pterodactyl/start-onilink.sh"),
        f"onibridge-{args.version}-bds-{args.bds_version}-linux-x86_64.so": Path("OniBridge/build/linux-release/onibridge.so"),
        f"onibridge-{args.version}-bds-{args.bds_version}-windows-x86_64.dll": Path("OniBridge/build/windows-release/onibridge.dll"),
    }
    missing = [str(path) for path in expected.values() if not path.is_file()]
    if missing:
        raise ValueError(f"release inputs are missing: {missing}")
    args.dist.mkdir(parents=True, exist_ok=True)
    outputs = []
    for name, source in expected.items():
        reject_forbidden(source)
        destination = args.dist / name
        shutil.copy2(source, destination)
        reject_forbidden(destination)
        outputs.append(destination)
    profile_bundle = args.dist / f"onibridge-profiles-{args.bds_version}.zip"
    create_profile_bundle(args.bds_version, profile_bundle)
    outputs.append(profile_bundle)
    lock = read_json(Path("OniBridge/bds.lock.json"))
    profiles = {platform: read_json(path) for platform, path in profile_paths(args.bds_version).items()}
    compatibility = []
    for platform, profile in profiles.items():
        locked = lock.get("platforms", {}).get(platform)
        if not isinstance(locked, dict) or locked.get("version") != args.bds_version:
            raise ValueError(f"lock does not contain {platform} BDS {args.bds_version}")
        if profile.get("executable_sha256") != locked.get("executable_sha256"):
            raise ValueError(f"{platform} profile hash does not match the BDS lock")
        evidence = profile.get("evidence", {})
        runtime_path = profile_paths(args.bds_version)[platform].with_name(platform + ".runtime.json")
        runtime = read_json(runtime_path) if runtime_path.is_file() else {"status": "not-run"}
        if runtime.get("bds_executable_sha256", locked["executable_sha256"]) != locked["executable_sha256"]:
            raise ValueError(f"{platform} runtime evidence hash does not match the BDS lock")
        compatibility.append({
            "platform": platform,
            "bds_version": args.bds_version,
            "bds_executable_sha256": locked["executable_sha256"],
            "bds_executable_size": locked["executable_size"],
            "bds_archive_sha256": locked["archive_sha256"],
            "architecture": profile.get("architecture"),
            "abi": profile.get("abi"),
            "endstone_version": "0.11.9",
            "endstone_commit": "a73f76d3725b471a6d83783166edc004804faa1b",
            "profile_id": f"bds-{args.bds_version}-{platform}-{locked['executable_sha256'][:16]}",
            "profile_status": profile.get("validation_status"),
            "release_blockers": profile.get("release_blockers", []),
            "hook_harness_passed": bool(evidence.get("hook_harness_passed")),
            "human_reviewed": bool(evidence.get("human_reviewed")),
            "live_tested": bool(evidence.get("live_tested")),
            "runtime_lifecycle_test": runtime,
            "command_compatibility": "unit-tested; live Endstone fixtures not run",
        })
    production = all(row["profile_status"] == "production" for row in compatibility)
    manifest = {
        "schema": 1,
        "release_status": "production" if production else "candidate-awaiting-validation",
        "production_ready": production,
        "onibridge_version": args.version,
        "bds_version": args.bds_version,
        "bds_lock_resolved_at_utc": lock.get("resolved_at_utc"),
        "compatibility": compatibility,
        "artifacts": [{"name": path.name, "sha256": digest(path), "size": path.stat().st_size} for path in outputs],
    }
    manifest_path = args.dist / "compatibility-manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    outputs.append(manifest_path)
    (args.dist / "SHA256SUMS").write_text(
        "".join(f"{digest(path)}  {path.name}\n" for path in outputs), encoding="utf-8")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError) as exc:
        print(f"package: error: {exc}", file=sys.stderr)
        raise SystemExit(2)
