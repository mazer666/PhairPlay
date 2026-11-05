# make-elf.awk — write a minimal ELF file with one PT_LOAD segment.
#
# WHY: the self-test of scripts/check-native-libs.sh needs a native library
#      with a *known* alignment. A real library from the build output only
#      exists after a full native build, so the test cannot depend on it.
#      This generator writes the smallest ELF file that carries the field the
#      gate reads — the p_align of a PT_LOAD segment — so the test can prove
#      that a wrongly aligned library turns the gate red.
#
# USAGE
#   awk -f make-elf.awk -v class=2 -v align=16384 > library.so
#
#   class   1 = 32-bit ELF, 2 = 64-bit ELF (default: 2)
#   align   the p_align of the PT_LOAD segment (default: 16384)
#
#   The bytes go to stdout, so the caller redirects them into a file.
#
# EXIT CODES
#   0  the file was written
#   1  class is neither 1 nor 2
#

function put(value) {
  printf "%c", value
}

function put16(value) {
  put(value % 256)
  put(int(value / 256) % 256)
}

function put32(value,   i) {
  for (i = 0; i < 4; i++) {
    put(int(value / (2 ^ (8 * i))) % 256)
  }
}

function put64(value,   i) {
  for (i = 0; i < 8; i++) {
    put(int(value / (2 ^ (8 * i))) % 256)
  }
}

BEGIN {
  if (class != 1 && class != 2) {
    print "ERROR: class must be 1 or 2" > "/dev/stderr"
    exit 1
  }

  # ── the ELF header ────────────────────────────────────────────────────────
  put(127); put(69); put(76); put(70)      # 0x7f "ELF"
  put(class)                               # EI_CLASS
  put(1)                                   # EI_DATA: little endian
  put(1)                                   # EI_VERSION
  put(0)                                   # EI_OSABI
  for (i = 0; i < 8; i++) {
    put(0)                                 # padding
  }
  put16(3)                                 # e_type = ET_DYN
  if (class == 2) {
    put16(183)                             # e_machine = EM_AARCH64
  } else {
    put16(3)                               # e_machine = EM_386
  }

  if (class == 2) {
    put32(1)                               # e_version
    put64(0)                               # e_entry
    put64(64)                              # e_phoff
    put64(0)                               # e_shoff
    put32(0)                               # e_flags
    put16(64)                              # e_ehsize
    put16(56)                              # e_phentsize
    put16(1)                               # e_phnum
    put16(0)                               # e_shentsize
    put16(0)                               # e_shnum
    put16(0)                               # e_shstrndx
  } else {
    put32(1)                               # e_version
    put32(0)                               # e_entry
    put32(52)                              # e_phoff
    put32(0)                               # e_shoff
    put32(0)                               # e_flags
    put16(52)                              # e_ehsize
    put16(32)                              # e_phentsize
    put16(1)                               # e_phnum
    put16(0)                               # e_shentsize
    put16(0)                               # e_shnum
    put16(0)                               # e_shstrndx
  }

  # ── one program header: PT_LOAD ───────────────────────────────────────────
  put32(1)                                 # p_type = PT_LOAD
  if (class == 2) {
    put32(5)                               # p_flags = R+X
    put64(0)                               # p_offset
    put64(0)                               # p_vaddr
    put64(0)                               # p_paddr
    put64(4096)                           # p_filesz
    put64(4096)                           # p_memsz
    put64(align)                          # p_align
  } else {
    put32(0)                               # p_offset
    put32(0)                               # p_vaddr
    put32(0)                               # p_paddr
    put32(4096)                           # p_filesz
    put32(4096)                           # p_memsz
    put32(5)                               # p_flags = R+X
    put32(align)                          # p_align
  }
}
