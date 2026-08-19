from __future__ import annotations

import argparse
import json
from pathlib import Path
import shutil
import sys

from check_linux_abi import MAXIMUM_GLIBC, inspect_glibc_requirements
from package_release import digest, read_json, reject_forbidden, validate_release_token


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Assemble OniLink Linux release artifacts"
    )
    parser.add_argument("--version", required=True)
    parser.add_argument("--bds-version", required=True)
    parser.add_argument("--dist", type=Path, default=Path("dist/linux"))
    args = parser.parse_args()
    validate_release_token("version", args.version)
    validate_release_token("BDS version", args.bds_version)

    lock = read_json(Path("OniBridge/bds.lock.json"))
    locked = lock.get("platforms", {}).get("linux-x86_64")
    if not isinstance(locked, dict) or locked.get("version") != args.bds_version:
        raise ValueError("Linux BDS lock does not match the requested version")
    profile_path = Path("OniBridge/profiles") / args.bds_version / "linux-x86_64.json"
    profile = read_json(profile_path)
    if profile.get("executable_sha256") != locked.get("executable_sha256"):
        raise ValueError("Linux profile hash does not match the BDS lock")

    inputs = {
        "OniLink.jar": Path("OniLink/dist/OniLink.jar"),
        "OniBridge-Geyser.jar": Path("OniBridge-Geyser/dist/OniBridge-Geyser.jar"),
        f"onibridge-{args.version}-bds-{args.bds_version}-linux-x86_64.so": Path(
            "OniBridge/build/linux-release/onibridge.so"
        ),
        "onilink.properties.example": Path("OniLink/onilink.example.properties"),
        "onibridge.toml.example": Path("OniBridge/onibridge.example.toml"),
        "onibridge-geyser.properties.example": Path(
            "OniBridge-Geyser/config.example.properties"
        ),
        "egg-onilink.json": Path("packaging/pterodactyl/egg-onilink.json"),
        "start-onilink.sh": Path("packaging/pterodactyl/start-onilink.sh"),
        f"onibridge-profile-{args.bds_version}-linux-x86_64.json": profile_path,
    }
    missing = [str(path) for path in inputs.values() if not path.is_file()]
    if missing:
        raise ValueError(f"Linux package inputs are missing: {missing}")
    native_abi = inspect_glibc_requirements(
        inputs[f"onibridge-{args.version}-bds-{args.bds_version}-linux-x86_64.so"],
        MAXIMUM_GLIBC,
    )
    if (
        not native_abi["glibc_policy_passed"]
        or not native_abi["cxx_runtime_policy_passed"]
    ):
        raise ValueError(
            "Linux plugin violates the native runtime policy: "
            f"GLIBC requires {native_abi['glibc_highest_required']} (maximum {MAXIMUM_GLIBC}), "
            f"C++ runtime passed={native_abi['cxx_runtime_policy_passed']}"
        )

    args.dist.mkdir(parents=True, exist_ok=True)
    outputs: list[Path] = []
    for name, source in inputs.items():
        reject_forbidden(source)
        destination = args.dist / name
        shutil.copy2(source, destination)
        reject_forbidden(destination)
        outputs.append(destination)

    evidence = profile.get("evidence", {})
    production = profile.get("validation_status") == "production"
    manifest = {
        "schema": 1,
        "release_status": "production"
        if production
        else "candidate-awaiting-validation",
        "production_ready": production,
        "onibridge_version": args.version,
        "bds_version": args.bds_version,
        "bds_executable_sha256": locked["executable_sha256"],
        "bds_archive_sha256": locked["archive_sha256"],
        "platform": "linux-x86_64",
        "abi": profile.get("abi"),
        "native_runtime": native_abi,
        "profile_status": profile.get("validation_status"),
        "release_blockers": profile.get("release_blockers", []),
        "hook_harness_passed": bool(evidence.get("hook_harness_passed")),
        "human_reviewed": bool(evidence.get("human_reviewed")),
        "live_tested": bool(evidence.get("live_tested")),
        "artifacts": [
            {"name": path.name, "sha256": digest(path), "size": path.stat().st_size}
            for path in outputs
        ],
    }
    manifest_path = args.dist / "linux-compatibility-manifest.json"
    manifest_path.write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    outputs.append(manifest_path)
    sums = args.dist / "SHA256SUMS"
    sums.write_text(
        "".join(f"{digest(path)}  {path.name}\n" for path in outputs), encoding="utf-8"
    )
    reject_forbidden(sums)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError) as exception:
        print(f"package-linux: error: {exception}", file=sys.stderr)
        raise SystemExit(2)
