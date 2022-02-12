#!/bin/sh
docker buildx create --name umlrt-rts-builder
docker buildx use umlrt-rts-builder
docker buildx build --platform linux/amd64,linux/arm64 -t kjahed/umlrt-rts:1.1 . --push

