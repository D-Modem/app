# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 D-Modem Authors
#
# The Android app built in a container, with what CI builds it with
# (.github/workflows/android.yml): JDK 21, the SDK's platform 37, NDK 29,
# CMake 3.31.6, and gawk for proot; and for the unit tests, the host's
# compilers (the engine's host build, native-test) and the SSH tools.
#
# D-Modem-RE is a private submodule, so it isn't cloned here: check the
# submodules out first (git submodule update --init --recursive), and the
# build takes them from the tree.
#
#   The debug APK, into ./out:
#     docker build --target apk --output out .
#
#   A shell with the toolchain and the source (the unit tests' tools too):
#     docker build -t dmodem-android .
#     docker run --rm -it dmodem-android sh -c \
#       'cmake -S native-test -B build-host -G Ninja && cmake --build build-host && ./gradlew testDebugUnitTest'
#
#   The release APK (unsigned): --build-arg TASK=assembleRelease
#
#   Behind a proxy that re-signs TLS, its CA:
#     --secret id=ca,src=/path/to/ca.crt  (and --network host if it's local)

# (by digest: the same image each time; a newer one's digest from
# `docker buildx imagetools inspect ubuntu:24.04`)
ARG UBUNTU=24.04@sha256:68ee22004a9109b44973313b02ff7b1de250f1de0f5ecb628d73f579aaff33ac

FROM ubuntu:${UBUNTU} AS toolchain

ARG CMDLINE_TOOLS=11076708
ARG CMDLINE_TOOLS_SHA256=2d2d50857e4eb553af5a6dc3ad507a17adf43d115264b1afc116f95c92e5e258
ARG PLATFORM=android-37.0
ARG BUILD_TOOLS=36.0.0
ARG NDK=29.0.14206865
ARG CMAKE=3.31.6

ENV DEBIAN_FRONTEND=noninteractive \
    ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    GRADLE_USER_HOME=/root/.gradle \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8

COPY docker/java-proxy.sh /etc/java-proxy.sh

# the CA, if one's given, first: everything after fetches through it (one
# file for each certificate in it: Java's keystore takes a file's first)
RUN --mount=type=secret,id=ca,required=false \
    if [ -s /run/secrets/ca ]; then \
      mkdir -p /usr/local/share/ca-certificates/build \
      && awk '/BEGIN CERTIFICATE/ { if (f) close(f); f = sprintf("/usr/local/share/ca-certificates/build/ca-%03d.crt", ++n) } \
             f { print > f }' /run/secrets/ca; \
    fi \
 && apt-get update \
 && apt-get install -y --no-install-recommends \
      ca-certificates curl unzip git make gawk python3 \
      openjdk-21-jdk-headless \
      openssh-client openssh-server putty-tools mksh \
      cmake ninja-build g++ libssl-dev \
 && update-ca-certificates \
 && mkdir -p /run/sshd \
 && rm -rf /var/lib/apt/lists/*

# the SDK: its command-line tools, then the packages the build uses
RUN . /etc/java-proxy.sh \
 && mkdir -p $ANDROID_HOME/cmdline-tools \
 && curl -fsSL -o /tmp/clt.zip https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS}_latest.zip \
 && echo "$CMDLINE_TOOLS_SHA256  /tmp/clt.zip" | sha256sum -c - \
 && unzip -q /tmp/clt.zip -d $ANDROID_HOME/cmdline-tools \
 && mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest \
 && rm /tmp/clt.zip \
 && yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses > /dev/null \
 && $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager \
      "platform-tools" "platforms;${PLATFORM}" "build-tools;${BUILD_TOOLS}" \
      "ndk;${NDK}" "cmake;${CMAKE}" > /dev/null

ENV PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH

WORKDIR /src

# Gradle itself and the dependencies, from the build files alone: a layer
# kept while only the source changes
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY app/build.gradle.kts app/proguard-rules.pro app/
COPY shared/build.gradle.kts shared/
RUN . /etc/java-proxy.sh \
 && (./gradlew --no-daemon -q help || true)

FROM toolchain AS build
ARG TASK=assembleDebug
COPY . .
RUN test -f external/d-modem-re/CMakeLists.txt && test -f external/proot/src/GNUmakefile \
    || { echo "the submodules aren't checked out: git submodule update --init --recursive" >&2; exit 1; }
RUN . /etc/java-proxy.sh \
 && echo "sdk.dir=$ANDROID_HOME" > local.properties \
 && ./gradlew --no-daemon ${TASK} \
 && mkdir -p /out && find app/build/outputs/apk -name '*.apk' -exec cp {} /out/ \;

# just the APKs: docker build --target apk --output out .
FROM scratch AS apk
COPY --from=build /out/ /

# the default: the toolchain with the source, to run Gradle in
FROM toolchain
COPY . .
RUN echo "sdk.dir=$ANDROID_HOME" > local.properties
CMD ["./gradlew", "--no-daemon", "assembleDebug"]
