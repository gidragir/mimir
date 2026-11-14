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
owner="$(prop mimir.githubOwner)"
repo="$(prop mimir.githubRepo)"
pkgurl="https://github.com/${owner}/${repo}"

./gradlew :apps:desktop:packageUberJarForCurrentOS -Pmimir.bundleWebUi=true --no-daemon --console=plain

jars=(apps/desktop/build/compose/jars/*.jar)
jar="${jars[0]}"
cp "$jar" packaging/arch/mimir.jar

cd packaging/arch
# Подстановки живут только на время сборки: PKGBUILD в дереве остаётся чистым. Разделитель `|` в sed не требует экранировать `/` в URL.
cp PKGBUILD PKGBUILD.orig
trap 'mv -f PKGBUILD.orig PKGBUILD' EXIT
sed -i -e "s|^pkgname=.*|pkgname=${pkgname}|" -e "s|^pkgver=.*|pkgver=${pkgver}|" -e "s|^url=.*|url='${pkgurl}'|" PKGBUILD

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
