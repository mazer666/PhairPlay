# zip-align.awk — read the ZIP structures that decide the APK alignment.
#
# WHY: Google Play requires the native libraries inside an APK to be stored
#      uncompressed and aligned to a 16 KB boundary in the ZIP container.
#      The reference tool for this is "zipalign -c -P 16 -v 4", which only
#      exists in the Android build tools. The owner runs the checks on a Mac
#      without the Android SDK, so this helper reads the ZIP structures
#      directly and reproduces the two properties that matter:
#
#        * every entry starts at a 4-byte boundary  (zipalign -v 4)
#        * every lib/**.so entry is stored uncompressed and starts at a
#          16 KB boundary                             (zipalign -P 16)
#
# USAGE
#   The byte stream is fed in as unsigned decimal values, one per field,
#   exactly as "od -An -tu1 -v" prints it.
#
#   mode=eocd     input: the last bytes of the archive
#                 output: "<cd-offset> <cd-size> <entry-count>"
#
#   mode=central  input: a window of the archive that contains the central
#                 directory
#                 output: one line per entry:
#                        "<method>\t<local-header-offset>\t<name>"
#
#   mode=local    input: a window of the archive that contains a local file
#                 header
#                 output: "<name-length>\t<extra-length>"
#
#   Every mode takes an extra variable "offset": the byte inside the input
#   window at which the structure starts. The shell reads the window with dd
#   on a block boundary, so the offset is the remainder of the division.
#
# EXIT CODES
#   0  the requested information was printed
#   1  the archive is malformed, truncated or uses ZIP64
#
# NOTE ON PRECISION
#   awk uses double precision numbers. Every value read here is far below
#   2^53, so the arithmetic is exact.
#

function u16(a, i) {
  return a[i] + a[i + 1] * 256
}

function u32(a, i) {
  return a[i] + a[i + 1] * 256 + a[i + 2] * 65536 + a[i + 3] * 16777216
}

function fail(message) {
  print "ERROR: " message > "/dev/stderr"
  exit 1
}

{
  for (i = 1; i <= NF; i++) {
    if ($i != "") {
      n++
      b[n] = $i + 0
    }
  }
}

END {
  if (n == 0) fail("no input bytes")

  if (mode == "eocd") {
    # The end of central directory record is 22 bytes plus an optional
    # comment of up to 65535 bytes, so it is always inside the last 65557
    # bytes of the archive. Scan backwards for its signature.
    found = 0
    for (pos = n - 21; pos >= 1; pos--) {
      if (b[pos] == 0x50 && b[pos + 1] == 0x4b && b[pos + 2] == 0x05 && b[pos + 3] == 0x06) {
        found = 1
        break
      }
    }
    if (!found) fail("no end of central directory record found")
    entries = u16(b, pos + 10)
    cd_size = u32(b, pos + 12)
    cd_offset = u32(b, pos + 16)
    if (cd_size == 4294967295 || cd_offset == 4294967295) {
      fail("ZIP64 archive — the built-in alignment check does not support ZIP64")
    }
    print cd_offset "\t" cd_size "\t" entries
    exit 0
  }

  if (mode == "central") {
    pos = offset + 1
    while (pos + 45 <= n) {
      if (b[pos] != 0x50 || b[pos + 1] != 0x4b || b[pos + 2] != 0x01 || b[pos + 3] != 0x02) {
        fail("bad central directory signature at byte " (pos - 1))
      }
      method = u16(b, pos + 10)
      name_len = u16(b, pos + 28)
      extra_len = u16(b, pos + 30)
      comment_len = u16(b, pos + 32)
      local_offset = u32(b, pos + 42)
      if (local_offset == 4294967295) {
        fail("ZIP64 archive — the built-in alignment check does not support ZIP64")
      }
      name = ""
      for (i = 0; i < name_len; i++) {
        c = b[pos + 46 + i]
        name = name sprintf("%c", c)
      }
      print method "\t" local_offset "\t" name
      pos = pos + 46 + name_len + extra_len + comment_len
    }
    exit 0
  }

  if (mode == "local") {
    base = offset + 1
    if (base + 29 > n) fail("local file header is shorter than 30 bytes")
    if (b[base] != 0x50 || b[base + 1] != 0x4b || b[base + 2] != 0x03 || b[base + 3] != 0x04) {
      fail("bad local file header signature")
    }
    print u16(b, base + 26) "\t" u16(b, base + 28)
    exit 0
  }

  fail("unknown mode \"" mode "\"")
}
