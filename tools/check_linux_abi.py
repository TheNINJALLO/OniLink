from __future__ import annotations

import argparse
from pathlib import Path
import re
import subprocess
import sys


MAXIMUM_GLIBC = "2.35"
_GLIBC_VERSION = re.compile(r"\bGLIBC_(\d+(?:\.\d+)+)\b")
_NEEDED_LIBRARY = re.compile(r"\(NEEDED\).*Shared library: \[([^\]]+)\]")


def version_tuple(value: str) -> tuple[int, ...]:
    if not re.fullmatch(r"\d+(?:\.\d+)+", value):
        raise ValueError(f"invalid version: {value}")
    return tuple(int(part) for part in value.split("."))


def glibc_versions(readelf_output: str) -> list[str]:
    return sorted(set(_GLIBC_VERSION.findall(readelf_output)), key=version_tuple)


def needed_libraries(readelf_output: str) -> list[str]:
    return sorted(set(_NEEDED_LIBRARY.findall(readelf_output)))


def forbidden_libstdcxx_symbols(readelf_output: str) -> list[str]:
    forbidden: set[str] = set()
    for line in readelf_output.splitlines():
        if " UND " not in line or ("__cxx11" not in line and "GLIBCXX_" not in line):
            continue
        for token in line.split():
            if "__cxx11" in token or "GLIBCXX_" in token:
                forbidden.add(token)
    return sorted(forbidden)


def _readelf(binary: Path, readelf: str, *arguments: str) -> str:
    result = subprocess.run(
        [readelf, *arguments, "--wide", str(binary)],
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip() or "readelf failed"
        raise ValueError(f"cannot inspect Linux plugin ABI: {detail}")
    return result.stdout


def inspect_glibc_requirements(
    binary: Path,
    maximum: str = MAXIMUM_GLIBC,
    readelf: str = "readelf",
) -> dict[str, object]:
    if not binary.is_file():
        raise ValueError(f"Linux plugin does not exist: {binary}")
    maximum_version = version_tuple(maximum)
    version_output = _readelf(binary, readelf, "--version-info")
    dynamic_output = _readelf(binary, readelf, "--dynamic")
    symbol_output = _readelf(binary, readelf, "--dyn-syms")
    versions = glibc_versions(version_output)
    if not versions:
        raise ValueError("Linux plugin has no readable GLIBC version requirements")
    highest = versions[-1]
    compatible = version_tuple(highest) <= maximum_version
    libraries = needed_libraries(dynamic_output)
    forbidden_symbols = forbidden_libstdcxx_symbols(symbol_output)
    cxx_runtime_compatible = (
        "libc++.so.1" in libraries
        and "libstdc++.so.6" not in libraries
        and not forbidden_symbols
    )
    return {
        "glibc_highest_required": highest,
        "glibc_policy_maximum": maximum,
        "glibc_versions_required": versions,
        "glibc_policy_passed": compatible,
        "cxx_runtime_policy": "libc++ only; no libstdc++ or unresolved std::__cxx11 symbols",
        "cxx_runtime_needed_libraries": libraries,
        "cxx_runtime_forbidden_symbols": forbidden_symbols,
        "cxx_runtime_policy_passed": cxx_runtime_compatible,
    }


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Reject an OniBridge ELF that violates the glibc or C++ runtime policy"
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
    print(
        "OniBridge C++ runtime requirement: "
        f"{', '.join(report['cxx_runtime_needed_libraries'])} "
        f"(libc++-only policy {'passed' if report['cxx_runtime_policy_passed'] else 'failed'})"
    )
    if not report["glibc_policy_passed"]:
        raise ValueError(
            f"{args.binary} requires GLIBC_{report['glibc_highest_required']}; "
            f"release policy allows at most GLIBC_{report['glibc_policy_maximum']}"
        )
    if not report["cxx_runtime_policy_passed"]:
        forbidden = ", ".join(report["cxx_runtime_forbidden_symbols"]) or "none listed"
        raise ValueError(
            "Linux plugin violates the libc++-only release policy; "
            f"needed={report['cxx_runtime_needed_libraries']}, forbidden symbols={forbidden}"
        )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError) as exception:
        print(f"check-linux-abi: error: {exception}", file=sys.stderr)
        raise SystemExit(2)
