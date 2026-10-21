#!/usr/bin/env bash
# Empaqueta ksqlc para el SO actual: instalador (.dmg/.deb) + app portable (zip) en dist/.
# jpackage no compila en cruzado: cada SO genera lo suyo (ver .github/workflows/release.yml).
set -euo pipefail
cd "$(dirname "$0")"

./run.sh :app:packageDistributionForCurrentOS :app:createDistributable

bin=app/build/compose/binaries/main
rm -rf dist && mkdir -p dist
shopt -s nullglob  # cada SO genera solo uno de los dos formatos
pkgs=("$bin"/dmg/*.dmg "$bin"/deb/*.deb)
[[ ${#pkgs[@]} -gt 0 ]] || { echo "No se generó ningún instalador en $bin" >&2; exit 1; }
cp "${pkgs[@]}" dist/
(cd "$bin/app" && zip -qry "$OLDPWD/dist/ksqlc-$(uname -s)-$(uname -m).zip" .)
ls -l dist
