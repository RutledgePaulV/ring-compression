#!/usr/bin/env bash

clj -X:build clean
clj -X:build jar

mvn deploy:deploy-file \
  -DgroupId="io.github.rutledgepaulv" \
  -DartifactId="ring-compression" \
  -Dversion="$(clj -X:build get-version)" \
  -Dpackaging="jar" \
  -Dfile="target/ring-compression.jar" \
  -DrepositoryId="clojars" \
  -Durl="https://repo.clojars.org"