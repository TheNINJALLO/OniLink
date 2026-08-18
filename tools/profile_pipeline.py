from __future__ import annotations

import argparse
import json
from pathlib import Path
import shutil
import subprocess
import sys


def read(path: Path) -> dict:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"{path} root must be an object")
    return value


def executable(cache: Path, artifact: dict, platform: str) -> Path:
    root = cache / "bds" / artifact["version"] / platform / "extracted"
    matches = list(root.rglob(artifact["executable"]))
    if len(matches) != 1:
        raise ValueError(f"expected one extracted executable for {platform}, found {len(matches)}")
    return matches[0]


def run(command: list[str], allowed: tuple[int, ...] = (0,)) -> None:
    result = subprocess.run(command, check=False)
    if result.returncode not in allowed:
        raise subprocess.CalledProcessError(result.returncode, command)


def generate(lock: dict, cache: Path) -> None:
    for platform, artifact in lock["platforms"].items():
        version = artifact["version"]
        recipe_path = Path("OniBridge/analysis-recipes") / version / f"{platform}.json"
        if not recipe_path.is_file():
            raise ValueError(f"human-reviewed analysis recipe is missing: {recipe_path}")
        recipe = read(recipe_path)
        binary = executable(cache, artifact, platform)
        output = Path("OniBridge/generated/bds") / version / platform
        command = [
            sys.executable, "-m", "sdkgen", "auth-artifacts", str(binary),
            "--version", version, "--platform", platform,
            "--validation-rva", str(recipe["validation_rva"]),
            "--validation-size", str(recipe["validation_size"]),
            "--call-rva", str(recipe["call_rva"]),
            "--helper-rva", str(recipe["helper_rva"]),
            "--helper-size", str(recipe["helper_size"]),
            "--authentication-size", str(recipe["authentication_size"]),
            "--string-size", str(recipe["string_size"]),
            "--signature", recipe["masked_byte_signature"],
            "--output", str(output),
        ]
        for name, rva in recipe["login_json_string_xrefs"].items():
            command.extend(("--xref", f"{name}={rva}"))
        run(command, allowed=(0, 3))
        run([sys.executable, "-m", "sdkgen", "generate-headers", str(output / "abi.json"),
             "--output", str(output / "include/onibridge/bds_abi.hpp")])
        run([sys.executable, "-m", "sdkgen", "generate-adapter", str(output / "profile.json"),
             str(output / "abi.json"), "--output", str(output / "adapter.cpp")])
        profile = Path("OniBridge/profiles") / version / f"{platform}.json"
        profile.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(output / "profile.json", profile)


def validate(lock: dict, cache: Path, allow_candidate: bool) -> None:
    for platform, artifact in lock["platforms"].items():
        profile = Path("OniBridge/profiles") / artifact["version"] / f"{platform}.json"
        if not profile.is_file():
            raise ValueError(f"compatibility profile is missing: {profile}")
        command = [sys.executable, "-m", "sdkgen", "validate-profile", str(profile),
                   str(executable(cache, artifact, platform))]
        if allow_candidate:
            command.append("--allow-candidate")
        run(command)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("generate", "validate"))
    parser.add_argument("--lock", type=Path, required=True)
    parser.add_argument("--cache", type=Path, default=Path(".cache"))
    parser.add_argument("--allow-candidate", action="store_true")
    args = parser.parse_args()
    lock = read(args.lock)
    if args.command == "generate":
        generate(lock, args.cache)
    else:
        validate(lock, args.cache, args.allow_candidate)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, KeyError, json.JSONDecodeError, subprocess.CalledProcessError) as exc:
        print(f"profile-pipeline: error: {exc}", file=sys.stderr)
        raise SystemExit(2)
