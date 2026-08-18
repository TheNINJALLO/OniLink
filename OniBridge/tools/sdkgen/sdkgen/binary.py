from __future__ import annotations

from dataclasses import dataclass
import hashlib
from pathlib import Path
import struct


class BinaryError(ValueError):
    pass


@dataclass(frozen=True, slots=True)
class Section:
    name: str
    rva: int
    file_offset: int
    size: int
    executable: bool

    def contains_rva(self, rva: int, length: int = 1) -> bool:
        return self.rva <= rva and rva + length <= self.rva + self.size


@dataclass(frozen=True, slots=True)
class Image:
    path: Path
    file_format: str
    architecture: str
    abi: str
    image_base: int
    data: bytes
    sections: tuple[Section, ...]

    @property
    def sha256(self) -> str:
        return hashlib.sha256(self.data).hexdigest()

    def executable_section_for_rva(self, rva: int, length: int = 1) -> Section | None:
        return next((s for s in self.sections if s.executable and s.contains_rva(rva, length)), None)

    def bytes_at_rva(self, rva: int, length: int) -> bytes:
        section = next((s for s in self.sections if s.contains_rva(rva, length)), None)
        if section is None:
            raise BinaryError(f"RVA 0x{rva:x} is outside a file-backed section")
        offset = section.file_offset + rva - section.rva
        if offset + length > len(self.data):
            raise BinaryError("RVA maps beyond the executable file")
        return self.data[offset : offset + length]


def load_image(path: Path) -> Image:
    data = path.read_bytes()
    if data.startswith(b"\x7fELF"):
        return _load_elf(path, data)
    if data.startswith(b"MZ"):
        return _load_pe(path, data)
    raise BinaryError("unsupported executable format")


def _range(data: bytes, offset: int, length: int, label: str) -> memoryview:
    if offset < 0 or length < 0 or offset + length > len(data):
        raise BinaryError(f"truncated {label}")
    return memoryview(data)[offset : offset + length]


def _load_elf(path: Path, data: bytes) -> Image:
    _range(data, 0, 64, "ELF header")
    if data[4] != 2 or data[5] != 1:
        raise BinaryError("only little-endian ELF64 is supported")
    if struct.unpack_from("<H", data, 18)[0] != 62:
        raise BinaryError("ELF architecture is not x86_64")
    shoff = struct.unpack_from("<Q", data, 40)[0]
    shentsize, shnum, shstrndx = struct.unpack_from("<HHH", data, 58)
    if shentsize < 64 or shnum == 0 or shstrndx >= shnum:
        raise BinaryError("ELF section table is missing or invalid")
    _range(data, shoff, shentsize * shnum, "ELF section table")
    string_header = shoff + shstrndx * shentsize
    str_offset, str_size = struct.unpack_from("<QQ", data, string_header + 24)
    strings = bytes(_range(data, str_offset, str_size, "ELF section-name table"))
    sections: list[Section] = []
    for index in range(shnum):
        offset = shoff + index * shentsize
        name_offset, section_type, flags, address, file_offset, size = struct.unpack_from("<IIQQQQ", data, offset)
        if name_offset >= len(strings):
            raise BinaryError("ELF section has an invalid name offset")
        end = strings.find(b"\0", name_offset)
        if end < 0:
            raise BinaryError("ELF section name is unterminated")
        name = strings[name_offset:end].decode("ascii", "replace")
        file_backed_size = 0 if section_type == 8 else size  # SHT_NOBITS has no bytes in the executable.
        if file_backed_size and file_offset + file_backed_size > len(data):
            raise BinaryError(f"ELF section {name!r} exceeds the file")
        sections.append(Section(name, address, file_offset, file_backed_size, bool(flags & 0x4)))
    if not any(section.executable and section.size for section in sections):
        raise BinaryError("ELF has no executable section")
    return Image(path, "ELF64", "x86_64", "sysv-amd64", 0, data, tuple(sections))


def _load_pe(path: Path, data: bytes) -> Image:
    _range(data, 0, 64, "DOS header")
    pe = struct.unpack_from("<I", data, 0x3C)[0]
    _range(data, pe, 24, "PE header")
    if data[pe : pe + 4] != b"PE\0\0":
        raise BinaryError("invalid PE signature")
    machine, section_count = struct.unpack_from("<HH", data, pe + 4)
    optional_size = struct.unpack_from("<H", data, pe + 20)[0]
    optional = pe + 24
    _range(data, optional, optional_size, "PE optional header")
    if machine != 0x8664 or optional_size < 32 or struct.unpack_from("<H", data, optional)[0] != 0x20B:
        raise BinaryError("PE architecture is not PE32+ x86_64")
    image_base = struct.unpack_from("<Q", data, optional + 24)[0]
    table = optional + optional_size
    _range(data, table, section_count * 40, "PE section table")
    sections: list[Section] = []
    for index in range(section_count):
        offset = table + index * 40
        name = data[offset : offset + 8].partition(b"\0")[0].decode("ascii", "replace")
        virtual_size, rva, raw_size, raw_offset = struct.unpack_from("<IIII", data, offset + 8)
        characteristics = struct.unpack_from("<I", data, offset + 36)[0]
        size = min(raw_size, virtual_size or raw_size)
        if raw_size and raw_offset + raw_size > len(data):
            raise BinaryError(f"PE section {name!r} exceeds the file")
        sections.append(Section(name, rva, raw_offset, size, bool(characteristics & 0x20000000)))
    if not any(section.executable and section.size for section in sections):
        raise BinaryError("PE has no executable section")
    return Image(path, "PE32+", "x86_64", "microsoft-x64", image_base, data, tuple(sections))
