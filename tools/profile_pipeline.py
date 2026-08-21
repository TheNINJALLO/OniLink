from __future__ import annotations

import argparse
import json
from pathlib import Path
import shutil
import subprocess
import sys


REQUIRED_GENERATED_FILES = (
    "abi.json",
    "adapter.cpp",
    "include/onibridge/bds_abi.hpp",
    "profile.json",
    "report.md",
    "signatures.json",
    "symbols.json",
)
REQUIRED_PRODUCTION_EVIDENCE = (
    "abi_layout_validated",
    "calling_convention_validated",
    "control_flow_validated",
    "cross_references_validated",
    "function_boundary_validated",
    "hook_harness_passed",
    "human_reviewed",
    "instruction_decode_validated",
    "live_tested",
    "trampoline_relocation_validated",
)


def read(path: Path) -> dict:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"{path} root must be an object")
    return value


def executable(cache: Path, artifact: dict, platform: str) -> Path:
    root = cache / "bds" / artifact["version"] / platform / "extracted"
    matches = list(root.rglob(artifact["executable"]))
    if len(matches) != 1:
        raise ValueError(
            f"expected one extracted executable for {platform}, found {len(matches)}"
        )
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
            raise ValueError(
                f"human-reviewed analysis recipe is missing: {recipe_path}"
            )
        recipe = read(recipe_path)
        binary = executable(cache, artifact, platform)
        output = Path("OniBridge/generated/bds") / version / platform
        command = [
            sys.executable,
            "-m",
            "sdkgen",
            "auth-artifacts",
            str(binary),
            "--version",
            version,
            "--platform",
            platform,
            "--validation-rva",
            str(recipe["validation_rva"]),
            "--validation-size",
            str(recipe["validation_size"]),
            "--call-rva",
            str(recipe["call_rva"]),
            "--helper-rva",
            str(recipe["helper_rva"]),
            "--helper-size",
            str(recipe["helper_size"]),
            "--authentication-size",
            str(recipe["authentication_size"]),
            "--string-size",
            str(recipe["string_size"]),
            "--signature",
            recipe["masked_byte_signature"],
            "--output",
            str(output),
        ]
        for name, rva in recipe["login_json_string_xrefs"].items():
            command.extend(("--xref", f"{name}={rva}"))
        run(command, allowed=(0, 3))
        run(
            [
                sys.executable,
                "-m",
                "sdkgen",
                "generate-headers",
                str(output / "abi.json"),
                "--output",
                str(output / "include/onibridge/bds_abi.hpp"),
            ]
        )
        run(
            [
                sys.executable,
                "-m",
                "sdkgen",
                "generate-adapter",
                str(output / "profile.json"),
                str(output / "abi.json"),
                "--output",
                str(output / "adapter.cpp"),
            ]
        )
        profile = Path("OniBridge/profiles") / version / f"{platform}.json"
        profile.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(output / "profile.json", profile)


def validate(lock: dict, cache: Path, allow_candidate: bool) -> None:
    for platform, artifact in lock["platforms"].items():
        profile = Path("OniBridge/profiles") / artifact["version"] / f"{platform}.json"
        if not profile.is_file():
            raise ValueError(f"compatibility profile is missing: {profile}")
        command = [
            sys.executable,
            "-m",
            "sdkgen",
            "validate-profile",
            str(profile),
            str(executable(cache, artifact, platform)),
        ]
        if allow_candidate:
            command.append("--allow-candidate")
        run(command)


