#!/usr/bin/env sh
set -eu

as_root() {
    if [ "$(id -u)" -eq 0 ]; then
        "$@"
    else
        sudo "$@"
    fi
}

installer=$(mktemp)
trap 'rm -f "$installer"' EXIT HUP INT TERM

as_root apt-get update
as_root apt-get install --yes ca-certificates curl gnupg lsb-release
curl --fail --location --silent --show-error https://apt.llvm.org/llvm.sh --output "$installer"
echo "03878e08f47b66cc95bc4b544b0db3c6d9ce8d60e6cf2492ae357984330a9eae  $installer" | sha256sum --check --status
chmod 0755 "$installer"
as_root "$installer" 18
as_root apt-get install --yes clang-18 libc++-18-dev libc++abi-18-dev

clang++-18 --version
