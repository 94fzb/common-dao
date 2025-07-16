#!/usr/bin/env bash
baseVersion=1.0
releaseVersion=${baseVersion}.${1}
nextVersion=${baseVersion}.$((${1}+1))-SNAPSHOT
tagName="v${releaseVersion}"
./mvnw versions:set -DnewVersion=${releaseVersion} -Dproject.build.outputTimestamp=2013-01-01T00:00:00Z
./mvnw clean install -U
git add -A
git commit -m '[shell-release]release version '${releaseVersion}
git checkout release
git reset --hard master
git tag ${tagName}
git push origin ${tagName}
git push origin release -f
git checkout master
./mvnw versions:set -DnewVersion=${nextVersion} -Dproject.build.outputTimestamp=2013-01-01T00:00:00Z
git add -A
git commit -m '[shell-release]next version '${nextVersion}
git push