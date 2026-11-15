# Local build image

From the workspace root, build the amd64 image with the pinned local base digest:

```sh
feedback/lead/tools/gated-run.sh 3 --name localica-train-0-image-build --rm \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v "$PWD/worktrees/luna-local-image-0/tools/local-build-image:/src:ro" \
  alpine:latest sh -ec 'apk add --no-cache docker-cli; docker build --platform linux/amd64 --pull=false --file /src/Dockerfile --tag localica-dev:owned-emulator-20261029 /src'
```

The Dockerfile pins the base image by its local image digest, checks Temurin JDK 21.0.9+10 against the release's published SHA-256 file, and pins Debian's Python package versions. Java 21 becomes the system default; Java 17 remains installed in the base image. The recipe targets Linux amd64.
