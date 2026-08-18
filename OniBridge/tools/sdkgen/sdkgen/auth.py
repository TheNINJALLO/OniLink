from __future__ import annotations

from datetime import datetime, timezone
import json
from pathlib import Path
import struct
from typing import Any

from . import __version__
from .binary import Image, load_image
from .profile import PROFILE_SCHEMA, PRODUCTION_GATES, ProfileError, find_matches, parse_pattern, write_json


def _capstone():
    try:
        from capstone import Cs, CS_ARCH_X86, CS_MODE_64
        from capstone.x86 import X86_OP_IMM, X86_OP_MEM, X86_REG_RIP
    except ImportError as exc:  # pragma: no cover - exercised by packaging environments
        raise ProfileError("auth analysis requires the capstone package") from exc
    decoder = Cs(CS_ARCH_X86, CS_MODE_64)
    decoder.detail = True
    return decoder, X86_OP_IMM, X86_OP_MEM, X86_REG_RIP


def _instructions(image: Image, rva: int, size: int):
    decoder, *_ = _capstone()
    instructions = list(decoder.disasm(image.bytes_at_rva(rva, size), rva))
    if not instructions or instructions[0].address != rva:
        raise ProfileError(f"0x{rva:x} is not a decoded instruction boundary")
    return instructions


def _direct_call_target(image: Image, rva: int) -> int:
    instructions = _instructions(image, rva, 5)
    _, immediate, _, _ = _capstone()
    instruction = instructions[0]
    if instruction.mnemonic != "call" or instruction.size != 5 or len(instruction.operands) != 1 \
            or instruction.operands[0].type != immediate:
        raise ProfileError(f"0x{rva:x} is not a five-byte direct call")
    return int(instruction.operands[0].imm)


def _direct_xrefs(image: Image, target_rva: int) -> list[int]:
    result: list[int] = []
    for section in image.sections:
        if not section.executable:
            continue
        content = image.data[section.file_offset:section.file_offset + section.size]
        offset = 0
        while True:
            offset = content.find(b"\xe8", offset)
            if offset < 0:
                break
            if offset + 5 <= len(content):
                source = section.rva + offset
                destination = source + 5 + struct.unpack_from("<i", content, offset + 1)[0]
                if destination == target_rva:
                    result.append(source)
            offset += 1
    return result


def _verify_prologue(instructions, platform: str) -> dict[str, str]:
    expected = (
        ["push rbp", "push r15", "push r14", "push r13", "push r12", "push rbx", "sub rsp, 0x538",
         "mov r12, rcx", "mov r15, rdx", "mov r14, rsi", "mov rbx, rdi"]
        if platform == "linux-x86_64" else
        ["push rbp", "push r15", "push r14", "push r13", "push r12", "push rsi", "push rdi", "push rbx",
         "sub rsp, 0x728", "lea rbp, [rsp + 0x80]", "movaps xmmword ptr [rbp + 0x690], xmm6",
         "mov qword ptr [rbp + 0x688], 0xfffffffffffffffe", "mov r14, r9", "mov rdi, r8", "mov rsi, rdx"]
    )
    observed = [f"{item.mnemonic} {item.op_str}".strip() for item in instructions[:len(expected)]]
    if observed != expected:
        raise ProfileError(f"validation prologue/calling convention mismatch: {observed!r}")
    if platform == "linux-x86_64":
        return {"this": "r14", "network_identifier": "r15", "login_packet": "r12", "optional_result": "rbx"}
    return {"this": "rbx", "network_identifier": "rdi", "login_packet": "r14", "optional_result": "rsi"}


def _verify_string_xrefs(image: Image, xrefs: dict[str, int]) -> dict[str, dict[str, int]]:
    _, _, memory, rip = _capstone()
    result: dict[str, dict[str, int]] = {}
    for expected, instruction_rva in xrefs.items():
        instruction = _instructions(image, instruction_rva, 15)[0]
        operands = [operand for operand in instruction.operands if operand.type == memory and operand.mem.base == rip]
        if instruction.mnemonic != "lea" or len(operands) != 1:
            raise ProfileError(f"string evidence 0x{instruction_rva:x} is not a RIP-relative LEA")
        string_rva = instruction.address + instruction.size + operands[0].mem.disp
        observed = image.bytes_at_rva(string_rva, len(expected) + 1)
        if observed != expected.encode("ascii") + b"\0":
            raise ProfileError(f"string xref 0x{instruction_rva:x} does not resolve to {expected!r}")
        result[expected] = {"instruction_rva": instruction_rva, "string_rva": string_rva}
    return result


