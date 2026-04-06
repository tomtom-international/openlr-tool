# Config Template Directory

This directory serves as the template for creating new OpenLR WebTool profiles. Copy it to create a new self-contained profile.

## Ultra Simple Setup

### 1. Copy Template to New Profile
```bash
cp -r config configs/my-new-profile
```

### 2. Customize Your Profile
```bash
vim configs/my-new-profile/application.properties
```

Update the profile name, port, and database settings for your environment.

### 3. Start Your Profile
```bash
./scripts/docker-run.sh up my-new-profile
```

## What's Included

This template provides:
- Complete `application.properties` settings for PostgreSQL and SQLite deployments
- Default decoder and encoder properties
- Optional banner and logging configuration
- Multiple decoder variants

## Key Customization Points

```properties
# 1. Basic Info
application.title=Your Profile Name
server.port=UNIQUE_PORT

# 2. Properties Paths
decoder_properties_dir=/app/configs/CONFIG_NAME/decoding_properties
encoding_properties_dir=/app/configs/CONFIG_NAME/encoding_properties

# 3. Map Database (choose one)
# PostgreSQL
# spring.datasource.url=jdbc:postgresql://host:port/database
# spring.datasource.username=user
# spring.datasource.password=pass

# SQLite
# spring.datasource.url=jdbc:sqlite:/maps/your-database.db
```

## Benefits of This Approach

- No redundancy: use the actual working config as template
- Self-contained profile layout
- Fast setup for new environments
- Pre-configured defaults for common cases

## Need Help?

- List all profiles: `./scripts/docker-run.sh list-configs`
- Check ports in use: `grep "server.port" configs/*/application.properties`
- View logs: `./scripts/docker-run.sh logs your-profile-name`
