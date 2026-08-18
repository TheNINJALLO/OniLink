# Windows

Target: Windows 10/Server 2016 or newer, x86-64, MSVC or clang-cl, C++20, Microsoft x64 ABI. BDS 1.26.44.3 executable SHA-256 is `2d6518ddd25211aa51155fc015cd0393b29b2af74551a378b16f9a724ed771bd`.

The profile-specific `onibridge.dll` builds under MSVC and exports `init_endstone_plugin`. Both Windows CTest targets pass. A disposable offline-mode BDS/Endstone run also proved DLL load, enable, exact hook installation, disable/uninstall, and clean exit. The profile remains a candidate because human review and live client identity/storage/command acceptance are missing. Never reuse Linux layout facts, RVAs, signatures, or patch lengths.
