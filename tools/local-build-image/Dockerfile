FROM sha256:1a59251fdc2b6e8277cb6bd1a2fbfa42a57928e728ade8f6c1af1b837c95e939

ARG TEMURIN_RELEASE=21.0.9%2B10
ARG TEMURIN_VERSION=21.0.9_10
ARG TEMURIN_SHA256=810d3773df7e0d6c4394e4e244b264c8b30e0b05a0acf542d065fd78a6b65c2f

ENV JAVA_HOME=/opt/java/openjdk \
    PATH=/opt/java/openjdk/bin:${PATH}

RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
      python3-pip=23.0.1+dfsg-1 \
      python3-yaml=6.0-3+b2; \
    rm -rf /var/lib/apt/lists/*; \
    mkdir -p "$JAVA_HOME"; \
    curl -fsSL "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-${TEMURIN_RELEASE}/OpenJDK21U-jdk_x64_linux_hotspot_${TEMURIN_VERSION}.tar.gz" -o "/tmp/OpenJDK21U-jdk_x64_linux_hotspot_${TEMURIN_VERSION}.tar.gz"; \
    curl -fsSL "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-${TEMURIN_RELEASE}/OpenJDK21U-jdk_x64_linux_hotspot_${TEMURIN_VERSION}.tar.gz.sha256.txt" -o /tmp/jdk.sha256; \
    grep -Fx "${TEMURIN_SHA256}  OpenJDK21U-jdk_x64_linux_hotspot_${TEMURIN_VERSION}.tar.gz" /tmp/jdk.sha256; \
    (cd /tmp && sha256sum -c jdk.sha256); \
    tar -xzf "/tmp/OpenJDK21U-jdk_x64_linux_hotspot_${TEMURIN_VERSION}.tar.gz" --strip-components=1 -C "$JAVA_HOME"; \
    rm "/tmp/OpenJDK21U-jdk_x64_linux_hotspot_${TEMURIN_VERSION}.tar.gz" /tmp/jdk.sha256 "$JAVA_HOME/lib/src.zip"; \
    update-alternatives --install /usr/bin/java java "$JAVA_HOME/bin/java" 2109; \
    update-alternatives --install /usr/bin/javac javac "$JAVA_HOME/bin/javac" 2109; \
    java -version 2>&1 | grep -F 'openjdk version "21.'; \
    python3 -m pip --version; \
    python3 -c 'import yaml; assert yaml.__version__ == "6.0"'