def _verify_move_helper(
    image: Image,
    platform: str,
    helper_rva: int,
    helper_size: int,
    authentication_size: int,
    string_size: int,
) -> dict[str, Any]:
    instructions = _instructions(image, helper_rva, helper_size)
    if instructions[-1].mnemonic != "ret" or instructions[-1].address + instructions[-1].size != helper_rva + helper_size:
        raise ProfileError("authentication move helper does not end at the reviewed RET boundary")
    _, immediate, memory, _ = _capstone()
    source_register = "rsi" if platform == "linux-x86_64" else "rdx"
    destination_registers = {"rdi"} if platform == "linux-x86_64" else {"rcx", "rax"}
    source_offsets: set[int] = set()
    destination_offsets: set[int] = set()
    flag_write = False
    for instruction in instructions:
        for operand in instruction.operands:
            if operand.type != memory:
                continue
            register = instruction.reg_name(operand.mem.base)
            if register == source_register:
                source_offsets.add(int(operand.mem.disp))
            if register in destination_registers:
                destination_offsets.add(int(operand.mem.disp))
        if instruction.mnemonic == "mov" and len(instruction.operands) == 2 \
                and instruction.operands[0].type == memory and instruction.operands[1].type == immediate:
            destination = instruction.reg_name(instruction.operands[0].mem.base)
            if destination in destination_registers \
                    and int(instruction.operands[0].mem.disp) == authentication_size \
                    and int(instruction.operands[1].imm) == 1:
                flag_write = True
    field_offsets = [index * string_size for index in range(10)]
    if any(offset not in source_offsets for offset in field_offsets) \
            or any(offset not in destination_offsets for offset in field_offsets):
        raise ProfileError("move helper does not independently cover all ten authentication strings")
    permissions_offset = 10 * string_size
    public_key_offset = permissions_offset + 8
    uuid_offset = public_key_offset + string_size
    host_offset = uuid_offset + 16
    for required in (permissions_offset, public_key_offset, uuid_offset, host_offset):
        if required not in source_offsets or required not in destination_offsets:
            raise ProfileError(f"move helper does not cover required authentication offset 0x{required:x}")
    if not flag_write:
        raise ProfileError("move helper does not set the optional authentication-result engaged flag")
    return {
        "instruction_count": len(instructions),
        "string_count": 10,
        "string_size": string_size,
        "permissions_offset": permissions_offset,
        "public_key_offset": public_key_offset,
        "authenticated_uuid_offset": uuid_offset,
        "is_host_offset": host_offset,
        "is_local_offset": host_offset + 1,
        "optional_engaged_offset": authentication_size,
    }


