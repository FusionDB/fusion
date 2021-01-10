#!/usr/bin/env bash

set -euxo pipefail

SOURCE_DIR="../.."

# Retrieve the script directory.
SCRIPT_DIR="${BASH_SOURCE%/*}"
cd ${SCRIPT_DIR}

# Move to the root directory to run maven for current version.
pushd ${SOURCE_DIR}
FUSION_VERSION=$(./mvnw --quiet help:evaluate -Dexpression=project.version -DforceStdout)
popd

WORK_DIR="$(mktemp -d)"
cp ${SOURCE_DIR}/core/fusion-server/target/fusion-server-${FUSION_VERSION}.tar.gz ${WORK_DIR}
tar -C ${WORK_DIR} -xzf ${WORK_DIR}/fusion-server-${FUSION_VERSION}.tar.gz
rm ${WORK_DIR}/fusion-server-${FUSION_VERSION}.tar.gz
cp -R bin ${WORK_DIR}/fusion-server-${FUSION_VERSION}
cp -R default -t ${WORK_DIR}

cp ${SOURCE_DIR}/client/fusion-cli/target/fusion-cli-${FUSION_VERSION}-executable.jar ${WORK_DIR}

CONTAINER="fusion:${FUSION_VERSION}"

docker build ${WORK_DIR} --pull -f Dockerfile -t ${CONTAINER} --build-arg "FUSION_VERSION=${FUSION_VERSION}"

rm -r ${WORK_DIR}

# Source common testing functions
. container-test.sh

test_container ${CONTAINER}
