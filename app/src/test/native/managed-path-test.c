#define _GNU_SOURCE
#include "managed-path.h"
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static void require(int condition, const char *message) {
    if (!condition) { perror(message); exit(1); }
}
static int descriptor_count(void) {
    DIR *directory = opendir("/proc/self/fd");
    require(directory != NULL, "open descriptor list");
    int count = 0;
    while (readdir(directory) != NULL) count++;
    require(closedir(directory) == 0, "close descriptor list");
    return count;
}
int main(void) {
    char temporary[] = "/tmp/termux-managed-path-XXXXXX";
    require(mkdtemp(temporary) != NULL, "temporary directory");
    int base = open(temporary, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    require(base >= 0 && mkdirat(base, "root", 0700) == 0 && mkdirat(base, "outside", 0700) == 0, "test roots");
    int root = openat(base, "root", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    int outside = openat(base, "outside", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    require(root >= 0 && outside >= 0, "open roots");
    require(mkdirat(root, "project", 0700) == 0, "create project");
    int file = openat(root, "program", O_CREAT | O_WRONLY | O_CLOEXEC, 0700);
    require(file >= 0 && write(file, "owned", 5) == 5 && close(file) == 0, "create executable");
    file = openat(outside, "secret", O_CREAT | O_WRONLY | O_CLOEXEC, 0700);
    require(file >= 0 && write(file, "other", 5) == 5 && close(file) == 0, "create outside file");
    require(symlinkat("../outside", root, "link") == 0 && symlinkat("../outside/secret", root, "file-link") == 0, "create symlinks");
    require(mkfifoat(root, "fifo", 0600) == 0, "create fifo");
    int directory = termux_managed_open_relative(root, "project", TERMUX_MANAGED_DIRECTORY);
    require(directory >= 0 && (fcntl(directory, F_GETFD) & FD_CLOEXEC), "open pinned directory");
    close(directory);
    directory = termux_managed_open_relative(root, "", TERMUX_MANAGED_DIRECTORY);
    require(directory >= 0, "open root directory"); close(directory);
    const char *invalid[] = {"/outside", "../outside", "project/../program", "project//x", "project/", ".", "file-link", "link/secret"};
    for (size_t i = 0; i < sizeof(invalid) / sizeof(invalid[0]); i++) {
        int fd = termux_managed_open_relative(root, invalid[i], TERMUX_MANAGED_EXECUTABLE);
        require(fd == -1, "reject traversal or symlink");
    }
    require(termux_managed_open_relative(root, "", TERMUX_MANAGED_EXECUTABLE) == -1, "reject empty executable");
    require(termux_managed_open_relative(root, "project", TERMUX_MANAGED_EXECUTABLE) == -1, "reject non-regular executable");
    require(termux_managed_open_relative(root, "fifo", TERMUX_MANAGED_EXECUTABLE) == -1, "reject fifo without opening it");
    int before = descriptor_count();
    for (int i = 0; i < 1000; i++) {
        require(termux_managed_open_relative(root, "project/../missing", TERMUX_MANAGED_EXECUTABLE) == -1,
            "reject repeated invalid traversal");
        require(termux_managed_open_relative(root, "link/secret", TERMUX_MANAGED_EXECUTABLE) == -1,
            "reject repeated symlink traversal");
        require(termux_managed_open_relative(root, "missing/entry", TERMUX_MANAGED_EXECUTABLE) == -1,
            "reject repeated missing path");
    }
    require(descriptor_count() == before, "failed traversal leaks no descriptors");
    int pinned = termux_managed_open_relative(root, "program", TERMUX_MANAGED_EXECUTABLE);
    require(pinned >= 0 && (fcntl(pinned, F_GETFD) & FD_CLOEXEC), "open pinned executable");
    struct stat original, after_swap;
    require(fstat(pinned, &original) == 0, "stat pinned executable");
    require(renameat(root, "program", root, "original") == 0 && symlinkat("../outside/secret", root, "program") == 0, "swap name");
    require(fstat(pinned, &after_swap) == 0 && original.st_dev == after_swap.st_dev && original.st_ino == after_swap.st_ino,
        "descriptor retains original inode");
    require(termux_managed_open_relative(root, "program", TERMUX_MANAGED_EXECUTABLE) == -1, "reject swapped symlink");
    close(pinned);
    require(unlinkat(root, "program", 0) == 0 && unlinkat(root, "original", 0) == 0 &&
        unlinkat(root, "file-link", 0) == 0 && unlinkat(root, "link", 0) == 0 && unlinkat(root, "fifo", 0) == 0 &&
        unlinkat(root, "project", AT_REMOVEDIR) == 0 && unlinkat(outside, "secret", 0) == 0, "remove test entries");
    close(root); close(outside);
    require(unlinkat(base, "root", AT_REMOVEDIR) == 0 && unlinkat(base, "outside", AT_REMOVEDIR) == 0, "remove test roots");
    close(base); require(rmdir(temporary) == 0, "remove test directory");
    puts("descriptor-relative managed path traversal and swap checks passed");
    return 0;
}
