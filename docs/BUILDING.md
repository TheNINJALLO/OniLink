# Building

Prerequisites are Python 3.11+, Java 21, CMake 3.24+, and a C++20 compiler. Linux uses Clang/System V AMD64 and libc++; Windows uses MSVC or clang-cl/Microsoft x64. Never reuse generated offsets, signatures, or adapters across platforms.

The full Linux candidate set can be built from the committed lock/profile without downloading BDS:

```bash
scripts/build-linux.sh
```

This produces OniLink, the exact-profile `onibridge.so`, OniBridge-Geyser, configuration examples, hashes, and a candidate-aware manifest under `dist/linux`. The same build is available as `.github/workflows/linux-artifacts.yml` and as an export-only container target:

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

On Linux, use the equivalent Linux adapter with the `linux-release` preset and pass the two cache variables on the configure command. ASan/UBSan presets exercise the shared core without loading BDS. Normal CI uses generated fixtures and does not download BDS.

The local `onibridge.so` was cross-compiled with Zig 0.16's Clang driver for `x86_64-linux-gnu.2.35` because this host has no Linux runtime. That proves compilation/ELF shape, not Linux loading or execution.
