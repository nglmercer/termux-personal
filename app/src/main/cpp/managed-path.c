#define _GNU_SOURCE
#include "managed-path.h"

#include <errno.h>
#include <fcntl.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#define MANAGED_PATH_MAX 4096

int termux_managed_open_relative(int root_fd, const char *path, enum termux_managed_path_kind kind) {
    if (root_fd < 0 || path == NULL || (kind != TERMUX_MANAGED_DIRECTORY && kind != TERMUX_MANAGED_EXECUTABLE)) {
        errno = EINVAL; return -1;
    }
    size_t length = strnlen(path, MANAGED_PATH_MAX + 1);
    if (length > MANAGED_PATH_MAX || (length == 0 && kind != TERMUX_MANAGED_DIRECTORY) ||
        (length && (path[0] == '/' || path[length - 1] == '/'))) {
        errno = EINVAL; return -1;
    }
    int dir = fcntl(root_fd, F_DUPFD_CLOEXEC, 3);
    if (dir < 0) return -1;
    struct stat root_stat;
    if (fstat(dir, &root_stat) < 0) { int error = errno; close(dir); errno = error; return -1; }
    if (!S_ISDIR(root_stat.st_mode)) { close(dir); errno = ENOTDIR; return -1; }
    if (!length) return dir;

    char copy[MANAGED_PATH_MAX + 1];
    memcpy(copy, path, length + 1);
    char *part = copy;
    for (;;) {
        char *slash = strchr(part, '/');
        if (slash) *slash = '\0';
        if (!*part || !strcmp(part, ".") || !strcmp(part, "..")) { close(dir); errno = EINVAL; return -1; }
        int directory = slash != NULL || kind == TERMUX_MANAGED_DIRECTORY;
        // O_PATH avoids opening a device or FIFO before its type can be checked.
        int flags = O_CLOEXEC | O_NOFOLLOW | (directory ? O_RDONLY | O_DIRECTORY : O_PATH);
        int next = openat(dir, part, flags);
        int error = errno;
        close(dir);
        if (next < 0) { errno = error; return -1; }
        dir = next;
        if (!slash) break;
        part = slash + 1;
    }
    if (kind == TERMUX_MANAGED_EXECUTABLE) {
        struct stat st;
        if (fstat(dir, &st) < 0) { int error = errno; close(dir); errno = error; return -1; }
        if (!S_ISREG(st.st_mode)) { close(dir); errno = EACCES; return -1; }
    }
    return dir;
}
