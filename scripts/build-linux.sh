#!/usr/bin/env sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$repo_root"

release_version=${ONILINK_RELEASE_VERSION:-0.1.0}
bds_version=$(python3 -c 'import json; print(json.load(open("OniBridge/bds.lock.json", encoding="utf-8"))["platforms"]["linux-x86_64"]["version"])')
adapter="OniBridge/generated/bds/$bds_version/linux-x86_64/adapter.cpp"

if [ ! -f "$adapter" ]; then
    echo "Missing committed Linux adapter for locked BDS $bds_version" >&2
    exit 2
fi

./OniLink/gradlew -p OniLink test standaloneJar --no-daemon --console=plain
./OniLink/gradlew -p OniBridge-Geyser test jar --no-daemon --console=plain

cmake -S OniBridge -B OniBridge/build/linux-release \
    -G Ninja \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_CXX_COMPILER=clang++ \
    -DONIBRIDGE_VERSION="$release_version" \
    -DONIBRIDGE_BUILD_PLUGIN=ON \
    -DONIBRIDGE_BUILD_TESTS=ON \
    -DONIBRIDGE_GENERATED_ADAPTER="$adapter"
cmake --build OniBridge/build/linux-release --parallel
ctest --test-dir OniBridge/build/linux-release --output-on-failure

python3 tools/check_linux_abi.py OniBridge/build/linux-release/onibridge.so --maximum-glibc 2.35
python3 tools/package_linux.py --version "$release_version" --bds-version "$bds_version"
