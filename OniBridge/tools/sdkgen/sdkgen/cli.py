from __future__ import annotations

import argparse
from dataclasses import asdict
import json
from pathlib import Path
import sys

from .binary import BinaryError, load_image
from .auth import generate_auth_artifacts
from .adapter import generate_adapter
from .headers import generate_minimal_header
from .profile import (
    ProfileError,
    create_candidate,
    read_json,
    validate_profile,
    write_json,
)


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(prog="sdkgen")
    sub = root.add_subparsers(dest="command", required=True)
    inspect = sub.add_parser("inspect")
    inspect.add_argument("binary", type=Path)
    candidate = sub.add_parser("candidate")
    candidate.add_argument("binary", type=Path)
    candidate.add_argument("--version", required=True)
    candidate.add_argument(
        "--platform", choices=("linux-x86_64", "windows-x86_64"), required=True
    )
    candidate.add_argument("--signature", required=True)
    candidate.add_argument("--minimum-patch-length", type=int, default=5)
    candidate.add_argument("--role", default="pre-storage identity substitution")
    candidate.add_argument("--evidence", type=Path)
    candidate.add_argument("--output", type=Path, required=True)
    validate = sub.add_parser("validate-profile")
    validate.add_argument("profile", type=Path)
    validate.add_argument("binary", type=Path)
    validate.add_argument("--allow-candidate", action="store_true")
    headers = sub.add_parser("generate-headers")
    headers.add_argument("abi", type=Path)
    headers.add_argument("--output", type=Path, required=True)
    auth = sub.add_parser("auth-artifacts")
    auth.add_argument("binary", type=Path)
    auth.add_argument("--version", required=True)
    auth.add_argument(
        "--platform", choices=("linux-x86_64", "windows-x86_64"), required=True
    )
    auth.add_argument(
        "--validation-rva", type=lambda value: int(value, 0), required=True
    )
    auth.add_argument(
        "--validation-size", type=lambda value: int(value, 0), required=True
    )
    auth.add_argument("--call-rva", type=lambda value: int(value, 0), required=True)
    auth.add_argument("--helper-rva", type=lambda value: int(value, 0), required=True)
    auth.add_argument("--helper-size", type=lambda value: int(value, 0), required=True)
    auth.add_argument(
        "--authentication-size", type=lambda value: int(value, 0), required=True
    )
    auth.add_argument("--string-size", type=lambda value: int(value, 0), required=True)
    auth.add_argument("--signature", required=True)
    auth.add_argument("--xref", action="append", default=[], metavar="TEXT=RVA")
    auth.add_argument("--output", type=Path, required=True)
    adapter = sub.add_parser("generate-adapter")
    adapter.add_argument("profile", type=Path)
    adapter.add_argument("abi", type=Path)
    adapter.add_argument("--output", type=Path, required=True)
    return root


def main(argv: list[str] | None = None) -> int:
    try:
        args = parser().parse_args(argv)
        if args.command == "inspect":
            image = load_image(args.binary)
            value = {
                "format": image.file_format,
                "architecture": image.architecture,
                "abi": image.abi,
                "sha256": image.sha256,
                "size": len(image.data),
                "sections": [asdict(section) for section in image.sections],
            }
            print(json.dumps(value, indent=2, sort_keys=True))
        elif args.command == "candidate":
            evidence = read_json(args.evidence) if args.evidence else None
            value = create_candidate(
                args.binary,
                args.version,
                args.platform,
                args.signature,
                args.minimum_patch_length,
                args.role,
                evidence,
            )
            write_json(args.output, value)
            print(
                json.dumps(
                    {
                        "output": str(args.output),
                        "status": value["validation_status"],
                        "blockers": value["release_blockers"],
                    },
                    indent=2,
                )
            )
            if value["validation_status"] != "production":
                return 3
        elif args.command == "validate-profile":
            validate_profile(
                read_json(args.profile),
                args.binary,
                production=not args.allow_candidate,
            )
            print(
                json.dumps({"status": "valid", "profile": str(args.profile)}, indent=2)
            )
        elif args.command == "generate-headers":
            generate_minimal_header(read_json(args.abi), args.output)
            print(
                json.dumps(
                    {"status": "generated", "header": str(args.output)}, indent=2
                )
            )
        elif args.command == "auth-artifacts":
            xrefs = {}
            for value in args.xref:
                if "=" not in value:
                    raise ProfileError("--xref must use TEXT=RVA")
                key, raw_rva = value.rsplit("=", 1)
                if not key or key in xrefs:
                    raise ProfileError("--xref keys must be non-empty and unique")
                xrefs[key] = int(raw_rva, 0)
            if set(xrefs) != {"auth", "uuid", "xid", "realms"}:
                raise ProfileError(
                    "auth analysis requires auth, uuid, xid, and realms xrefs"
                )
            paths = generate_auth_artifacts(
                args.binary,
                args.version,
                args.platform,
                args.validation_rva,
                args.validation_size,
                args.call_rva,
                args.helper_rva,
                args.helper_size,
                args.authentication_size,
                args.string_size,
                args.signature,
                xrefs,
                args.output,
            )
            print(
                json.dumps(
                    {
                        "status": "candidate",
                        "outputs": {key: str(value) for key, value in paths.items()},
                    },
                    indent=2,
                )
            )
            return 3
        elif args.command == "generate-adapter":
            generate_adapter(read_json(args.profile), read_json(args.abi), args.output)
            print(
                json.dumps(
                    {"status": "generated", "adapter": str(args.output)}, indent=2
                )
            )
        return 0
    except (
        OSError,
        ValueError,
        BinaryError,
        ProfileError,
        json.JSONDecodeError,
    ) as exc:
        print(f"sdkgen: error: {exc}", file=sys.stderr)
        return 2
