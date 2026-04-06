#!/bin/bash
ogr2ogr --help
psql postgresql://openlr:openlrpwd@postgres:5432/openlr_db -c "select count(*) from local.roads;"
echo "Hello, World!"
