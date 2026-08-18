# Linux

Target: Ubuntu 22.04 or newer, x86-64, Clang, C++20, System V AMD64. BDS 1.26.44.3 executable SHA-256 is `06effdd00067f1ae0951ee7a732398dde721728e6b18ea149b138b8e2aececa7`.

The profile-specific `onibridge.so` cross-compiles as ELF64 x86-64 (`ET_DYN`) and contains `init_endstone_plugin`. It targets glibc 2.35 and uses libc++. This Windows-hosted cross-build was not loaded or run on Linux; the Linux hook harness, sanitizers, and live BDS acceptance remain required. Never infer Linux compatibility from the Windows harness.
