#!/usr/bin/env bash

if [[ $# -lt 1 ]]; then
    echo "Usage: $0 FUSION_VERSION" >&2
    exit 1
fi

set -euxo pipefail

# Retrieve the script directory.
SCRIPT_DIR="${BASH_SOURCE%/*}"
cd ${SCRIPT_DIR}

FUSION_VERSION=$1
SERVER_LOCATION="https://repo1.maven.org/maven2/io/fusion/fusion-server/${FUSION_VERSION}/fusion-server-${FUSION_VERSION}.tar.gz"
CLIENT_LOCATION="https://repo1.maven.org/maven2/io/fusion/fusion-cli/${FUSION_VERSION}/fusion-cli-${FUSION_VERSION}-executable.jar"

WORK_DIR="$(mktemp -d)"
curl -o ${WORK_DIR}/fusion-server-${FUSION_VERSION}.tar.gz ${SERVER_LOCATION}
tar -C ${WORK_DIR} -xzf ${WORK_DIR}/fusion-server-${FUSION_VERSION}.tar.gz
rm ${WORK_DIR}/fusion-server-${FUSION_VERSION}.tar.gz
cp -R bin ${WORK_DIR}/fusion-server-${FUSION_VERSION}
cp -R default -t ${WORK_DIR}

curl -o ${WORK_DIR}/fusion-cli-${FUSION_VERSION}-executable.jar ${CLIENT_LOCATION}
chmod +x ${WORK_DIR}/fusion-cli-${FUSION_VERSION}-executable.jar

CONTAINER="fusion:${FUSION_VERSION}"

docker build ${WORK_DIR} --pull -f Dockerfile -t ${CONTAINER} --build-arg "FUSION_VERSION=${FUSION_VERSION}"

rm -r ${WORK_DIR}

# Source common testing functions
. container-test.sh

test_container ${CONTAINER}
