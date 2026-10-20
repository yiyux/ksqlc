#!/usr/bin/env bash
# Empaqueta ksqlc para el SO actual: instalador (.dmg/.deb) + app portable (zip) en dist/.
# jpackage no compila en cruzado: cada SO genera lo suyo (ver .github/workflows/release.yml).
set -euo pipefail
cd "$(dirname "$0")"

./run.sh :app:packageDistributionForCurrentOS :app:createDistributable

bin=app/build/compose/binaries/main
rm -rf dist && mkdir -p dist
cp "$bin"/dmg/*.dmg "$bin"/deb/*.deb dist/ 2>/dev/null || true
(cd "$bin/app" && zip -qry "$OLDPWD/dist/ksqlc-$(uname -s)-$(uname -m).zip" .)
ls -l dist
