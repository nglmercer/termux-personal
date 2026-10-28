#include <jni.h>
#include <errno.h>
#include <fcntl.h>
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
