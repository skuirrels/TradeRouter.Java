FROM debian:bookworm-slim

RUN apt-get update \
    && apt-get install --yes --no-install-recommends osmium-tool \
    && rm -rf /var/lib/apt/lists/*

ENTRYPOINT ["osmium"]