def generate_auth_artifacts(
    binary: Path,
    version: str,
    platform: str,
    validation_rva: int,
    validation_size: int,
    call_rva: int,
    helper_rva: int,
    helper_size: int,
    authentication_size: int,
    string_size: int,
    signature: str,
    xrefs: dict[str, int],
    output: Path,
) -> dict[str, Path]:
    image = load_image(binary)
    expected_abi = "sysv-amd64" if platform == "linux-x86_64" else "microsoft-x64"
    if image.abi != expected_abi:
        raise ProfileError(f"binary ABI {image.abi} does not match platform {platform}")
    if image.executable_section_for_rva(validation_rva, validation_size) is None:
        raise ProfileError("validation function is not wholly inside one executable section")
    validation_instructions = _instructions(image, validation_rva, validation_size)
    register_contract = _verify_prologue(validation_instructions, platform)
    call = next((item for item in validation_instructions if item.address == call_rva), None)
    if call is None or call.mnemonic != "call" or call.size != 5:
        raise ProfileError("selected call site is not an instruction in the validation function")
    if _direct_call_target(image, call_rva) != helper_rva:
        raise ProfileError("selected call site does not target the authentication move helper")
    helper_xrefs = _direct_xrefs(image, helper_rva)
    if helper_xrefs != [call_rva]:
        raise ProfileError(f"authentication move helper has unexpected direct callers: {helper_xrefs!r}")
    move_evidence = _verify_move_helper(
        image, platform, helper_rva, helper_size, authentication_size, string_size)
    string_evidence = _verify_string_xrefs(image, xrefs)
    pattern = parse_pattern(signature)
    matches = find_matches(image, pattern)
    target_section = image.executable_section_for_rva(call_rva, 5)
    if matches != [(target_section.name if target_section else "", call_rva)]:
        raise ProfileError(f"call-site signature is not unique at the selected target: {matches!r}")
    if len(pattern.bytes) < 10:
        raise ProfileError("reviewed call-site signature must include at least ten bytes")

    now = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    offsets = {
        "PlayerAuthenticationInfo.xuid": 0,
        "PlayerAuthenticationInfo.xbox_live_name": 6 * string_size,
        "PlayerAuthenticationInfo.best_display_name": 9 * string_size,
        "PlayerAuthenticationInfo.permissions": move_evidence["permissions_offset"],
        "PlayerAuthenticationInfo.public_key": move_evidence["public_key_offset"],
        "PlayerAuthenticationInfo.authenticated_uuid": move_evidence["authenticated_uuid_offset"],
        "PlayerAuthenticationInfo.is_host": move_evidence["is_host_offset"],
        "PlayerAuthenticationInfo.is_local": move_evidence["is_local_offset"],
        "optional<PlayerAuthenticationInfo>.engaged": move_evidence["optional_engaged_offset"],
    }
    abi = {
        "schema": 1,
        "status": "verified",
        "bds_version": version,
        "platform": platform,
        "executable_sha256": image.sha256,
        "compiler_abi": image.abi,
        "sizes": {"std::string": string_size, "PlayerAuthenticationInfo": authentication_size},
        "alignments": {"PlayerAuthenticationInfo": 8},
        "offsets": offsets,
        "binary_evidence": move_evidence,
        "generation_time_utc": now,
        "generator_version": __version__,
    }
    evidence = {name: True for name in PRODUCTION_GATES}
    evidence["hook_harness_passed"] = False
    evidence["human_reviewed"] = False
    evidence["live_tested"] = False
    blockers = [name for name, passed in evidence.items() if not passed]
    profile = {
        "schema": PROFILE_SCHEMA,
        "bds_version": version,
        "executable_sha256": image.sha256,
        "executable_size": len(image.data),
        "operating_system": "linux" if platform.startswith("linux") else "windows",
        "architecture": image.architecture,
        "abi": image.abi,
        "target_function_role": "successful PlayerAuthenticationInfo move call before Endstone player-ban and BDS storage selection",
        "target_section": target_section.name if target_section else None,
        "target_rva": call_rva,
        "target_call_destination_rva": helper_rva,
        "target_owner_function_rva": validation_rva,
        "target_owner_function_size": validation_size,
        "helper_function_size": helper_size,
        "candidate_symbol_name": "ServerNetworkHandler::_validateLoginPacket / unique authentication-result move helper",
        "expected_prologue_bytes": image.bytes_at_rva(call_rva, 5).hex(" "),
        "expected_target_bytes": image.bytes_at_rva(call_rva, len(pattern.bytes)).hex(" "),
        "masked_byte_signature": pattern.text,
        "signature_match_count": 1,
        "minimum_patch_length": 5,
        "patch_model": "replace one direct CALL with a near W^X relay; helper and Endstone detours remain unmodified",
        "calling_convention": image.abi,
        "register_contract": register_contract,
        "required_structure_sizes": abi["sizes"],
        "required_field_offsets": offsets,
        "known_endstone_hook_interaction": "inside the original BDS validation call, before Endstone post-validation bans",
        "direct_helper_callers": helper_xrefs,
        "login_json_string_xrefs": string_evidence,
        "evidence": evidence,
        "validation_status": "candidate",
        "generation_time_utc": now,
        "generator_version": __version__,
        "human_review_status": "required",
        "live_test_status": "required",
        "release_blockers": blockers,
    }
    symbols = {
        "schema": 1,
        "bds_version": version,
        "platform": platform,
        "executable_sha256": image.sha256,
        "symbols": {
            "ServerNetworkHandler::_validateLoginPacket": {"rva": validation_rva, "size": validation_size},
            "PlayerAuthenticationInfo optional move helper": {"rva": helper_rva, "size": helper_size,
                                                               "direct_callers": helper_xrefs},
            "successful authentication move call": {"rva": call_rva, "size": 5},
        },
    }
    signatures = {
        "schema": 1,
        "bds_version": version,
        "platform": platform,
        "executable_sha256": image.sha256,
        "signatures": [{"role": profile["target_function_role"], "pattern": pattern.text,
                         "match_count": 1, "target_rva": call_rva}],
    }
    output.mkdir(parents=True, exist_ok=True)
    paths = {
        "abi": output / "abi.json",
        "symbols": output / "symbols.json",
        "signatures": output / "signatures.json",
        "profile": output / "profile.json",
        "report": output / "report.md",
    }
    write_json(paths["abi"], abi)
    write_json(paths["symbols"], symbols)
    write_json(paths["signatures"], signatures)
    write_json(paths["profile"], profile)
    paths["report"].write_text(
        f"# BDS authentication ABI report: {platform}\n\n"
        f"- BDS: `{version}`\n- Executable SHA-256: `{image.sha256}`\n"
        f"- Validation function: `0x{validation_rva:x}` (`0x{validation_size:x}` bytes)\n"
        f"- Successful move call: `0x{call_rva:x}`\n- Unique move helper: `0x{helper_rva:x}` "
        f"(`0x{helper_size:x}` bytes; one direct caller)\n"
        f"- PlayerAuthenticationInfo: `0x{authentication_size:x}` bytes; first/XUID field offset `0`\n"
        f"- Optional engaged flag: `0x{authentication_size:x}`\n"
        f"- Endstone chain: the patch point executes inside Endstone's call to original BDS validation, so "
        f"the verified XUID is visible to Endstone's post-validation ban check.\n"
        f"- Release status: candidate; blockers are {', '.join(blockers)}.\n",
        encoding="utf-8",
    )
    return paths

