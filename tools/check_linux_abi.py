from __future__ import annotations

import argparse
from pathlib import Path
import re
import subprocess
import sys


MAXIMUM_GLIBC = "2.35"
_GLIBC_VERSION = re.compile(r"\bGLIBC_(\d+(?:\.\d+)+)\b")


def version_tuple(value: str) -> tuple[int, ...]:
    if not re.fullmatch(r"\d+(?:\.\d+)+", value):
        raise ValueError(f"invalid version: {value}")
    return tuple(int(part) for part in value.split("."))


def glibc_versions(readelf_output: str) -> list[str]:
    return sorted(set(_GLIBC_VERSION.findall(readelf_output)), key=version_tuple)


def inspect_glibc_requirements(
    binary: Path,
    maximum: str = MAXIMUM_GLIBC,
    readelf: str = "readelf",
) -> dict[str, object]:
    if not binary.is_file():
        raise ValueError(f"Linux plugin does not exist: {binary}")
    maximum_version = version_tuple(maximum)
    result = subprocess.run(
        [readelf, "--version-info", "--wide", str(binary)],
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip() or "readelf failed"
        raise ValueError(f"cannot inspect Linux plugin ABI: {detail}")
    versions = glibc_versions(result.stdout)
    if not versions:
        raise ValueError("Linux plugin has no readable GLIBC version requirements")
    highest = versions[-1]
    compatible = version_tuple(highest) <= maximum_version
    return {
        "glibc_highest_required": highest,
        "glibc_policy_maximum": maximum,
        "glibc_versions_required": versions,
        "glibc_policy_passed": compatible,
    }


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Reject an OniBridge ELF that requires a newer glibc than the runtime policy"
    )
    parser.add_argument("binary", type=Path)
    parser.add_argument("--maximum-glibc", default=MAXIMUM_GLIBC)
    parser.add_argument("--readelf", default="readelf")
    args = parser.parse_args()
    report = inspect_glibc_requirements(args.binary, args.maximum_glibc, args.readelf)
    print(
        "OniBridge GLIBC requirement: "
        f"{report['glibc_highest_required']} "
        f"(policy maximum {report['glibc_policy_maximum']})"
    )
    if not report["glibc_policy_passed"]:
        raise ValueError(
            f"{args.binary} requires GLIBC_{report['glibc_highest_required']}; "
            f"release policy allows at most GLIBC_{report['glibc_policy_maximum']}"
        )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError) as exception:
        print(f"check-linux-abi: error: {exception}", file=sys.stderr)
        raise SystemExit(2)
