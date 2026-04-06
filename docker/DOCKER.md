# OpenLR WebTool - Docker Deployment Guide

This guide explains how to run OpenLR WebTool using Docker with a **config-driven approach** that automatically discovers available configurations.

## Config-Driven Architecture

The new approach eliminates hardcoded profile names and instead:
- ✅ **Auto-discovers** configurations from `configs/` directory
- ✅ **Dynamic port assignment** with sensible defaults
- ✅ **Single template** handles all configurations
- ✅ **Validates configs** exist before starting
- ✅ **Flexible deployment** for any config structure

## Quick Start

### Build the Image
```bash
# Build OpenLR WebTool Docker image
./scripts/docker-run.sh build
```

### Start Any Configuration
```bash
# Start specific configs (auto-discovered from configs/ directory)
./scripts/docker-run.sh up orbis          # Port 8086
./scripts/docker-run.sh up poland         # Port 8081
./scripts/docker-run.sh up trucklanes     # Port 8084

# Start all available configs
./scripts/docker-run.sh up all

# List available configs
./scripts/docker-run.sh list-configs
```

### Custom Port Override
```bash
# Override default port assignment
PORT=9001 ./scripts/docker-run.sh up orbis
PORT=9002 ./scripts/docker-run.sh up poland
```

## Available Commands

### Basic Operations
```bash
# Build Docker image
./scripts/docker-run.sh build

# Start configuration(s)
./scripts/docker-run.sh up <config_name>
./scripts/docker-run.sh up all

# Stop configuration(s)
./scripts/docker-run.sh down <config_name>
./scripts/docker-run.sh down all

# View logs
./scripts/docker-run.sh logs <config_name>
./scripts/docker-run.sh logs all

# Restart configuration
./scripts/docker-run.sh restart <config_name>
```

### Management Commands
```bash
# Show container status
./scripts/docker-run.sh status

# List available configurations
./scripts/docker-run.sh list-configs

# Clean up all containers and images
./scripts/docker-run.sh clean
```

## Configuration Discovery

The script automatically discovers configurations by scanning the `configs/` directory:

```
configs/
├── poland_orbis_2024.02.2900/     → ./scripts/docker-run.sh up poland_orbis_2024.02.2900
├── taiwan_mnr_2024.03.005/        → ./scripts/docker-run.sh up taiwan_mnr_2024.03.005
├── trucklanes/                    → ./scripts/docker-run.sh up trucklanes
├── trucklanes-orbis/              → ./scripts/docker-run.sh up trucklanes-orbis
└── uturn-fix/                     → ./scripts/docker-run.sh up uturn-fix
```

## Default Port Mappings

| Configuration | Default Port | Override Example |
|---------------|--------------|------------------|
| poland        | 8081        | `PORT=9001 ./scripts/docker-run.sh up poland` |
| taiwan        | 8082        | `PORT=9002 ./scripts/docker-run.sh up taiwan` |
| trucklanes-orbis | 8083     | `PORT=9003 ./scripts/docker-run.sh up trucklanes-orbis` |
| trucklanes    | 8084        | `PORT=9004 ./scripts/docker-run.sh up trucklanes` |
| uturn-fix     | 8085        | `PORT=9005 ./scripts/docker-run.sh up uturn-fix` |
| orbis         | 8086        | `PORT=9006 ./scripts/docker-run.sh up orbis` |
| *any other*   | 8080        | `PORT=9999 ./scripts/docker-run.sh up custom-config` |

## Docker Architecture

### Single Template Approach
- **File**: `docker/docker-compose-template.yml`
- **Dynamic service naming**: `openlr-${CONFIG_NAME}`
- **Dynamic volume mapping**: `configs/${CONFIG_NAME}:/app/configs/${CONFIG_NAME}:ro`
- **Environment-driven**: Uses `CONFIG_NAME` and `PORT` variables

