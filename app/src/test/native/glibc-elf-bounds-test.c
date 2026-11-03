/* Exercise the actual shim parser with malformed copies of a host ELF64 binary. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "../../main/cpp/glibc-exec.c"

static unsigned get16(const unsigned char *p) { return p[0] | (unsigned) p[1] << 8; }
static unsigned get32(const unsigned char *p) {
    return p[0] | (unsigned) p[1] << 8 | (unsigned) p[2] << 16 | (unsigned) p[3] << 24;
}
static void write_fixture(const char *path, const unsigned char *data, size_t n) {
    FILE *f = fopen(path, "wb");
    if (!f || fwrite(data, 1, n, f) != n || fclose(f)) { perror("fixture"); exit(1); }
}
int main(int argc, char **argv) {
    if (argc != 3) return 1;
    unsigned char original[4096], modified[4096];
    FILE *f = fopen(argv[1], "rb");
    if (!f) return 1;
    size_t n = fread(original, 1, sizeof(original), f);
    fclose(f);
    if (n < 64) return 1;
    if (original[4] != 2 || original[5] != 1) {
        puts("ELF64 bounds checks skipped: host fixture is not little-endian ELF64"); return 0;
    }
    unsigned phoff = get32(original + 32), phsize = get16(original + 54), count = get16(original + 56);
    unsigned interp = 0;
    for (unsigned i = 0; i < count && phoff + (i + 1) * phsize <= n; i++) {
        unsigned ph = phoff + i * phsize;
        if (get32(original + ph) == 3) { interp = ph; break; }
    }
    if (!interp) return 1;
    char loader[MAX_PATH];
    write_fixture(argv[2], original, n);
    if (!glibc_loader_for(argv[2], loader)) { fprintf(stderr, "valid ELF not recognized\n"); return 1; }
    /* High halves of e_phoff, p_offset and p_filesz must never be truncated. */
    unsigned offsets[] = {36, interp + 12, interp + 36};
    for (unsigned i = 0; i < sizeof(offsets) / sizeof(offsets[0]); i++) {
        memcpy(modified, original, n);
        modified[offsets[i]] = 1;
        write_fixture(argv[2], modified, n);
        if (glibc_loader_for(argv[2], loader)) { fprintf(stderr, "accepted high ELF64 bits at %u\n", offsets[i]); return 1; }
    }
    /* An offset near UINT32_MAX must not wrap when compiled for a 32-bit ABI. */
    memcpy(modified, original, n);
    memset(modified + interp + 8, 0xff, 4);
    write_fixture(argv[2], modified, n);
    if (glibc_loader_for(argv[2], loader)) { fprintf(stderr, "accepted overflowing interpreter offset\n"); return 1; }
    puts("glibc ELF bounds checks passed");
    return 0;
}
