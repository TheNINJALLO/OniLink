from __future__ import annotations

from dataclasses import asdict, dataclass
from datetime import datetime, timezone
import json
from pathlib import Path
import re
from typing import Any

from . import __version__
from .binary import BinaryError, Image, load_image


PROFILE_SCHEMA = 1
PRODUCTION_GATES = (
    "function_boundary_validated",
    "control_flow_validated",
    "cross_references_validated",
    "instruction_decode_validated",
    "trampoline_relocation_validated",
    "calling_convention_validated",
    "existing_detour_checked",
    "endstone_chain_compatible",
    "abi_layout_validated",
    "hook_harness_passed",
    "human_reviewed",
    "live_tested",
)


class ProfileError(ValueError):
    pass


@dataclass(frozen=True, slots=True)
class Pattern:
    bytes: bytes
    mask: bytes

    @property
    def text(self) -> str:
        return " ".join(f"{value:02X}" if required else "??" for value, required in zip(self.bytes, self.mask))


def parse_pattern(value: str) -> Pattern:
    tokens = value.split()
    if not tokens:
        raise ProfileError("signature cannot be empty")
    raw = bytearray()
    mask = bytearray()
    for token in tokens:
        if token in {"?", "??"}:
            raw.append(0)
            mask.append(0)
        elif re.fullmatch(r"[0-9a-fA-F]{2}", token):
            raw.append(int(token, 16))
            mask.append(1)
        else:
            raise ProfileError(f"invalid signature token {token!r}")
    if not any(mask):
        raise ProfileError("signature must contain at least one required byte")
    return Pattern(bytes(raw), bytes(mask))


def find_matches(image: Image, pattern: Pattern) -> list[tuple[str, int]]:
    matches: list[tuple[str, int]] = []
    spans: list[tuple[int, int]] = []
    start = 0
    while start < len(pattern.mask):
        while start < len(pattern.mask) and not pattern.mask[start]:
            start += 1
        end = start
        while end < len(pattern.mask) and pattern.mask[end]:
            end += 1
        if end > start:
            spans.append((start, end))
        start = end
    anchor_start, anchor_end = max(spans, key=lambda span: span[1] - span[0])
    anchor = pattern.bytes[anchor_start:anchor_end]
    for section in image.sections:
        if not section.executable:
            continue
        content = image.data[section.file_offset : section.file_offset + section.size]
        found_at = 0
        while True:
            anchor_index = content.find(anchor, found_at)
            if anchor_index < 0:
                break
            index = anchor_index - anchor_start
            found_at = anchor_index + 1
            if index < 0 or index + len(pattern.bytes) > len(content):
                continue
            candidate = content[index : index + len(pattern.bytes)]
            if all(not required or candidate[i] == pattern.bytes[i] for i, required in enumerate(pattern.mask)):
                matches.append((section.name, section.rva + index))
    return matches


def create_candidate(
    binary: Path,
    version: str,
    platform: str,
    signature: str,
    minimum_patch_length: int,
    role: str,
    evidence: dict[str, bool] | None = None,
) -> dict[str, Any]:
    image = load_image(binary)
    expected_abi = "sysv-amd64" if platform == "linux-x86_64" else "microsoft-x64"
    if image.abi != expected_abi:
        raise ProfileError(f"binary ABI {image.abi} does not match platform {platform}")
    pattern = parse_pattern(signature)
    matches = find_matches(image, pattern)
    target_section = matches[0][0] if len(matches) == 1 else None
    target_rva = matches[0][1] if len(matches) == 1 else None
    observed = image.bytes_at_rva(target_rva, minimum_patch_length).hex(" ") if target_rva is not None else None
    gates = {name: bool((evidence or {}).get(name, False)) for name in PRODUCTION_GATES}
    blockers = []
    if len(matches) != 1:
        blockers.append(f"signature match count is {len(matches)}, expected 1")
    if minimum_patch_length < 5:
        blockers.append("minimum patch length is below the supported 5-byte jump")
    blockers.extend(name for name, passed in gates.items() if not passed)
    status = "production" if not blockers else "candidate"
    now = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    return {
        "schema": PROFILE_SCHEMA,
        "bds_version": version,
        "executable_sha256": image.sha256,
        "executable_size": len(image.data),
        "operating_system": "linux" if platform.startswith("linux") else "windows",
        "architecture": image.architecture,
        "abi": image.abi,
        "compiler_assumptions": [],
        "target_function_role": role,
        "target_section": target_section,
        "target_rva": target_rva,
        "candidate_symbol_name": None,
        "expected_prologue_bytes": observed,
        "masked_byte_signature": pattern.text,
        "signature_match_count": len(matches),
        "minimum_patch_length": minimum_patch_length,
        "calling_convention": image.abi,
        "required_structure_sizes": {},
        "required_field_offsets": {},
        "known_endstone_hook_interaction": "unverified" if not gates["endstone_chain_compatible"] else "chain-compatible",
        "evidence": gates,
        "validation_status": status,
        "generation_time_utc": now,
        "generator_version": __version__,
        "human_review_status": "approved" if gates["human_reviewed"] else "required",
        "live_test_status": "passed" if gates["live_tested"] else "required",
        "release_blockers": blockers,
    }


def validate_profile(profile: dict[str, Any], binary: Path, production: bool = True) -> None:
    image = load_image(binary)
    required = {
        "schema", "bds_version", "executable_sha256", "executable_size", "architecture", "abi",
        "target_section", "target_rva", "expected_prologue_bytes", "masked_byte_signature",
        "signature_match_count", "minimum_patch_length", "evidence", "validation_status",
    }
    missing = required - set(profile)
    if missing:
        raise ProfileError(f"profile is missing keys: {sorted(missing)}")
    if profile["schema"] != PROFILE_SCHEMA:
        raise ProfileError("unsupported profile schema")
    if profile["executable_sha256"] != image.sha256 or profile["executable_size"] != len(image.data):
        raise ProfileError("profile executable hash or size does not match runtime module")
    if profile["architecture"] != image.architecture or profile["abi"] != image.abi:
        raise ProfileError("profile architecture or ABI does not match runtime module")
    pattern = parse_pattern(profile["masked_byte_signature"])
    matches = find_matches(image, pattern)
    if len(matches) != 1 or profile["signature_match_count"] != 1:
        raise ProfileError("profile signature is not unique in the executable image")
    section, rva = matches[0]
    if section != profile["target_section"] or rva != profile["target_rva"]:
        raise ProfileError("profile target does not match the unique signature location")
    patch_length = profile["minimum_patch_length"]
    if patch_length < 5 or image.executable_section_for_rva(rva, patch_length) is None:
        raise ProfileError("target or patch range is outside an executable section")
    expected = bytes.fromhex(profile["expected_prologue_bytes"])
    if image.bytes_at_rva(rva, len(expected)) != expected:
        raise ProfileError("expected target bytes do not match")
    evidence = profile["evidence"]
    missing_gates = [name for name in PRODUCTION_GATES if not evidence.get(name)]
    if production and (profile["validation_status"] != "production" or missing_gates):
        raise ProfileError(f"profile is not production-approved; missing evidence: {missing_gates}")


def read_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ProfileError("JSON root must be an object")
    return value


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".partial")
    temporary.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    temporary.replace(path)
