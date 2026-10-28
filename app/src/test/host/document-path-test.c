#define _GNU_SOURCE
#include "../../main/cpp/document-path.c"
#include <assert.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>

static int try_open(const char *root, const char *name, int access, int truncate_file) {
    char path[4097];
    assert(strlen(name) < sizeof(path));
    strcpy(path, name);
    return open_document(root, path, access, truncate_file, 0);
}

static int try_create(const char *root, const char *name, int directory) {
    char path[4097]; assert(strlen(name) < sizeof(path)); strcpy(path, name);
    return create_document(root, path, directory);
}
static int try_delete(const char *root, const char *name) {
    char path[4097]; assert(strlen(name) < sizeof(path)); strcpy(path, name);
    return delete_document(root, path);
}

struct swap_state { const char *dir; const char *held; atomic_int stop; };
static void *swap_directory(void *arg) {
    struct swap_state *state = arg;
    while (!atomic_load(&state->stop)) {
        assert(rename(state->dir, state->held) == 0);
        assert(symlink("..", state->dir) == 0);
        assert(unlink(state->dir) == 0);
        assert(rename(state->held, state->dir) == 0);
    }
    return NULL;
}

int main(void) {
    char base[512];
    const char *tmp = getenv("TMPDIR");
    if (!tmp || !*tmp) tmp = "/tmp";
    assert(snprintf(base, sizeof(base), "%s/termux-doc-test-XXXXXX", tmp) < (int) sizeof(base));
    assert(mkdtemp(base));
    char root[1024], outside[1024], file[1024], link[1024], dir[1024], held[1024], inner[1024], created[1024], escaped[1024];
    snprintf(root, sizeof(root), "%s/root", base);
    snprintf(outside, sizeof(outside), "%s/secret", base);
    snprintf(dir, sizeof(dir), "%s/root/dir", base);
    snprintf(file, sizeof(file), "%s/root/dir/file", base);
    snprintf(link, sizeof(link), "%s/root/link", base);
    snprintf(held, sizeof(held), "%s/root/held", base);
    snprintf(inner, sizeof(inner), "%s/root/dir/secret", base);
    snprintf(created, sizeof(created), "%s/root/dir/created", base);
    snprintf(escaped, sizeof(escaped), "%s/created", base);
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
    assert(try_create(root, "link/created", 0) < 0);
    assert(try_create(root, "../created", 0) < 0);
    assert(try_create(root, "dir/newdir", 1) == 0);
    assert(try_create(root, "dir/newdir", 1) == 1);
    assert(try_delete(root, "link/secret") < 0);
    assert(try_delete(root, "../secret") < 0);
    assert(try_delete(root, "link") < 0);
    fd = open(inner, O_CREAT | O_WRONLY | O_EXCL, 0600); assert(fd >= 0); close(fd);
    struct swap_state state = {dir, held, ATOMIC_VAR_INIT(0)};
    pthread_t thread; assert(pthread_create(&thread, NULL, swap_directory, &state) == 0);
    for (int i = 0; i < 20000; i++) {
        fd = try_open(root, "dir/secret", 1, 1);
        if (fd >= 0) close(fd);
        int create_result = try_create(root, "dir/created", 0);
        assert(create_result == -1 || create_result == 0 || create_result == 1);
        int delete_result = try_delete(root, "dir/secret");
        assert(delete_result == -1 || delete_result == 0);
    }
    atomic_store(&state.stop, 1);
    assert(pthread_join(thread, NULL) == 0);
    assert(access(escaped, F_OK) != 0);
    struct stat st; assert(!stat(outside, &st) && st.st_size == 4);
    fd = try_open(root, "dir/file", 2, 1); assert(fd >= 0); close(fd);
    assert(!stat(file, &st) && st.st_size == 0);
    if (access(created, F_OK) == 0) assert(!unlink(created));
    if (access(inner, F_OK) == 0) assert(!unlink(inner));
    assert(try_delete(root, "dir/newdir") == 0);
    assert(!unlink(link) && !unlink(file) && !rmdir(dir) && !rmdir(root) && !unlink(outside) && !rmdir(base));
    puts("descriptor-relative document open passed");
    return 0;
}
