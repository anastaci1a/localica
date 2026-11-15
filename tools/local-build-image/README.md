# Local build image

Build from the preserved `localica-dev:base-emulator-20261029` image using the local gated Docker CLI and this directory as context. The base tag is the original `localica-dev:owned-emulator-20261029` image before this update. The output tag is `localica-dev:owned-emulator-20261029`.

The recipe pins Debian's Python package versions and Temurin JDK 21.0.9+10 by SHA-256. Java 21 becomes the system default; Java 17 remains installed in the base image.
