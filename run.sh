#!/bin/bash
echo "Starting"

mvn clean install -DskipTests -Dcheckstyle.skip

cd examples

sh docker_build.sh

cd docker

docker-compose -f docker-compose_default.yaml up -d

echo "Done"