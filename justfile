set dotenv-load := false

bds-resolve:
    python -m pip install -e OniBridge/tools/bdsctl
    python -m bdsctl resolve --channel stable --platform both

bds-fetch:
    python -m bdsctl fetch --lock OniBridge/bds.lock.json

bds-inspect:
    python -m bdsctl inspect --lock OniBridge/bds.lock.json

sdk-generate:
    python -m pip install -e OniBridge/tools/sdkgen
    python -m sdkgen --help

profiles-generate:
    sh scripts/generate-profiles.sh

profiles-validate:
    sh scripts/validate-release-profiles.sh

build-linux adapter:
    cmake --preset linux-release -S OniBridge -DONIBRIDGE_GENERATED_ADAPTER={{adapter}}
    cmake --build --preset linux-release

build-windows adapter:
    cmake --preset windows-release -S OniBridge -DONIBRIDGE_GENERATED_ADAPTER={{adapter}}
    cmake --build --preset windows-release

build-geyser:
    OniLink/gradlew -p OniBridge-Geyser test jar --no-daemon

build-linux-all:
    sh scripts/build-linux.sh

test:
    python -m unittest discover -s OniBridge/tools/bdsctl/tests -t OniBridge/tools/bdsctl
    python -m unittest discover -s OniBridge/tools/sdkgen/tests -t OniBridge/tools/sdkgen
    python -m unittest discover -s tools -p "test_*.py"
    OniLink/gradlew test
    OniLink/gradlew -p OniBridge-Geyser test

package version bds_version:
    python tools/package_release.py --version {{version}} --bds-version {{bds_version}}
