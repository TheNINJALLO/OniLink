# Building

Prerequisites are Python 3.11+, Java 21, CMake 3.24+, and a C++20 compiler. Release Linux builds use LLVM 18/System V AMD64 and libc++ 18 from the Jammy apt.llvm.org repository; Windows uses MSVC or clang-cl/Microsoft x64. Never reuse generated offsets, signatures, or adapters across platforms.

The full Linux candidate set can be built from the committed lock/profile without downloading BDS:

```bash
scripts/build-linux.sh
```

This produces OniLink, the exact-profile `onibridge.so`, OniBridge-Geyser, configuration examples, hashes, and a candidate-aware manifest under `dist/linux`. The build fails if the ELF imports symbols newer than `GLIBC_2.35`. The same build is available as `.github/workflows/linux-artifacts.yml` and as an export-only Ubuntu 22.04 container target:

```bash
docker build -f packaging/linux/Dockerfile --output type=local,dest=dist/linux-container .
```

```powershell
cd OniBridge/tools/bdsctl
python -m pytest -q
cd ../sdkgen
python -m pytest -q
cd ../../../tools
python -m unittest test_package_release -v

cd ../OniLink
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ONILINK_BUILD_DIR = "$env:TEMP\onilink-build"
.\gradlew.bat test standaloneJar --no-daemon --console=plain

cd ..
.\OniLink\gradlew.bat -p OniBridge-Geyser test jar --no-daemon --console=plain
```

Profile-bearing native builds must set both the exact generated adapter and the audited Endstone source:

```powershell
cmake -S OniBridge -B OniBridge/build/windows-release -A x64 `
  -DONIBRIDGE_BUILD_PLUGIN=ON `
  -DONIBRIDGE_GENERATED_ADAPTER=OniBridge/generated/bds/1.26.44.3/windows-x86_64/adapter.cpp `
  -DONIBRIDGE_ENDSTONE_SOURCE=.upstream/endstone
cmake --build OniBridge/build/windows-release --config Release
ctest --test-dir OniBridge/build/windows-release -C Release --output-on-failure
```

On Linux, use the equivalent Linux adapter with the `linux-release` build directory and pass the profile-specific adapter on the configure command. ASan/UBSan presets exercise the shared core without loading BDS. Normal CI uses generated fixtures and does not download BDS.

The release `.so` is built natively by the Ubuntu 22.04 GitHub workflow with LLVM 18, libc++ 18, and libc++abi 18 packages built for Jammy. The workflow runs CTest and `tools/check_linux_abi.py` before packaging. The ABI check rejects imports newer than `GLIBC_2.35`, any `libstdc++.so` dependency, and unresolved `std::__cxx11` or `GLIBCXX` symbols. It accepts runtime-neutral plugins as well as plugins with direct or host-resolved libc++ references. This proves a native Linux build and synthetic harness execution; it does not replace live BDS/Endstone acceptance or human profile review.
