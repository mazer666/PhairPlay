# elf-load-align.awk — report the alignment of the PT_LOAD segments of an ELF file.
#
# WHY: Google Play requires every 64-bit native library in an APK/AAB to be
#      usable on a device with a 16 KB page size. Whether that works is decided
#      by the p_align field of the LOAD segments in the ELF header — not by the
#      build system and not by the packaging. The check therefore reads the ELF
#      header directly, so it works on every machine that has awk and od:
#      no readelf, no llvm-readelf, no Android NDK, no Python.
#
#      The script must also run on the owner's Mac, where none of those tools
#      are installed by default.
#
# USAGE
#   Phase 1 — the file header (the first 64 bytes):
#     dd if=FILE bs=1 count=64 2>/dev/null | od -An -tu1 -v \
#       | awk -f elf-load-align.awk -v mode=header
#     prints: "<elf-class> <e_phoff> <e_phentsize> <e_phnum>"
#       elf-class   1 = 32-bit ELF, 2 = 64-bit ELF
#
#   Phase 2 — the program header table:
#     dd if=FILE bs=1 skip=<e_phoff> count=<e_phnum * e_phentsize> 2>/dev/null \
#       | od -An -tu1 -v \
#       | awk -f elf-load-align.awk -v mode=segments -v elfclass=<1|2> \
#              -v phentsize=<n> -v phnum=<n>
#     prints one line per PT_LOAD segment: "<hex alignment> <decimal alignment>"
#
#   The input is the byte stream as unsigned decimal values, one per field,
#   exactly as "od -An -tu1 -v" prints it.
#
#   The mode "segments" takes an extra variable "offset": the byte inside the
#   input window at which the program header table starts.
#
# EXIT CODES
#   0  the requested information was printed
#   1  the input is not a usable ELF file (wrong magic, unknown class,
#      truncated program header table)
#
# NOTE ON PRECISION
#   awk uses double precision numbers. Every value read here (offsets, sizes,
#   alignments) is far below 2^53, so the arithmetic is exact.
#

function u16(a, i) {
  return a[i] + a[i + 1] * 256
}

function u32(a, i) {
  return a[i] + a[i + 1] * 256 + a[i + 2] * 65536 + a[i + 3] * 16777216
}

function u64(a, i,   v, m, k) {
  v = 0
  m = 1
  for (k = 0; k < 8; k++) {
    v += a[i + k] * m
    m *= 256
  }
  return v
}

function tohex(v,   s, d, digits) {
  digits = "0123456789abcdef"
  if (v == 0) return "0x0"
  s = ""
  while (v > 0) {
    d = int(v % 16)
    s = substr(digits, d + 1, 1) s
    v = int(v / 16)
  }
  return "0x" s
}

function fail(message) {
  print "ERROR: " message > "/dev/stderr"
  exit 1
}

# Collect the byte stream: one unsigned decimal value per field.
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

  if (mode == "header") {
    if (n < 64) fail("file header is shorter than 64 bytes")
    if (b[1] != 127 || b[2] != 69 || b[3] != 76 || b[4] != 70) {
      fail("not an ELF file (bad magic)")
    }
    cls = b[5]
    if (cls == 2) {
      # ELF64: e_phoff @ 0x20, e_phentsize @ 0x36, e_phnum @ 0x38
      phoff = u64(b, 33)
      phentsize = u16(b, 55)
      phnum = u16(b, 57)
    } else if (cls == 1) {
      # ELF32: e_phoff @ 0x1c, e_phentsize @ 0x2a, e_phnum @ 0x2c
      phoff = u32(b, 29)
      phentsize = u16(b, 43)
      phnum = u16(b, 45)
    } else {
      fail("unsupported ELF class " cls)
    }
    print cls, phoff, phentsize, phnum
    exit 0
  }

  if (mode == "segments") {
    if (elfclass != 1 && elfclass != 2) fail("elfclass must be 1 or 2")
    if (phentsize <= 0 || phnum <= 0) fail("invalid program header layout")
    if (elfclass == 2 && phentsize < 56) fail("ELF64 program header is too small")
    if (elfclass == 1 && phentsize < 32) fail("ELF32 program header is too small")
    for (i = 0; i < phnum; i++) {
      base = offset + 1 + i * phentsize
      if (base + phentsize - 1 > n) fail("truncated program header table")
      if (u32(b, base) != 1) continue      # 1 = PT_LOAD
      if (elfclass == 2) {
        align = u64(b, base + 48)           # p_align is the last field
      } else {
        align = u32(b, base + 28)
      }
      print tohex(align), align
    }
    exit 0
  }

  fail("unknown mode \"" mode "\"")
}