def validate_checked(lock: dict, allow_candidate: bool, root: Path = Path(".")) -> None:
    """Validate checked profile evidence without downloading proprietary BDS archives.

    Exact executable inspection remains part of profile generation. This gate proves that an app
    release uses the same reviewed profile, generated ABI, adapter binding, and lock metadata. The
    generated adapter independently hashes the real executable again at plugin startup.
    """
    if lock.get("schema") != 1 or lock.get("paired_version") is not True:
        raise ValueError("BDS lock must be a paired schema-1 lock")
    platforms = lock.get("platforms")
    if not isinstance(platforms, dict) or set(platforms) != {
        "linux-x86_64",
        "windows-x86_64",
    }:
        raise ValueError(
            "BDS lock must contain exactly the Linux and Windows x86-64 platforms"
        )

    for platform, artifact in platforms.items():
        if not isinstance(artifact, dict):
            raise ValueError(f"invalid lock artifact for {platform}")
        version = artifact.get("version")
        if not isinstance(version, str) or not version:
            raise ValueError(f"lock version is missing for {platform}")
        profile_path = root / "OniBridge" / "profiles" / version / f"{platform}.json"
        generated = root / "OniBridge" / "generated" / "bds" / version / platform
        recipe_path = (
            root / "OniBridge" / "analysis-recipes" / version / f"{platform}.json"
        )
        profile = read(profile_path)
        generated_profile = read(generated / "profile.json")
        if profile != generated_profile:
            raise ValueError(f"checked and generated profiles differ for {platform}")
        if not recipe_path.is_file() or recipe_path.stat().st_size == 0:
            raise ValueError(
                f"human-reviewed analysis recipe is missing for {platform}"
            )
        for relative in REQUIRED_GENERATED_FILES:
            path = generated / relative
            if not path.is_file() or path.stat().st_size == 0:
                raise ValueError(f"generated profile evidence is missing: {path}")

        expected_os = platform.removesuffix("-x86_64")
        if (
            profile.get("schema") != 1
            or profile.get("bds_version") != version
            or profile.get("operating_system") != expected_os
            or profile.get("architecture") != artifact.get("architecture")
            or profile.get("executable_sha256") != artifact.get("executable_sha256")
            or profile.get("executable_size") != artifact.get("executable_size")
        ):
            raise ValueError(f"profile identity does not match the lock for {platform}")

        abi = read(generated / "abi.json")
        if (
            abi.get("schema") != 1
            or abi.get("status") != "verified"
            or abi.get("platform") != platform
            or abi.get("bds_version") != version
            or abi.get("executable_sha256") != artifact.get("executable_sha256")
            or abi.get("sizes") != profile.get("required_structure_sizes")
            or abi.get("offsets") != profile.get("required_field_offsets")
        ):
            raise ValueError(
                f"generated ABI evidence does not match the profile for {platform}"
            )

        status = profile.get("validation_status")
        if status != "production" and not allow_candidate:
            raise ValueError(
                f"{platform} profile is {status!r}; candidate releases are not allowed"
            )
        if status == "production":
            evidence = profile.get("evidence")
            if profile.get("release_blockers") != [] or not isinstance(evidence, dict):
                raise ValueError(
                    f"production profile contains release blockers for {platform}"
                )
            missing = [
                name
                for name in REQUIRED_PRODUCTION_EVIDENCE
                if evidence.get(name) is not True
            ]
            if missing:
                raise ValueError(
                    f"production profile evidence is incomplete for {platform}: {missing}"
                )

        executable_hash = artifact.get("executable_sha256")
        profile_id = f"bds-{version}-{platform}-{str(executable_hash)[:16]}"
        adapter = (generated / "adapter.cpp").read_text(encoding="utf-8")
        markers = (
            f'kProfileId[] = "{profile_id}"',
            f'kExecutableHash[] = "{executable_hash}"',
            f"kExecutableSize = {artifact.get('executable_size')}ULL",
            f"kProductionProfile = {'true' if status == 'production' else 'false'}",
        )
        if any(marker not in adapter for marker in markers):
            raise ValueError(
                f"generated adapter is not bound to the checked profile for {platform}"
            )

        runtime_path = profile_path.with_name(f"{platform}.runtime.json")
        if runtime_path.is_file():
            runtime = read(runtime_path)
            if (
                runtime.get("schema") != 1
                or runtime.get("platform") != platform
                or runtime.get("bds_version") != version
                or runtime.get("bds_executable_sha256") != executable_hash
            ):
                raise ValueError(
                    f"runtime evidence does not match the lock for {platform}"
                )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("generate", "validate", "validate-checked"))
    parser.add_argument("--lock", type=Path, required=True)
    parser.add_argument("--cache", type=Path, default=Path(".cache"))
    parser.add_argument("--allow-candidate", action="store_true")
    args = parser.parse_args()
    lock = read(args.lock)
    if args.command == "generate":
        generate(lock, args.cache)
    elif args.command == "validate":
        validate(lock, args.cache, args.allow_candidate)
    else:
        validate_checked(lock, args.allow_candidate)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (
        OSError,
        ValueError,
        KeyError,
        json.JSONDecodeError,
        subprocess.CalledProcessError,
    ) as exc:
        print(f"profile-pipeline: error: {exc}", file=sys.stderr)
        raise SystemExit(2)
