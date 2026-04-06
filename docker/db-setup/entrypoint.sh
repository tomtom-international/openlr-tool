#!/bin/bash
set -e

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  OpenLR WebTool - Database Setup Container"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

# Display environment
echo "Database Connection:"
echo "  Host:     ${PGHOST:-localhost}"
echo "  Port:     ${PGPORT:-5432}"
echo "  Database: ${PGDATABASE:-postgres}"
echo "  User:     ${PGUSER:-postgres}"
echo ""

# Wait for PostgreSQL to be ready
echo "Waiting for PostgreSQL to be ready..."
MAX_RETRIES=30
RETRY_COUNT=0

until pg_isready -h "${PGHOST}" -p "${PGPORT}" -U "${PGUSER}" -d "${PGDATABASE}" > /dev/null 2>&1; do
    RETRY_COUNT=$((RETRY_COUNT + 1))
    if [ $RETRY_COUNT -ge $MAX_RETRIES ]; then
        echo "❌ PostgreSQL did not become ready in time"
        exit 1
    fi
    echo "   Waiting... (attempt $RETRY_COUNT/$MAX_RETRIES)"
    sleep 2
done

echo "✅ PostgreSQL is ready"
echo ""

# Check for dbsetup.sh script
echo "Looking for database setup script..."
if [ ! -f /data/dbsetup.sh ]; then
    echo "❌ No /data/dbsetup.sh found"
    echo ""
    echo "To use this container, create a dbsetup.sh script in your data directory."
    echo "Example:"
    echo ""
    echo "  #!/bin/bash"
    echo "  set -e"
    echo "  echo 'Loading map data...'"
    echo "  psql -f schema.sql"
    echo "  ogr2ogr -f PostgreSQL PG:\"host=\$PGHOST dbname=\$PGDATABASE\" roads.shp"
    echo ""
    echo "Then run:"
    echo "  ./dc setup /path/to/your/data"
    echo ""
    exit 1
fi

echo "✅ Found /data/dbsetup.sh"
echo ""

# Display script contents (first 20 lines)
echo "Script contents (preview):"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
head -n 20 /data/dbsetup.sh | sed 's/^/  /'
if [ $(wc -l < /data/dbsetup.sh) -gt 20 ]; then
    echo "  ... (truncated)"
fi
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

# Execute the setup script
echo "Executing database setup script..."
echo ""

cd /data
if bash ./dbsetup.sh; then
    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "✅ Database setup completed successfully"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    exit 0
else
    EXIT_CODE=$?
    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "❌ Database setup failed (exit code: $EXIT_CODE)"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    exit $EXIT_CODE
fi
