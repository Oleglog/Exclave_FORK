@echo off

set CGO_LDFLAGS=-Wl,-z,max-page-size=16384

gomobile bind -v -androidapi 21 -trimpath -ldflags="-s -buildid= -checklinkname=0" -tags="with_clash" -o libsagernetcore.aar "github.com/dyhkwong/libsagernetcore" "github.com/openlibrecommunity/olcrtc/mobile"
if errorlevel 1 (
    exit /b 1
)

set "proj=..\..\app\libs"

if exist "%proj%" (
    copy /Y libsagernetcore.aar "%proj%"
)
