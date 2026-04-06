# Database Setup Sidecar Container
# Provides psql and ogr2ogr for loading map data into PostgreSQL
# Builds for native architecture (arm64 or amd64)

FROM ubuntu:22.04

# Install PostgreSQL client and GDAL tools
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        postgresql-client \
        gdal-bin \
        curl \
        bash \
        ca-certificates && \
    rm -rf /var/lib/apt/lists/*

# Copy entrypoint script
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

WORKDIR /data

ENTRYPOINT ["/entrypoint.sh"]
