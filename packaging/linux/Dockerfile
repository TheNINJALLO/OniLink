FROM ubuntu:24.04 AS build

ARG DEBIAN_FRONTEND=noninteractive
ARG RELEASE_VERSION=0.1.0
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates clang cmake git libc++-dev libc++abi-dev ninja-build \
    openjdk-21-jdk-headless python3 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /source
COPY . .
RUN chmod +x OniLink/gradlew scripts/build-linux.sh \
    && ONILINK_RELEASE_VERSION="$RELEASE_VERSION" scripts/build-linux.sh

FROM scratch AS artifacts
COPY --from=build /source/dist/linux/ /
