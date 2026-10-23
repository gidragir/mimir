#!/usr/bin/env bash
# Генерирует golden-векторы rclone crypt для core/src/commonTest/.../RcloneVectors.kt.
# Нужен rclone >= 1.60 и python3. Обычная сборка и тесты скрипт не запускают.
set -euo pipefail

OUT="$(cd "$(dirname "$0")/.." && pwd)/core/src/commonTest/kotlin/dev/mimir/core/RcloneVectors.kt"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

PASSWORD="mimir-test-pass"
PASSWORD2="mimir-test-salt"
SIZES=(0 1 65535 65536 65537 131072 131073)
NAMES=("a.md" "Здоровье" "Анализы мамы.md" "заметка 😀.md" "$(printf 'x%.0s' $(seq 200))" ".obsidian")

version="$(rclone version | head -1 | sed -E 's/^rclone v([0-9]+)\.([0-9]+).*/\1 \2/')"
read -r major minor <<<"$version"
if (( major < 1 || (major == 1 && minor < 60) )); then
  echo "rclone >= 1.60 required, found: $(rclone version | head -1)" >&2
  exit 1
fi

kt_string() { python3 -I -c 'import sys; print("\"" + sys.argv[1].replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\"")' "$1"; }

# Открытый текст: байт i = (i * 7 + 3) & 0xFF
make_plain() { python3 -I -c 'import sys; sys.stdout.buffer.write(bytes((i * 7 + 3) & 0xFF for i in range(int(sys.argv[1]))))' "$1" > "$2"; }

# base64 порциями <= 8000 символов (ограничение JVM на длину строковой константы)
kt_chunks() { base64 -w0 "$1" | python3 -I -c '
import sys
s = sys.stdin.read()
chunks = [s[i:i + 8000] for i in range(0, len(s), 8000)] or [""]
print("listOf(" + ", ".join("\"" + c + "\"" for c in chunks) + ").joinToString(\"\")")'; }

emit_config() { # индекс, пароль, соль или пусто
  local idx="$1" pw="$2" pw2="$3"
  local remote_dir="$TMP/store$idx"
  local obscured spec
  obscured="$(rclone obscure "$pw")"
  spec=":crypt,remote=$remote_dir,password=$obscured"
  if [[ -n "$pw2" ]]; then spec+=",password2=$(rclone obscure "$pw2")"; fi
  spec+=":"

  for name in "${NAMES[@]}"; do
    local enc
    enc="$(rclone cryptdecode --reverse "$spec" "$name" | sed -E 's/^.* \t //')"
    echo "$idx|$(kt_string "$name")|$(kt_string "$enc")" >> "$TMP/names.txt"
  done

  for size in "${SIZES[@]}"; do
    make_plain "$size" "$TMP/plain.bin"
    rclone copyto "$TMP/plain.bin" "${spec}f$size.bin"
    local enc
    enc="$(rclone cryptdecode --reverse "$spec" "f$size.bin" | sed -E 's/^.* \t //')"
    echo "$idx|$size|$(kt_chunks "$remote_dir/$enc")" >> "$TMP/files.txt"
  done
}

: > "$TMP/names.txt"; : > "$TMP/files.txt"
emit_config 0 "$PASSWORD" ""
emit_config 1 "$PASSWORD" "$PASSWORD2"

{
  echo "package dev.mimir.core"
  echo
  echo "// Сгенерировано scripts/gen-rclone-vectors.sh ($(rclone version | head -1)). Не править вручную."
  echo "internal object RcloneVectors {"
  echo "    class Config(val password: String, val password2: String?)"
  echo "    class NameVector(val config: Int, val plain: String, val encrypted: String)"
  echo "    class FileVector(val config: Int, val size: Int, val encryptedBase64: String)"
  echo
  echo "    val configs = listOf("
  echo "        Config($(kt_string "$PASSWORD"), null),"
  echo "        Config($(kt_string "$PASSWORD"), $(kt_string "$PASSWORD2")),"
  echo "    )"
  echo
  echo "    val names = listOf("
  while IFS='|' read -r idx plain enc; do echo "        NameVector($idx, $plain, $enc),"; done < "$TMP/names.txt"
  echo "    )"
  echo
  echo "    val files = listOf("
  while IFS='|' read -r idx size b64; do echo "        FileVector($idx, $size, $b64),"; done < "$TMP/files.txt"
  echo "    )"
  echo "}"
} > "$OUT"

echo "$OUT"
