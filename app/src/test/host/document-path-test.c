#define _GNU_SOURCE
#include "../../main/cpp/document-path.c"
#include <assert.h>
#include <stdio.h>

static int try_open(const char *root, const char *name, int access, int truncate_file) {
    char path[4097];
    assert(strlen(name) < sizeof(path));
    strcpy(path, name);
    return open_document(root, path, access, truncate_file, 0);
}

int main(void) {
    char base[512];
    const char *tmp = getenv("TMPDIR");
    if (!tmp || !*tmp) tmp = "/tmp";
    assert(snprintf(base, sizeof(base), "%s/termux-doc-test-XXXXXX", tmp) < (int) sizeof(base));
    assert(mkdtemp(base));
    char root[1024], outside[1024], file[1024], link[1024], dir[1024];
    snprintf(root, sizeof(root), "%s/root", base);
    snprintf(outside, sizeof(outside), "%s/secret", base);
    snprintf(dir, sizeof(dir), "%s/root/dir", base);
    snprintf(file, sizeof(file), "%s/root/dir/file", base);
    snprintf(link, sizeof(link), "%s/root/link", base);
    assert(!mkdir(root, 0700) && !mkdir(dir, 0700));
    int fd = open(outside, O_CREAT | O_WRONLY | O_EXCL, 0600);
    assert(fd >= 0 && write(fd, "safe", 4) == 4); close(fd);
    fd = open(file, O_CREAT | O_WRONLY | O_EXCL, 0600);
    assert(fd >= 0 && write(fd, "abc", 3) == 3); close(fd);
    assert(!symlink("..", link));
    fd = try_open(root, "dir/file", 0, 0); assert(fd >= 0); close(fd);
    assert(try_open(root, "link/secret", 1, 1) < 0);
    assert(try_open(root, "../secret", 1, 1) < 0);
    assert(try_open(root, "dir//file", 0, 0) < 0);
    assert(try_open(root, "dir", 0, 0) < 0);
    struct stat st; assert(!stat(outside, &st) && st.st_size == 4);
    fd = try_open(root, "dir/file", 2, 1); assert(fd >= 0); close(fd);
    assert(!stat(file, &st) && st.st_size == 0);
    assert(!unlink(link) && !unlink(file) && !rmdir(dir) && !rmdir(root) && !unlink(outside) && !rmdir(base));
    puts("descriptor-relative document open passed");
    return 0;
}
