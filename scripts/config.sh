#!/usr/bin/env bash

pushd () {
    command pushd "$@" > /dev/null
}

popd () {
    command popd "$@" > /dev/null
}

pushd .

cd "$(dirname "${BASH_SOURCE[0]}")"; cd ..

ROOT=`pwd`
CHROMIUM_MIRROR_DIR=${CHROMIUM_MIRROR_DIR:?"$ROOT/../chromium-mirror"}
DEVTOOLS_BRANCH="devtools"
DEVTOOLS_CHROMIUM_PREFIX="third_party/WebKit/Source/devtools"
CHROMIUM_MIRROR_DEVTOOLS_DIR="$CHROMIUM_MIRROR_DIR/$DEVTOOLS_CHROMIUM_PREFIX"
DEVTOOLS_DIRAC_PREFIX="resources/unpacked/devtools"
DEVTOOLS_ROOT="$ROOT/$DEVTOOLS_DIRAC_PREFIX"
DIRAC_USER_PROFILE=${DIRAC_USER_PROFILE:-".profiles/dirac"}
DEVSERVER_ROOT="$DEVTOOLS_ROOT"
DEVSERVER_PORT=9000
RELEASES="$ROOT/releases"
RELEASE_BUILD="$ROOT/resources/release"
RELEASE_BUILD_COMPILED="$RELEASE_BUILD/compiled"
CSS_PROPERTIES_SOURCE="$CHROMIUM_MIRROR_DEVTOOLS_DIR/../core/css/CSSProperties.in"
CSS_PROPERTIES_COPY="$DEVTOOLS_ROOT/CSSProperties.in"
CSS_PROPERTIES_OUTPUT_FILE="$DEVTOOLS_ROOT/front_end/SupportedCSSProperties.js"
INSPECTOR_BACKEND_COMMANDS_OUTPUT_FILE="$DEVTOOLS_ROOT/front_end/InspectorBackendCommands.js"

popd .