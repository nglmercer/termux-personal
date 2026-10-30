#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

/* Walk each component relative to an open directory; never follow a link. */
static int open_document(const char *root, char *path, int access, int truncate_file, int append) {
    int dir = open(root, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    if (dir < 0) return -1;
    int result = -1;
    char *part = path;
    while (*part) {
        char *slash = strchr(part, '/');
        if (slash) *slash = 0;
        if (!*part || !strcmp(part, ".") || !strcmp(part, "..")) break;
        if (slash) {
            int next = openat(dir, part, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
            if (next < 0) break;
            close(dir); dir = next; part = slash + 1;
            if (!*part) break;
        } else {
            int flags = access == 0 ? O_RDONLY : access == 1 ? O_WRONLY : O_RDWR;
            int fd = openat(dir, part, flags | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK | (append ? O_APPEND : 0));
            if (fd >= 0) {
                struct stat st;
                if (fstat(fd, &st) == 0 && S_ISREG(st.st_mode) &&
                    (!truncate_file || ftruncate(fd, 0) == 0)) result = fd;
                else close(fd);
            }
            break;
        }
    }
    close(dir); return result;
}

/* Return 0 for creation, 1 for an existing name, and -1 for all other errors. */
static int create_document(const char *root, char *path, int directory) {
    int dir = open(root, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    if (dir < 0) return -1;
    int result = -1;
    char *part = path;
    while (*part) {
        char *slash = strchr(part, '/');
        if (slash) *slash = 0;
        if (!*part || !strcmp(part, ".") || !strcmp(part, "..")) break;
        if (slash) {
            int next = openat(dir, part, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
            if (next < 0) break;
            close(dir); dir = next; part = slash + 1;
        } else {
            int rc;
            if (directory) rc = mkdirat(dir, part, 0777);
            else {
                int fd = openat(dir, part, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, 0666);
                rc = fd < 0 ? -1 : 0;
                if (fd >= 0) close(fd);
            }
            result = rc == 0 ? 0 : errno == EEXIST ? 1 : -1;
            break;
        }
    }
    close(dir); return result;
}

static int delete_document(const char *root, char *path) {
    int dir = open(root, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    if (dir < 0) return -1;
    int result = -1;
    char *part = path;
    while (*part) {
        char *slash = strchr(part, '/');
        if (slash) *slash = 0;
        if (!*part || !strcmp(part, ".") || !strcmp(part, "..")) break;
        if (slash) {
            int next = openat(dir, part, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
            if (next < 0) break;
            close(dir); dir = next; part = slash + 1;
        } else {
            struct stat st;
            if (fstatat(dir, part, &st, AT_SYMLINK_NOFOLLOW) == 0 &&
                (S_ISREG(st.st_mode) || S_ISDIR(st.st_mode)))
                result = unlinkat(dir, part, S_ISDIR(st.st_mode) ? AT_REMOVEDIR : 0);
            break;
        }
    }
    close(dir); return result;
}

static int open_directory(const char *root, char *path) {
    int dir = open(root, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    if (dir < 0) return -1;
    char *part = path;
    while (*part) {
        char *slash = strchr(part, '/');
        if (slash) *slash = 0;
        if (!*part || !strcmp(part, ".") || !strcmp(part, "..")) { close(dir); return -1; }
        int next = openat(dir, part, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
        close(dir);
        if (next < 0) return -1;
        dir = next;
        if (!slash) return dir;
        part = slash + 1;
        if (!*part) { close(dir); return -1; }
    }
    return dir;
}

JNIEXPORT jint JNICALL
Java_com_termux_filepicker_SafeDocumentOpen_directoryNative(JNIEnv *env, jclass cls,
                                                             jbyteArray root_bytes, jbyteArray relative_bytes) {
    (void) cls;
    jsize root_len = (*env)->GetArrayLength(env, root_bytes);
    jsize path_len = (*env)->GetArrayLength(env, relative_bytes);
    if (root_len <= 0 || path_len < 0 || path_len > 4096) return -1;
    char *root = malloc((size_t) root_len + 1), *path = malloc((size_t) path_len + 1);
    if (!root || !path) { free(root); free(path); return -1; }
    (*env)->GetByteArrayRegion(env, root_bytes, 0, root_len, (jbyte *) root);
    (*env)->GetByteArrayRegion(env, relative_bytes, 0, path_len, (jbyte *) path);
    root[root_len] = 0; path[path_len] = 0;
    int result = -1;
    if (!(*env)->ExceptionCheck(env) && !memchr(root, 0, (size_t) root_len) && !memchr(path, 0, (size_t) path_len))
        result = open_directory(root, path);
    free(root); free(path); return result;
}

JNIEXPORT jint JNICALL
Java_com_termux_filepicker_SafeDocumentOpen_deleteNative(JNIEnv *env, jclass cls,
                                                          jbyteArray root_bytes, jbyteArray relative_bytes) {
    (void) cls;
    jsize root_len = (*env)->GetArrayLength(env, root_bytes);
    jsize path_len = (*env)->GetArrayLength(env, relative_bytes);
    if (root_len <= 0 || path_len <= 0 || path_len > 4096) return -1;
    char *root = malloc((size_t) root_len + 1), *path = malloc((size_t) path_len + 1);
    if (!root || !path) { free(root); free(path); return -1; }
    (*env)->GetByteArrayRegion(env, root_bytes, 0, root_len, (jbyte *) root);
    (*env)->GetByteArrayRegion(env, relative_bytes, 0, path_len, (jbyte *) path);
    root[root_len] = 0; path[path_len] = 0;
    int result = -1;
    if (!(*env)->ExceptionCheck(env) && !memchr(root, 0, (size_t) root_len) && !memchr(path, 0, (size_t) path_len))
        result = delete_document(root, path);
    free(root); free(path); return result;
}

JNIEXPORT jint JNICALL
Java_com_termux_filepicker_SafeDocumentOpen_createNative(JNIEnv *env, jclass cls,
                                                          jbyteArray root_bytes, jbyteArray relative_bytes,
                                                          jboolean directory) {
    (void) cls;
    jsize root_len = (*env)->GetArrayLength(env, root_bytes);
    jsize path_len = (*env)->GetArrayLength(env, relative_bytes);
    if (root_len <= 0 || path_len <= 0 || path_len > 4096) return -1;
    char *root = malloc((size_t) root_len + 1), *path = malloc((size_t) path_len + 1);
    if (!root || !path) { free(root); free(path); return -1; }
    (*env)->GetByteArrayRegion(env, root_bytes, 0, root_len, (jbyte *) root);
    (*env)->GetByteArrayRegion(env, relative_bytes, 0, path_len, (jbyte *) path);
    root[root_len] = 0; path[path_len] = 0;
    int result = -1;
    if (!(*env)->ExceptionCheck(env) && !memchr(root, 0, (size_t) root_len) && !memchr(path, 0, (size_t) path_len))
        result = create_document(root, path, directory);
    free(root); free(path); return result;
}

JNIEXPORT jint JNICALL
Java_com_termux_filepicker_SafeDocumentOpen_openNative(JNIEnv *env, jclass cls,
                                                        jbyteArray root_bytes, jbyteArray relative_bytes,
                                                        jint access, jboolean truncate_file, jboolean append) {
    (void) cls;
    jsize root_len = (*env)->GetArrayLength(env, root_bytes);
    jsize path_len = (*env)->GetArrayLength(env, relative_bytes);
    if (root_len <= 0 || path_len <= 0 || path_len > 4096) return -1;
    char *root = malloc((size_t) root_len + 1), *path = malloc((size_t) path_len + 1);
    if (!root || !path) { free(root); free(path); return -1; }
    (*env)->GetByteArrayRegion(env, root_bytes, 0, root_len, (jbyte *) root);
    (*env)->GetByteArrayRegion(env, relative_bytes, 0, path_len, (jbyte *) path);
    root[root_len] = 0; path[path_len] = 0;
    int result = -1;
    if (!(*env)->ExceptionCheck(env) && !memchr(root, 0, (size_t) root_len) && !memchr(path, 0, (size_t) path_len))
        result = open_document(root, path, access, truncate_file, append);
    free(root); free(path); return result;
}

/* Opens the directory that holds the last component of path, with no link followed, and points
 * *leaf at that component. Returns the directory descriptor, or -1 for an invalid or missing parent. */
static int open_parent(const char *root, char *path, char **leaf) {
    char *slash = strrchr(path, '/');
    char *name = slash ? slash + 1 : path;
    if (!*name || !strcmp(name, ".") || !strcmp(name, "..")) return -1;
    int dir;
    if (slash) {
        *slash = 0;
        dir = open_directory(root, path);
    } else {
        dir = open(root, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    }
    if (dir < 0) return -1;
    *leaf = name;
    return dir;
}

/* New regular file, read/write, owner-only; fails if any entry already has the name. */
static int create_new_file(const char *root, char *path) {
    char *leaf;
    int dir = open_parent(root, path, &leaf);
    if (dir < 0) return -ENOENT;
    int fd = openat(dir, leaf, O_RDWR | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, 0600);
    int saved = errno;
    close(dir);
    return fd >= 0 ? fd : -saved;
}

/* Renames within root without following either final component. Without replace, a regular file is
 * moved with link then unlink, which fails atomically on an existing target; a directory is checked
 * first and then renamed, which a concurrent same-UID creation can still race. */
static int rename_entry(const char *root, char *from, char *to, int replace) {
    char *from_leaf, *to_leaf;
    int from_dir = open_parent(root, from, &from_leaf);
    if (from_dir < 0) return -ENOENT;
    int to_dir = open_parent(root, to, &to_leaf);
    if (to_dir < 0) { close(from_dir); return -ENOENT; }
    struct stat st;
    int result = 0;
    if (fstatat(from_dir, from_leaf, &st, AT_SYMLINK_NOFOLLOW) < 0) result = -errno;
    else if (replace) { if (renameat(from_dir, from_leaf, to_dir, to_leaf) < 0) result = -errno; }
    else if (S_ISREG(st.st_mode)) {
        if (linkat(from_dir, from_leaf, to_dir, to_leaf, 0) < 0) result = -errno;
        else if (unlinkat(from_dir, from_leaf, 0) < 0) result = -errno;
    } else if (fstatat(to_dir, to_leaf, &st, AT_SYMLINK_NOFOLLOW) == 0) result = -EEXIST;
    else if (errno != ENOENT) result = -errno;
    else if (renameat(from_dir, from_leaf, to_dir, to_leaf) < 0) result = -errno;
    close(from_dir); close(to_dir);
    return result;
}

static char *copy_bytes(JNIEnv *env, jbyteArray bytes, jsize max) {
    if (bytes == NULL) return NULL;
    jsize length = (*env)->GetArrayLength(env, bytes);
    if (length <= 0 || length > max) return NULL;
    char *copy = malloc((size_t) length + 1);
    if (!copy) return NULL;
    (*env)->GetByteArrayRegion(env, bytes, 0, length, (jbyte *) copy);
    copy[length] = 0;
    if ((*env)->ExceptionCheck(env) || memchr(copy, 0, (size_t) length)) { free(copy); return NULL; }
    return copy;
}

/* Returns a descriptor or a negative errno. */
JNIEXPORT jint JNICALL
Java_com_termux_filepicker_SafeDocumentOpen_createFileNative(JNIEnv *env, jclass cls, jbyteArray root_bytes,
                                                              jbyteArray relative_bytes) {
    (void) cls;
    char *root = copy_bytes(env, root_bytes, 4096), *path = copy_bytes(env, relative_bytes, 4096);
    int result = root && path ? create_new_file(root, path) : -EINVAL;
    free(root); free(path); return result;
}

/* Returns 0 or a negative errno. */
JNIEXPORT jint JNICALL
Java_com_termux_filepicker_SafeDocumentOpen_renameNative(JNIEnv *env, jclass cls, jbyteArray root_bytes,
                                                          jbyteArray from_bytes, jbyteArray to_bytes, jboolean replace) {
    (void) cls;
    char *root = copy_bytes(env, root_bytes, 4096), *from = copy_bytes(env, from_bytes, 4096), *to = copy_bytes(env, to_bytes, 4096);
    int result = root && from && to ? rename_entry(root, from, to, replace) : -EINVAL;
    free(root); free(from); free(to); return result;
}
