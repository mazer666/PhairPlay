#!/usr/bin/env bash
#
# artifact-readers.sh — the readers that check-native-libs.sh uses to look
# inside an APK, an AAB and an ELF library.
#
# WHY: the readers are separated from the gate because they are the part
#      that has to work on a Mac without the Android SDK: they read the ELF
#      header and the ZIP structures directly, with nothing but awk and od.
#      Keeping them in one file makes them readable and testable on their own.
#
# USAGE
#   . "$(dirname "$0")/lib/artifact-readers.sh"
#
#   list_contains LIST ITEM
#       Does the newline separated LIST contain ITEM?
#
#   abi_of PATH
#       Prints the ABI of a native library path such as
#       "lib/arm64-v8a/libplayfair.so" or
#       "base/lib/arm64-v8a/libplayfair.so".
#
#   read_window FILE OFFSET COUNT
#       Prints COUNT bytes of FILE, starting at OFFSET, on stdout. The
#       window starts on a block boundary, so the byte inside the window at
#       which the data starts is OFFSET % 65536.
#
#   elf_info FILE
#       Prints "<elf-class> <e_phoff> <e_phentsize> <e_phnum>".
#       The elf class is 1 for a 32-bit ELF and 2 for a 64-bit ELF.
#
#   elf_load_alignments FILE INFO
#       Prints "<hex alignment> <decimal alignment>" per PT_LOAD segment.
#
#   zip_entries ZIP
#       Prints "<method> <local-header-offset> <name>" per archive entry.
#
#   zip_local_header ZIP OFFSET
#       Prints "<name-length> <extra-length>" of the local file header at
#       OFFSET.
#
#   find_zipalign
#       Prints the path of the zipalign from the Android build tools, or
#       fails when it cannot be found.
#
# EXIT CODES of the individual readers: 0 on success, 1 when the input cannot
# be read.
#

BLOCK_SIZE=4096

# list_contains LIST ITEM — does the newline separated list contain the item?
list_contains() {
  local list="$1" item="$2" line
  while IFS= read -r line; do
    if [ "$line" = "$item" ]; then
      return 0
    fi
  done <<EOF
$list
EOF
  return 1
}

# abi_of PATH — the ABI directory of a native library path
abi_of() {
  printf '%s\n' "$1" | awk -F/ '
    { for (i = 1; i <= NF; i++) if ($i == "lib" && i + 1 <= NF) { print $(i + 1); exit } }'
}

# read_window FILE OFFSET COUNT — COUNT bytes of FILE from OFFSET on stdout
#
# dd seeks on a block boundary, so the window starts at
# floor(OFFSET / BLOCK_SIZE) * BLOCK_SIZE and the caller has to add the
# remainder to the offset it hands to the reader.
read_window() {
  local file="$1" offset="$2" count="$3" blocks
  blocks=$(( (offset % BLOCK_SIZE + count) / BLOCK_SIZE + 1 ))
  dd if="$file" bs="$BLOCK_SIZE" skip=$((offset / BLOCK_SIZE)) count="$blocks" 2>/dev/null
}

# elf_info FILE — "<elf-class> <e_phoff> <e_phentsize> <e_phnum>"
elf_info() {
  read_window "$1" 0 64 | od -An -tu1 -v | awk -f "$AWK_ELF" -v mode=header
}

# elf_load_alignments FILE INFO — "<hex> <decimal>" per PT_LOAD segment
elf_load_alignments() {
  local file="$1" info="$2"
  local cls phoff phentsize phnum total
  cls="$(printf '%s\n' "$info" | awk '{print $1}')"
  phoff="$(printf '%s\n' "$info" | awk '{print $2}')"
  phentsize="$(printf '%s\n' "$info" | awk '{print $3}')"
  phnum="$(printf '%s\n' "$info" | awk '{print $4}')"
  total=$((phnum * phentsize))
  if [ "$total" -le 0 ]; then
    return 1
  fi
  read_window "$file" "$phoff" "$total" | od -An -tu1 -v \
    | awk -f "$AWK_ELF" -v mode=segments -v elfclass="$cls" \
          -v phentsize="$phentsize" -v phnum="$phnum" \
          -v offset=$((phoff % BLOCK_SIZE))
}

# zip_entries ZIP — "<method> <local-header-offset> <name>" per archive entry
zip_entries() {
  local zip="$1" size tail_len eocd cd_offset cd_size
  size="$(wc -c < "$zip" | tr -d ' ')"
  tail_len=65557
  if [ "$size" -lt "$tail_len" ]; then
    tail_len="$size"
  fi
  eocd="$(tail -c "$tail_len" "$zip" | od -An -tu1 -v \
          | awk -f "$AWK_ZIP" -v mode=eocd)" || return 1
  cd_offset="$(printf '%s\n' "$eocd" | cut -f1)"
  cd_size="$(printf '%s\n' "$eocd" | cut -f2)"
  if [ -z "$cd_offset" ] || [ -z "$cd_size" ]; then
    return 1
  fi
  read_window "$zip" "$cd_offset" "$cd_size" | od -An -tu1 -v \
    | awk -f "$AWK_ZIP" -v mode=central -v offset=$((cd_offset % BLOCK_SIZE))
}

# zip_local_header ZIP OFFSET — "<name-length> <extra-length>"
zip_local_header() {
  local zip="$1" offset="$2"
  read_window "$zip" "$offset" 30 | od -An -tu1 -v \
    | awk -f "$AWK_ZIP" -v mode=local -v offset=$((offset % BLOCK_SIZE))
}

# find_zipalign — the path of the zipalign from the Android build tools
find_zipalign() {
  local sdk version candidate
  if [ -n "${ZIPALIGN:-}" ] && [ -x "${ZIPALIGN}" ]; then
    printf '%s' "$ZIPALIGN"
    return 0
  fi
  if command -v zipalign >/dev/null 2>&1; then
    command -v zipalign
    return 0
  fi
  sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [ -n "$sdk" ] && [ -d "$sdk/build-tools" ]; then
    for version in $(ls -1 "$sdk/build-tools" 2>/dev/null \
                      | sort -t. -k1,1nr -k2,2nr -k3,3nr); do
      candidate="$sdk/build-tools/$version/zipalign"
      if [ -x "$candidate" ]; then
        printf '%s' "$candidate"
        return 0
      fi
    done
  fi
  return 1
}
