#!/usr/bin/env bash
# Собирает пакет Arch: uber-jar apps/desktop -> packaging/arch/mimir.jar -> makepkg.
# pkgname и pkgver берутся из gradle.properties (единственный источник идентификаторов).
# Запускается из корня репозитория; в CI — в контейнере archlinux:latest от root.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"

prop() { sed -n "s/^$1=//p" gradle.properties; }
pkgname="$(prop mimir.pacmanName)"
pkgver="$(prop mimir.version)"

./gradlew :apps:desktop:packageUberJarForCurrentOS --no-daemon --console=plain

jar="$(ls apps/desktop/build/compose/jars/*.jar | head -n 1)"
cp "$jar" packaging/arch/mimir.jar

cd packaging/arch
sed -i -e "s/^pkgname=.*/pkgname=${pkgname}/" -e "s/^pkgver=.*/pkgver=${pkgver}/" PKGBUILD

# --nodeps: java-runtime нужен только при установке пакета, не при сборке (JDK может быть из mise).
if [ "$(id -u)" -eq 0 ]; then
    # makepkg отказывается работать от root: собираем под временным пользователем.
    id builder >/dev/null 2>&1 || useradd -m builder
    chown -R builder: .
    su builder -c "makepkg -f --nodeps"
else
    makepkg -f --nodeps
fi
ls ./*.pkg.tar.zst
