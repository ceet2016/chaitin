# 雷池 WAF 社区版 Android 客户端 —— CI 构建环境
# JDK 17 + Android SDK（platform 34 / build-tools 34.0.0）+ Gradle 8.7
# 仅用于流水线 docker.build，不进 APK 产物，也不影响本地开发环境。
FROM eclipse-temurin:17-jdk-jammy

ENV DEBIAN_FRONTEND=noninteractive \
    ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    GRADLE_USER_HOME=/root/.gradle \
    LANG=C.UTF-8

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl unzip git ca-certificates \
 && rm -rf /var/lib/apt/lists/*

# ---- Android SDK command-line tools ----
ARG CMDLINE_TOOLS_VERSION=11076708
RUN mkdir -p "${ANDROID_HOME}/cmdline-tools" \
 && curl -fsSL -o /tmp/cmdline-tools.zip \
      "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip" \
 && unzip -q /tmp/cmdline-tools.zip -d /tmp/cmdline-tools \
 && mv /tmp/cmdline-tools/cmdline-tools "${ANDROID_HOME}/cmdline-tools/latest" \
 && rm -rf /tmp/cmdline-tools.zip /tmp/cmdline-tools

ENV PATH="${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${PATH}"

# ---- 安装 SDK 组件并接受许可 ----
RUN yes | sdkmanager --licenses > /dev/null \
 && sdkmanager --install "platform-tools" "platforms;android-34" "build-tools;34.0.0" > /dev/null

# ---- 预装 Gradle 发行包（与 gradle/wrapper 版本一致，避免构建时下载）----
ARG GRADLE_VERSION=8.7
RUN curl -fsSL -o /tmp/gradle.zip \
      "https://mirrors.cloud.tencent.com/gradle/gradle-${GRADLE_VERSION}-bin.zip" \
 && unzip -q /tmp/gradle.zip -d /opt \
 && rm -f /tmp/gradle.zip \
 && ln -s "/opt/gradle-${GRADLE_VERSION}/bin/gradle" /usr/local/bin/gradle

WORKDIR /workspace
