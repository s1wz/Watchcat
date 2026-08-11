#!/bin/sh
# Maven Wrapper — downloads Maven 3.9.9 to .mvn/maven if needed, then runs it.
set -e

WDIR="$(cd "$(dirname "$0")" && pwd)"
MVN_VERSION="3.9.9"
MVN_HOME="$WDIR/.mvn/maven/apache-maven-$MVN_VERSION"
MVN_CMD="$MVN_HOME/bin/mvn"

if [ ! -x "$MVN_CMD" ]; then
  echo "Downloading Maven $MVN_VERSION..."
  mkdir -p "$WDIR/.mvn/maven"
  curl -sL "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$MVN_VERSION/apache-maven-$MVN_VERSION-bin.tar.gz" \
    | tar xz -C "$WDIR/.mvn/maven"
fi

exec "$MVN_CMD" "$@"
