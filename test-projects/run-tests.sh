#!/bin/bash

set -e

plugin_version_arg="-Dmima-maven-plugin.version=$1"

pushd 0.1.0
mvn install $plugin_version_arg
popd

pushd 0.1.1-good
mvn install $plugin_version_arg
popd

pushd 0.1.1-bad
set +e
mvn install $plugin_version_arg
if [ $? -eq 0 ]; then
	echo '0.1.1-bad build should have failed' >&2
	exit 1
fi
set -e
popd

pushd 0.2.0
mvn install $plugin_version_arg
popd
