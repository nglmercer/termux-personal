#ifndef TERMUX_MANAGED_PATH_H
#define TERMUX_MANAGED_PATH_H

enum termux_managed_path_kind {
    TERMUX_MANAGED_DIRECTORY,
    TERMUX_MANAGED_EXECUTABLE
};

/*
 * Open a root-relative path without following any symlink. root_fd must already
 * be an authorized, owned directory descriptor. An empty path selects the root
 * only for DIRECTORY. The returned CLOEXEC descriptor belongs to the caller.
 * Returns -1 and sets errno on invalid paths, denied traversal, or I/O failure.
 * This pins opened inodes, but is not a complete containment guarantee against
 * concurrent directory renames, hard links, mount changes, or mutation of file
 * contents. The caller must establish root ownership and an appropriate policy.
 */
int termux_managed_open_relative(int root_fd, const char *path, enum termux_managed_path_kind kind);

#endif
