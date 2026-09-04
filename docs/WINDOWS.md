# Windows

Target: Windows 10/Server 2016 or newer, x86-64, MSVC or clang-cl, C++20, Microsoft x64 ABI. The
current candidate targets BDS `1.26.45.1`, executable SHA-256
`92d09c7b74ac6a9805bafc166d8e0a13ac9e5db73dbbb0819e5a14093699d44f`, with Endstone `0.11.10`.

The profile-specific `onibridge.dll` builds under MSVC, exports `init_endstone_plugin`, and passes
both Windows CTest targets. The 1.26.45.1 profile remains a candidate because human review and live
BDS/client identity, storage, command, and lifecycle acceptance are missing. The older 1.26.44.3
candidate has a recorded disposable offline lifecycle, but that evidence does not promote or apply
to the new binary. Never reuse Linux or older-version layout facts, RVAs, signatures, or patch
lengths.
