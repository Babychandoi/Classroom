# MinIO server + mc client built from source at pinned release tags.
#
# Why: MinIO no longer publishes pullable images (docker.io/minio/* is denied, quay.io/minio/* needs
# login / has no usable tags), so the stack builds them itself. Only Docker Hub (golang, alpine) and
# proxy.golang.org are needed, and the result works on any CPU (arm64 and x86_64).
#
#   docker build --target minio -t classroom/minio:RELEASE.2024-05-10T01-41-38Z infra/minio
#   docker build --target mc    -t classroom/mc:RELEASE.2024-05-09T17-04-24Z    infra/minio
# (docker compose up --build does exactly this for the `minio` and `minio-init` services.)
#
# Same runtime contract as the former quay.io images: ENTRYPOINT is the binary itself (so compose
# `command: server /data --console-address ":9001"` works), runs as root (existing volumes are
# root-owned), curl is present for the healthcheck, /bin/sh exists for the minio-init entrypoint.

FROM golang:1.22-alpine AS build
RUN apk add --no-cache git ca-certificates
ENV CGO_ENABLED=0 GOFLAGS=-trimpath GOTOOLCHAIN=local
ARG MINIO_VERSION=RELEASE.2024-05-10T01-41-38Z
ARG MC_VERSION=RELEASE.2024-05-09T17-04-24Z
RUN go install -ldflags "-s -w" github.com/minio/minio@${MINIO_VERSION}
RUN go install -ldflags "-s -w" github.com/minio/mc@${MC_VERSION}

FROM alpine:3.20 AS minio
RUN apk add --no-cache ca-certificates curl
COPY --from=build /go/bin/minio /usr/bin/minio
EXPOSE 9000 9001
VOLUME ["/data"]
ENTRYPOINT ["/usr/bin/minio"]

FROM alpine:3.20 AS mc
RUN apk add --no-cache ca-certificates curl
COPY --from=build /go/bin/mc /usr/bin/mc
ENTRYPOINT ["/usr/bin/mc"]
