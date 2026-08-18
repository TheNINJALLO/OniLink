from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

from .errors import BdsCtlError
from .metadata import resolve
from .model import DOWNLOAD_TYPES, LockFile, read_lock, write_lock
from .store import acquire, artifact_root, clean_partials, import_local, verify_artifact


def selected_platforms(value: str) -> tuple[str, ...]:
    if value == "both":
        return ("linux-x86_64", "windows-x86_64")
    return (f"{value}-x86_64",)


def add_resolution_options(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--channel", choices=tuple(DOWNLOAD_TYPES), default="stable")
    parser.add_argument("--platform", choices=("linux", "windows", "both"), default="both")


def create_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="bdsctl", description="Secure, reproducible official BDS artifact manager")
    parser.add_argument("--cache", type=Path, default=Path(".cache"))
    sub = parser.add_subparsers(dest="command", required=True)
    resolve_parser = sub.add_parser("resolve", help="resolve official metadata without downloading BDS")
    add_resolution_options(resolve_parser)
    resolve_parser.add_argument("--output", type=Path)
    lock_parser = sub.add_parser("lock", help="resolve, download, inspect, and write a complete lock")
    add_resolution_options(lock_parser)
    lock_parser.add_argument("--output", type=Path, default=Path("bds.lock.json"))
    import_parser = sub.add_parser(
        "import-local", help="validate user-downloaded official Linux and Windows archives and write a complete lock")
    import_parser.add_argument("--channel", choices=tuple(DOWNLOAD_TYPES), default="stable")
    import_parser.add_argument("--linux", type=Path, required=True)
    import_parser.add_argument("--windows", type=Path, required=True)
    import_parser.add_argument("--output", type=Path, default=Path("bds.lock.json"))
    for name in ("fetch", "inspect", "verify"):
        command = sub.add_parser(name)
        command.add_argument("--lock", type=Path, default=Path("bds.lock.json"))
    clean = sub.add_parser("clean", help="remove incomplete transfers and extraction staging directories")
    clean.add_argument("--partials", action="store_true", default=True)
    status = sub.add_parser("status", help="show cache state for a lock")
    status.add_argument("--lock", type=Path, default=Path("bds.lock.json"))
    return parser


def emit(value: object) -> None:
    print(json.dumps(value, indent=2, sort_keys=True))


def run(args: argparse.Namespace) -> int:
    if args.command == "resolve":
        lock = resolve(args.channel, selected_platforms(args.platform))
        if args.output:
            write_lock(args.output, lock)
        emit(lock.to_dict())
        return 0
    if args.command == "lock":
        lock = resolve(args.channel, selected_platforms(args.platform))
        acquire(lock, args.cache)
        write_lock(args.output, lock)
        emit(lock.to_dict())
        return 0
    if args.command == "import-local":
        lock = resolve(args.channel, ("linux-x86_64", "windows-x86_64"))
        import_local(lock, args.cache, {
            "linux-x86_64": args.linux,
            "windows-x86_64": args.windows,
        })
        write_lock(args.output, lock)
        emit(lock.to_dict())
        return 0
    if args.command == "clean":
        emit({"removed_partial_entries": clean_partials(args.cache)})
        return 0
    lock: LockFile = read_lock(args.lock)
    if args.command == "fetch":
        acquire(lock, args.cache, require_existing_hashes=True)
        emit({"status": "available", "platforms": sorted(lock.platforms)})
        return 0
    if args.command in {"inspect", "verify"}:
        results = [verify_artifact(artifact, platform, args.cache) for platform, artifact in lock.platforms.items()]
        emit({"lock": str(args.lock), "artifacts": results})
        return 0
    if args.command == "status":
        entries = []
        for platform, artifact in lock.platforms.items():
            root = artifact_root(args.cache, artifact, platform)
            entries.append({
                "platform": platform,
                "version": artifact.version,
                "archive": (root / "archive" / artifact.original_filename).is_file(),
                "extracted": (root / "extracted").is_dir(),
                "path": str(root),
            })
        emit({"channel": lock.channel, "paired_version": lock.paired_version, "artifacts": entries})
        return 0
    raise AssertionError(args.command)


def main(argv: list[str] | None = None) -> int:
    try:
        return run(create_parser().parse_args(argv))
    except (BdsCtlError, ValueError, OSError) as exc:
        print(f"bdsctl: error: {exc}", file=sys.stderr)
        return 2
