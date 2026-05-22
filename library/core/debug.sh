#!/bin/bash

cd olcrtc_local && go mod tidy && cd .. || exit 1
go mod tidy || exit 1

CGO_LDFLAGS="-Wl,-z,max-page-size=16384" gomobile bind -v -androidapi 21 -ldflags="-checklinkname=0" -tags="with_clash" -o libsagernetcore.aar "github.com/dyhkwong/libsagernetcore" "github.com/openlibrecommunity/olcrtc/mobile" || exit 1

proj=../../app/libs
if [ -d $proj ]; then
  cp -vf libsagernetcore.aar $proj
fi
