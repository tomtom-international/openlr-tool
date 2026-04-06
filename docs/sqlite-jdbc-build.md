# Custom SQLite JDBC Build Documentation

## Overview

OpenLR WebTool requires a custom SQLite JDBC driver built with `SQLITE_ENABLE_LOAD_EXTENSION=1` to support SpatiaLite extension loading in containerized environments.

## Why Custom Build is Needed

The standard Xerial SQLite JDBC driver:
- Bundles SQLite internally and runs in a sandboxed environment
- Cannot access system libraries like `/usr/lib/aarch64-linux-gnu/mod_spatialite.so`
- Does not have extension loading enabled by default

## Build Instructions

### Prerequisites
- Git
- Maven 3.6+
- JDK 17+
- Build tools (gcc, make, etc.)

### Build Commands

```bash
# Clone the SQLite JDBC repository
git clone https://github.com/xerial/sqlite-jdbc.git
cd sqlite-jdbc

# Checkout specific version for reproducibility (recommended)
git checkout 3.50.3.0

# Build with extension loading enabled
mvn clean package -Prelease -Dsqlite.nativeBuildArgs="-DSQLITE_ENABLE_LOAD_EXTENSION=1" -DskipTests
```

### Output

The build produces: `target/sqlite-jdbc-X.Y.Z.jar`

This JAR contains:
- ✅ SQLite compiled with `SQLITE_ENABLE_LOAD_EXTENSION=1`
- ✅ Native libraries for multiple platforms (Linux, macOS, Windows)
- ✅ Ability to load system extensions like SpatiaLite

### Version Management

**For reproducibility and stability:**
- Always use tagged releases (e.g., `3.50.3.0`) instead of master branch
- Document the specific version being used in both Dockerfile and build scripts
- Test thoroughly when upgrading to newer versions
- Keep the version consistent between local builds and Docker builds

## Integration

### Current Setup

The custom JAR is located at: `libs/sqlite-jdbc-3.50.3.1-SNAPSHOT.jar`

### Gradle Configuration

Both SQLite-based modules use the custom JAR:

```kotlin
dependencies {
    implementation(files("../../libs/sqlite-jdbc-3.50.3.1-SNAPSHOT.jar"))
    // Standard sqlite-jdbc dependency removed
}
```

### Docker Configuration

The Dockerfile uses explicit classpath ordering:

```dockerfile
# Copy custom SQLite JDBC for SpatiaLite extension support
COPY libs/sqlite-jdbc-3.50.3.1-SNAPSHOT.jar /app/libs/

# Entry point with explicit classpath to prioritize custom SQLite JDBC
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -cp /app/libs/sqlite-jdbc-3.50.3.1-SNAPSHOT.jar:app.jar -Dspring.profiles.active=${SPRING_PROFILES_ACTIVE} org.springframework.boot.loader.JarLauncher"]
```

## Verification

### Test SpatiaLite Loading

```java
import java.sql.*;

public class TestSpatiaLite {
    public static void main(String[] args) {
        try {
            Class.forName("org.sqlite.JDBC");
            Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:?enable_load_extension=true");

            Statement stmt = conn.createStatement();
            stmt.execute("SELECT load_extension('mod_spatialite')");

            ResultSet rs = stmt.executeQuery("SELECT spatialite_version()");
            if (rs.next()) {
                System.out.println("SUCCESS: SpatiaLite version " + rs.getString(1));
            }
            conn.close();
        } catch (Exception e) {
            System.out.println("ERROR: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
```

### Expected Success Output

```
Testing SpatiaLite extension loading...
SUCCESS: SpatiaLite version 5.0.1
Container SQLite + SpatiaLite working correctly!
```

## Updating the JAR

When SQLite JDBC versions change:

1. **Update source version**:
   ```bash
   git clone https://github.com/xerial/sqlite-jdbc.git
   cd sqlite-jdbc
   git checkout <new-version-tag>
   ```

2. **Build with same parameters**:
   ```bash
   mvn clean package -Prelease -Dsqlite.nativeBuildArgs="-DSQLITE_ENABLE_LOAD_EXTENSION=1" -DskipTests
   ```

3. **Replace in project**:
   ```bash
   cp target/sqlite-jdbc-*.jar /path/to/webtool/libs/
   ```

4. **Update references** in:
   - `mapdatabases/tomtom_sqlite/build.gradle.kts`
   - `mapdatabases/generic_sqlite/build.gradle.kts`
   - `docker/Dockerfile`

## Architecture Support

The Maven build automatically creates native libraries for:
- **Linux**: x86_64, aarch64, x86
- **macOS**: x86_64, aarch64 (Apple Silicon)
- **Windows**: x86_64, x86

## Troubleshooting

### Build Failures

**Missing dependencies**:
```bash
# Ubuntu/Debian
sudo apt-get install build-essential cmake

# macOS
xcode-select --install
```

**Maven issues**:
```bash
# Clean Maven cache
rm -rf ~/.m2/repository/org/xerial/sqlite-jdbc
```

### Runtime Issues

**Extension still not loading**:
- Verify JVM has correct library path: `-Djava.library.path=/usr/lib/aarch64-linux-gnu`
- Check custom JAR is first in classpath
- Ensure SpatiaLite library exists: `ls /usr/lib/aarch64-linux-gnu/mod_spatialite*`

**Classpath conflicts**:
- Remove standard SQLite JDBC from dependencies
- Use explicit classpath ordering in Docker ENTRYPOINT
- Verify with: `jar tf your-fat-jar.jar | grep sqlite-jdbc`

## Benefits

✅ **Reproducible**: Anyone can rebuild from source with documented commands
✅ **Transparent**: Clear build process and parameters
✅ **Maintainable**: Easy to update for new SQLite versions
✅ **Cross-platform**: Supports all deployment architectures
✅ **Documented**: Full provenance and reasoning captured