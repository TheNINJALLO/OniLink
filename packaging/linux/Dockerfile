FROM ubuntu:22.04 AS build

ARG DEBIAN_FRONTEND=noninteractive
ARG RELEASE_VERSION=0.1.4
RUN apt-get update && apt-get install -y --no-install-recommends \
    binutils ca-certificates curl git gnupg lsb-release ninja-build \
    openjdk-21-jdk-headless python3 python3-pip \
    && rm -rf /var/lib/apt/lists/* \
    && python3 -m pip install --no-cache-dir cmake==3.31.6

COPY scripts/install-llvm18-ubuntu.sh /tmp/install-llvm18-ubuntu.sh
RUN /tmp/install-llvm18-ubuntu.sh && rm -f /tmp/install-llvm18-ubuntu.sh

WORKDIR /source
COPY . .
RUN chmod +x OniLink/gradlew scripts/build-linux.sh \
    && ONILINK_RELEASE_VERSION="$RELEASE_VERSION" \
       ONIBRIDGE_CXX_COMPILER=clang++-18 \
       scripts/build-linux.sh

FROM scratch AS artifacts
COPY --from=build /source/dist/linux/ /
