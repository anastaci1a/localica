FROM localica-dev:base-emulator-20261029

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
    curl -fsSL "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-${TEMURIN_RELEASE}/OpenJDK21U-jdk_x64_linux_hotspot_${TEMURIN_VERSION}.tar.gz" -o /tmp/jdk.tar.gz; \
    echo "${TEMURIN_SHA256}  /tmp/jdk.tar.gz" | sha256sum -c -; \
    tar -xzf /tmp/jdk.tar.gz --strip-components=1 -C "$JAVA_HOME"; \
    rm /tmp/jdk.tar.gz "$JAVA_HOME/lib/src.zip"; \
    update-alternatives --install /usr/bin/java java "$JAVA_HOME/bin/java" 2109; \
    update-alternatives --install /usr/bin/javac javac "$JAVA_HOME/bin/javac" 2109; \
    java -version; \
    python3 -m pip --version; \
    python3 -c 'import yaml; assert yaml.__version__ == "6.0"'
