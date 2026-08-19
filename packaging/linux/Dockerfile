FROM ubuntu:22.04 AS build

ARG DEBIAN_FRONTEND=noninteractive
ARG RELEASE_VERSION=0.1.0
RUN apt-get update && apt-get install -y --no-install-recommends \
    binutils ca-certificates clang git libc++-dev libc++abi-dev ninja-build \
    openjdk-21-jdk-headless python3 python3-pip \
    && rm -rf /var/lib/apt/lists/* \
    && python3 -m pip install --no-cache-dir cmake==3.31.6

WORKDIR /source
COPY . .
RUN chmod +x OniLink/gradlew scripts/build-linux.sh \
    && ONILINK_RELEASE_VERSION="$RELEASE_VERSION" scripts/build-linux.sh

FROM scratch AS artifacts
COPY --from=build /source/dist/linux/ /