### Container Configuration
- **Base Image**: Built from `docker/Dockerfile`
- **JVM Settings**: Tuned for larger datasets and local debugging
- **Health Checks**: `/api/v1/purgeCache` endpoint monitoring
- **Volume Mounts**:
  - `maps/` → `/maps:ro` (read-only maps)
  - `configs/{config}/` → `/app/configs/{config}:ro` (config-specific)
  - `config/` → `/app/config:ro` (shared Spring config)

## Adding New Configurations

To add a new configuration:

1. **Copy template**: `cp -r config configs/my-new-config`
2. **Edit 3 lines**: Update title, port, and paths in `configs/my-new-config/application.properties`
3. **Start immediately**: `./scripts/docker-run.sh up my-new-config`

No code changes, no separate Spring profiles, no hardcoded references needed!

## Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `CONFIG_NAME` | Configuration name | Required |
| `PORT` | External port to expose | Config-specific default |
| `JAVA_OPTS` | JVM options | Large-dataset defaults |

## Legacy Compose Files

The old individual compose files are retained for compatibility:
- `docker-compose.yml` - All profiles
- `docker-compose-poland.yml` - Poland only
- `docker-compose-taiwan.yml` - Taiwan only
- `docker-compose-dev.yml` - Parameterized

**Recommendation**: Use the new config-driven approach with `docker-compose-template.yml` for maximum flexibility.

## Production Deployment

### Single Configuration
```bash
# Production deployment of orbis config on port 8080
./scripts/docker-run.sh build
./scripts/docker-run.sh up orbis
```

### Multiple Configurations
```bash
# Deploy multiple configs with custom ports
PORT=8081 ./scripts/docker-run.sh up poland &
PORT=8082 ./scripts/docker-run.sh up taiwan &
PORT=8083 ./scripts/docker-run.sh up trucklanes-orbis &
wait
```

### Load Balancer Setup
Use the config-driven containers behind nginx/haproxy:

```yaml
# nginx.conf upstream
upstream openlr-backend {
    server localhost:8081;  # poland
    server localhost:8082;  # taiwan
    server localhost:8083;  # trucklanes-orbis
}
```

## Monitoring and Logs

### Container Status
```bash
# Quick status check
./scripts/docker-run.sh status

# Detailed Docker info
docker ps -f "name=openlr-"
```

### Log Management
```bash
# Real-time logs for specific config
./scripts/docker-run.sh logs orbis

# Historical logs
docker logs openlr-orbis

# All configs (last 50 lines each)
./scripts/docker-run.sh logs all
```

### Health Checks
All containers include automatic health monitoring:
- **Endpoint**: `GET /api/v1/purgeCache`
- **Interval**: 30 seconds
- **Timeout**: 10 seconds
- **Retries**: 3 attempts

## Troubleshooting

### Configuration Not Found
```bash
❌ Error: Config directory '../configs/myconfig' not found

Available configs:
poland_orbis_2024.02.2900
taiwan_mnr_2024.03.005
trucklanes
trucklanes-orbis
uturn-fix
```

**Solution**: Use `./scripts/docker-run.sh list-configs` to see available options.

### Port Conflicts
```bash
# Check what's using a port
lsof -i :8081

# Use different port
PORT=9001 ./scripts/docker-run.sh up poland
```

### Container Health Issues
```bash
# Check container health
docker ps -f "name=openlr-" --format "table {{.Names}}\t{{.Status}}"

# View detailed logs
./scripts/docker-run.sh logs <config_name>

# Restart unhealthy container
./scripts/docker-run.sh restart <config_name>
```

## Benefits of Config-Driven Approach

| Aspect | Old Approach | New Approach |
|--------|-------------|--------------|
| **Adding Configs** | Edit scripts + compose files | Just add directory |
| **Port Management** | Hardcoded in multiple files | Single configuration point |
| **Validation** | Manual checking | Automatic validation |
| **Discovery** | Must know exact names | Auto-discovery |
| **Maintenance** | Multiple files to sync | Single template |
| **Flexibility** | Limited to predefined profiles | Any configuration possible |

The new approach scales effortlessly and requires zero code changes for new configurations.