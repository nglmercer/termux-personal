#define _GNU_SOURCE
#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <poll.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <termios.h>
#include <time.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/un.h>
#include <unistd.h>

#define MAX_FRAME 65536u
#define MAX_REQUEST 4096u

struct span { const char *start, *end; };
static void ws(const char **p) { while (**p == ' ' || **p == '\n' || **p == '\r' || **p == '\t') ++*p; }
static int strval(const char **p) {
    if (*(*p)++ != '"') return 0;
    while (**p && **p != '"') {
        unsigned char c = (unsigned char)*(*p)++;
        if (c < 0x20) return 0;
        if (c == '\\') {
            char e = *(*p)++;
            if (!e) return 0;
            if (e == 'u') { for (int i = 0; i < 4; ++i) if (!isxdigit((unsigned char)*(*p)++)) return 0; }
            else if (!strchr("\"\\/bfnrt", e)) return 0;
        }
    }
    if (**p != '"') return 0;
    ++*p; return 1;
}
static int value(const char **p, int depth) {
    if (depth > 16) return 0;
    ws(p);
    if (**p == '"') return strval(p);
    if (**p == '{' || **p == '[') {
        int object = **p == '{'; char end = object ? '}' : ']'; ++*p; ws(p);
        if (**p == end) { ++*p; return 1; }
        for (;;) {
            if (object) { if (**p != '"' || !strval(p)) return 0; ws(p); if (*(*p)++ != ':') return 0; }
            if (!value(p, depth + 1)) return 0;
            ws(p); if (**p == end) { ++*p; return 1; }
            if (*(*p)++ != ',') return 0;
            ws(p);
        }
    }
    const char *s = *p;
    if (!strncmp(s, "true", 4)) { *p += 4; return 1; }
    if (!strncmp(s, "false", 5)) { *p += 5; return 1; }
    if (!strncmp(s, "null", 4)) { *p += 4; return 1; }
    if (**p == '-') ++*p;
    if (**p == '0') ++*p;
    else { if (**p < '1' || **p > '9') return 0; while (isdigit((unsigned char)**p)) ++*p; }
    if (**p == '.') { ++*p; if (!isdigit((unsigned char)**p)) return 0; while (isdigit((unsigned char)**p)) ++*p; }
    if (**p == 'e' || **p == 'E') { ++*p; if (**p == '+' || **p == '-') ++*p; if (!isdigit((unsigned char)**p)) return 0; while (isdigit((unsigned char)**p)) ++*p; }
    return *p != s;
}
/* Find an unescaped ASCII member name in a fully validated object; reject duplicates. */
static int member(struct span object, const char *name, struct span *out) {
    const char *p = object.start; int found = 0;
    ws(&p); if (*p++ != '{') return -1; ws(&p);
    if (*p == '}') return 0;
    for (;;) {
        const char *key = p;
        if (*p != '"' || !strval(&p)) return -1;
        size_t keylen = (size_t)(p - key - 2); ws(&p); if (*p++ != ':') return -1; ws(&p);
        const char *start = p; if (!value(&p, 1)) return -1;
        if (strlen(name) == keylen && !memcmp(key + 1, name, keylen)) {
            if (found) return -1;
            found = 1; out->start = start; out->end = p;
        }
        ws(&p); if (*p == '}') { ++p; ws(&p); return p == object.end ? found : -1; }
        if (*p++ != ',') return -1;
        ws(&p);
    }
}
static int equals(struct span s, const char *v) { return (size_t)(s.end - s.start) == strlen(v) && !memcmp(s.start, v, strlen(v)); }
static int response_ok(char *json, int id, int hello) {
    const char *p = json; struct span root = {json, NULL}, version, rid, result, error;
    if (!value(&p, 0)) return -1;
    ws(&p); if (*p) return -1; root.end = p;
    int r = member(root, "result", &result), e = member(root, "error", &error);
    if (member(root, "jsonrpc", &version) != 1 || !equals(version, "\"2.0\"") ||
        member(root, "id", &rid) != 1 || (r == 1) == (e == 1) || r < 0 || e < 0) return -1;
    char expected[16]; snprintf(expected, sizeof(expected), "%d", id);
    if (!equals(rid, expected)) return -1;
    if (e == 1) return 0;
    if (hello) { struct span v; if (member(result, "version", &v) != 1 || !equals(v, "1")) return -1; }
    return 1;
}
static long long now_ms(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts)) return -1;
    return (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}
static int ready(int fd, short events, long long deadline) {
    for (;;) {
        long long now = now_ms();
        if (now < 0 || now >= deadline) { errno = ETIMEDOUT; return -1; }
        int remaining = (int)(deadline - now);
        struct pollfd pfd = { .fd = fd, .events = events };
        int n = poll(&pfd, 1, remaining);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) { if (!n) errno = ETIMEDOUT; return -1; }
        if (pfd.revents & events) return 0;
        errno = EIO; return -1;
    }
}
static int all_write(int fd, const void *buf, size_t n, long long deadline) {
    const unsigned char *p = buf;
    while (n) {
        if (ready(fd, POLLOUT, deadline)) return -1;
        ssize_t w = send(fd, p, n, MSG_NOSIGNAL);
        if (w < 0 && (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK)) continue;
        if (w <= 0) return -1;
        p += w; n -= (size_t)w;
    }
    return 0;
}
static int all_read(int fd, void *buf, size_t n, long long deadline) {
    unsigned char *p = buf;
    while (n) {
        if (ready(fd, POLLIN, deadline)) return -1;
        ssize_t r = recv(fd, p, n, 0);
        if (r < 0 && (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK)) continue;
        if (r <= 0) return -1;
        p += r; n -= (size_t)r;
    }
    return 0;
}
static int exchange(int fd, const char *request, int id, int hello, const unsigned char *binary, size_t binary_len, char **response_out) {
    size_t n = strlen(request); unsigned char h[4] = {(unsigned char)(n >> 24), (unsigned char)(n >> 16), (unsigned char)(n >> 8), (unsigned char)n};
    long long now = now_ms();
    if (now < 0) { fprintf(stderr, "termux-control: clock unavailable\n"); return -1; }
    long long deadline = now + 5000;
    if (n == 0 || n > MAX_FRAME || all_write(fd, h, 4, deadline) || all_write(fd, request, n, deadline)) {
        fprintf(stderr, "termux-control: connection failed or timed out\n"); return -2;
    }
    if (binary) {
        unsigned char header[4] = {(unsigned char)(binary_len >> 24), (unsigned char)(binary_len >> 16),
                                   (unsigned char)(binary_len >> 8), (unsigned char)binary_len};
        // A denied request may cause the server to reply and close before reading
        // the binary frame. Still try to read its structured response on send failure.
        if (all_write(fd, header, 4, deadline) || all_write(fd, binary, binary_len, deadline)) {
            if (all_read(fd, h, 4, deadline)) {
                fprintf(stderr, "termux-control: connection failed or timed out\n"); return -2;
            }
        } else if (all_read(fd, h, 4, deadline)) {
            fprintf(stderr, "termux-control: connection failed or timed out\n"); return -2;
        }
    } else if (all_read(fd, h, 4, deadline)) {
        fprintf(stderr, "termux-control: connection failed or timed out\n"); return -2;
    }
    uint32_t len = ((uint32_t)h[0] << 24) | ((uint32_t)h[1] << 16) | ((uint32_t)h[2] << 8) | h[3];
    if (len == 0 || len > MAX_FRAME) { fprintf(stderr, "termux-control: invalid response length\n"); return -1; }
    char *body = malloc((size_t)len + 1);
    if (!body) { fprintf(stderr, "termux-control: out of memory\n"); return -1; }
    if (all_read(fd, body, len, deadline)) { free(body); fprintf(stderr, "termux-control: incomplete response\n"); return -2; }
    if (memchr(body, 0, len)) { free(body); fprintf(stderr, "termux-control: invalid response\n"); return -1; }
    body[len] = 0;
    int result = response_ok(body, id, hello);
    if (result == 0 && id == 6) fprintf(stderr, "termux-control: resume rejected\n");
    else if (result == 0) fprintf(stderr, "termux-control: server error: %s\n", body);
    else if (result < 0) fprintf(stderr, "termux-control: invalid server response\n");
    else if (!hello && !response_out) puts(body);
    if (result == 1 && response_out) *response_out = body;
    else free(body);
    return result == 1 ? 0 : (result == 0 ? 1 : -1);
}
static int append(char *buf, size_t cap, size_t *used, const char *s) {
    size_t n = strlen(s); if (n >= cap - *used) return -1;
    memcpy(buf + *used, s, n + 1); *used += n; return 0;
}
static int quoted(char *buf, size_t cap, size_t *used, const char *s) {
    if (append(buf, cap, used, "\"")) return -1;
    for (const unsigned char *p = (const unsigned char *)s; *p; ++p) {
        char tmp[7];
        if (*p == '"' || *p == '\\') { tmp[0] = '\\'; tmp[1] = (char)*p; tmp[2] = 0; }
        else if (*p < 0x20) snprintf(tmp, sizeof(tmp), "\\u%04x", *p);
        else { tmp[0] = (char)*p; tmp[1] = 0; }
        if (append(buf, cap, used, tmp)) return -1;
    }
    return append(buf, cap, used, "\"");
}
static int number(const char *s, int min, int max) {
    char *end; errno = 0; long n = strtol(s, &end, 10);
    return !errno && *s && !*end && n >= min && n <= max;
}
/* Parse non-negative integer metadata without accepting signs, fractions or overflow. */
static int unsigned_member(struct span object, const char *name, uint64_t *out) {
    struct span v;
    if (member(object, name, &v) != 1 || v.start == v.end) return -1;
    uint64_t n = 0;
    for (const char *p = v.start; p < v.end; ++p) {
        if (*p < '0' || *p > '9' || n > (UINT64_MAX - (unsigned)(*p - '0')) / 10) return -1;
        n = n * 10 + (unsigned)(*p - '0');
    }
    *out = n;
    return 0;
}

static int read_output_once(int fd, const char *session_id, uint64_t *position, int *received) {
        uint64_t offset = *position;
        char request[MAX_REQUEST] = ""; size_t used = 0;
        if (append(request, sizeof(request), &used, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"session.output.read\",\"params\":{\"id\":") ||
            quoted(request, sizeof(request), &used, session_id)) return -1;
        char params[100];
        snprintf(params, sizeof(params), ",\"offset\":%llu,\"limit\":65536}}", (unsigned long long)offset);
        if (append(request, sizeof(request), &used, params)) return -1;
        char *response = NULL;
        int status = exchange(fd, request, 3, 0, NULL, 0, &response);
        if (status != 0) { free(response); return status; }
        const char *end = response;
        if (!value(&end, 0)) { free(response); return -1; }
        ws(&end);
        struct span root = {response, end}, result, gap;
        uint64_t start, next, latest, length;
        int valid = member(root, "result", &result) == 1 &&
            !unsigned_member(result, "offset", &start) && !unsigned_member(result, "nextOffset", &next) &&
            !unsigned_member(result, "endOffset", &latest) && !unsigned_member(result, "length", &length) &&
            member(result, "gap", &gap) == 1 && (equals(gap, "true") || equals(gap, "false"));
        int has_gap = valid && equals(gap, "true");
        if (!valid || length > MAX_FRAME || latest > INT64_MAX || start < offset || next < start || next - start != length ||
            next > latest || (!has_gap && start != offset) || (has_gap && start == offset)) {
            fprintf(stderr, "termux-control: invalid output metadata\n"); free(response); return -1;
        }
        free(response);
        if (has_gap) fprintf(stderr, "termux-control: output gap; older bytes were overwritten\n");
        if (length) {
            unsigned char header[4], data[MAX_FRAME];
            long long now = now_ms();
            if (now < 0 || all_read(fd, header, sizeof(header), now + 5000)) {
                fprintf(stderr, "termux-control: incomplete output frame\n"); return -2;
            }
            uint32_t size = ((uint32_t)header[0] << 24) | ((uint32_t)header[1] << 16) | ((uint32_t)header[2] << 8) | header[3];
            if (size != length) { fprintf(stderr, "termux-control: invalid output frame\n"); return -1; }
            if (all_read(fd, data, size, now + 5000)) {
                fprintf(stderr, "termux-control: incomplete output frame\n"); return -2;
            }
            if (fwrite(data, 1, size, stdout) != size || fflush(stdout)) {
                fprintf(stderr, "termux-control: cannot write output\n"); return -1;
            }
        }
        *received = length != 0;
        *position = next;
        return 0;
}

static volatile sig_atomic_t attach_stop, attach_suspend;
static int response_ack(const char *response, const char *field);
static int connect_raw(const char *path) {
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (fd < 0) { perror("termux-control: socket"); return -1; }
    struct timeval timeout = { .tv_sec = 5, .tv_usec = 0 };
    if (setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) || setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout))) { perror("termux-control: timeout"); close(fd); return -1; }
    struct sockaddr_un addr = { .sun_family = AF_UNIX }; memcpy(addr.sun_path, path, strlen(path) + 1);
    if (connect(fd, (struct sockaddr *)&addr, sizeof(addr))) {
        int saved = errno;
        long long now = now_ms();
        if ((saved != EINPROGRESS && saved != EAGAIN) || now < 0 || ready(fd, POLLOUT, now + 5000)) {
            if (saved != EINPROGRESS && saved != EAGAIN) errno = saved;
            fprintf(stderr, "termux-control: cannot connect to local control socket: %s\n", strerror(errno)); close(fd); return -1;
        }
        int error = 0; socklen_t error_size = sizeof(error);
        if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &error, &error_size) || error != 0) {
            if (error != 0) errno = error;
            fprintf(stderr, "termux-control: cannot connect to local control socket: %s\n", strerror(errno)); close(fd); return -1;
        }
    }
    struct ucred peer; socklen_t peer_size = sizeof(peer);
    if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &peer, &peer_size) || peer_size != sizeof(peer) || peer.uid != getuid()) {
        fprintf(stderr, "termux-control: socket peer is not the current UID\n"); close(fd); return -1;
    }
    return fd;
}
static int connect_control(const char *path) {
    int fd = connect_raw(path);
    if (fd < 0) return -1;
    int handshake = exchange(fd, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"system.hello\",\"params\":{\"version\":1}}", 1, 1, NULL, 0, NULL);
    if (handshake != 0) { close(fd); return -1; }
    return fd;
}

/* Debug transport bridge: copies bounded byte streams; the peer still authenticates and negotiates v1. */
static int bridge(const char *path) {
    int fd = connect_raw(path);
    if (fd < 0) return 1;
    int in_flags = fcntl(STDIN_FILENO, F_GETFL), out_flags = fcntl(STDOUT_FILENO, F_GETFL);
    if (in_flags < 0 || out_flags < 0 || fcntl(STDIN_FILENO, F_SETFL, in_flags | O_NONBLOCK) < 0) { close(fd); return 1; }
    if (fcntl(STDOUT_FILENO, F_SETFL, out_flags | O_NONBLOCK) < 0) {
        (void)fcntl(STDIN_FILENO, F_SETFL, in_flags); close(fd); return 1;
    }
    signal(SIGPIPE, SIG_IGN);
    unsigned char to_socket[16384], to_stdout[16384];
    size_t in_start = 0, in_end = 0, out_start = 0, out_end = 0;
    int stdin_open = 1, socket_open = 1, write_shutdown = 0, result = 0;
    while (socket_open || out_start < out_end) {
        if (!stdin_open && in_start == in_end && !write_shutdown) {
            (void)shutdown(fd, SHUT_WR); write_shutdown = 1;
        }
        struct pollfd fds[3] = {
            { STDIN_FILENO, stdin_open && in_start == in_end ? POLLIN : 0, 0 },
            { fd, (short)((socket_open && out_start == out_end ? POLLIN : 0) | (in_start < in_end ? POLLOUT : 0)), 0 },
            { STDOUT_FILENO, out_start < out_end ? POLLOUT : 0, 0 }
        };
        int ready_count = poll(fds, 3, -1);
        if (ready_count < 0) { if (errno == EINTR) continue; result = 1; break; }
        if (stdin_open && in_start == in_end && (fds[0].revents & (POLLIN | POLLHUP))) {
            ssize_t n = read(STDIN_FILENO, to_socket, sizeof(to_socket));
            if (n > 0) { in_start = 0; in_end = (size_t)n; }
            else if (n == 0) stdin_open = 0;
            else if (errno != EAGAIN && errno != EINTR) { result = 1; break; }
        }
        if (in_start < in_end && (fds[1].revents & POLLOUT)) {
            ssize_t n = send(fd, to_socket + in_start, in_end - in_start, MSG_NOSIGNAL);
            if (n > 0) in_start += (size_t)n;
            else if (n == 0 || (n < 0 && errno != EAGAIN && errno != EINTR)) { result = 1; break; }
        }
        if (socket_open && out_start == out_end && (fds[1].revents & (POLLIN | POLLHUP))) {
            ssize_t n = recv(fd, to_stdout, sizeof(to_stdout), 0);
            if (n > 0) { out_start = 0; out_end = (size_t)n; }
            else if (n == 0) socket_open = 0;
            else if (errno != EAGAIN && errno != EINTR) { result = 1; break; }
        }
        if (out_start < out_end && (fds[2].revents & POLLOUT)) {
            ssize_t n = write(STDOUT_FILENO, to_stdout + out_start, out_end - out_start);
            if (n > 0) out_start += (size_t)n;
            else if (n == 0 || (n < 0 && errno != EAGAIN && errno != EINTR)) { result = 1; break; }
        }
        if ((fds[0].revents | fds[1].revents | fds[2].revents) & (POLLERR | POLLNVAL)) { result = 1; break; }
    }
    (void)fcntl(STDIN_FILENO, F_SETFL, in_flags); (void)fcntl(STDOUT_FILENO, F_SETFL, out_flags);
    close(fd); return result;
}

/* Retry only read-side transport failures: never replay an uncertain input write. */
static int resume_output(int *fd, const char *path, const char *id, const char *token) {
    close(*fd); *fd = -1;
    long long now = now_ms();
    if (now < 0) return -1;
    long long deadline = now + 10000;
    fprintf(stderr, "termux-control: reconnecting output subscription\n");
    while (!attach_stop && !attach_suspend && (now = now_ms()) >= 0 && now < deadline) {
        int next = connect_control(path);
        if (next >= 0) {
            char req[MAX_REQUEST] = ""; size_t used = 0;
            if (append(req, sizeof(req), &used, "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"session.output.resume\",\"params\":{\"id\":") ||
                quoted(req, sizeof(req), &used, id) || append(req, sizeof(req), &used, ",\"token\":") ||
                quoted(req, sizeof(req), &used, token) || append(req, sizeof(req), &used, "}}")) { close(next); return -1; }
            char *response = NULL;
            int status = exchange(next, req, 6, 0, NULL, 0, &response);
            int ok = status == 0 && response_ack(response, "ok") == 0;
            free(response);
            if (ok) { *fd = next; fprintf(stderr, "termux-control: output subscription resumed\n"); return 0; }
            close(next);
            if (status == -1 || status == 0) return -1;
        }
        struct timespec delay = { .tv_sec = 0, .tv_nsec = 250000000 };
        while (!attach_stop && !attach_suspend && nanosleep(&delay, &delay) && errno == EINTR) { }
    }
    fprintf(stderr, "termux-control: output resume failed or expired\n"); return -1;
}

static int follow_output(int *fd, const char *session_id, const char *path, const char *token) {
    uint64_t offset = 0;
    if (isatty(STDOUT_FILENO))
        fprintf(stderr, "termux-control: follow from a different session or redirect stdout to avoid an output loop\n");
    for (;;) {
        int received = 0;
        int status = read_output_once(*fd, session_id, &offset, &received);
        if (status == -2 && token && !resume_output(fd, path, session_id, token)) continue;
        if (status) return status;
        if (!received) {
            struct timespec delay = { .tv_sec = 0, .tv_nsec = 250000000 };
            while (nanosleep(&delay, &delay) && errno == EINTR) { }
        }
    }
}

/* Refuse direct or ancestral self-attachment: reflecting output into its source can loop. */
static int ancestor_matches(pid_t target) {
    pid_t current = getpid();
    for (int depth = 0; depth < 128 && current > 1; ++depth) {
        if (current == target) return 1;
        char path[64], line[4096]; struct stat info;
        snprintf(path, sizeof(path), "/proc/%ld", (long)current);
        if (stat(path, &info)) return -1;
        if (info.st_uid != getuid()) return 0;
        snprintf(path, sizeof(path), "/proc/%ld/stat", (long)current);
        FILE *file = fopen(path, "r");
        if (!file) return -1;
        char *got = fgets(line, sizeof(line), file);
        fclose(file);
        if (!got) return -1;
        char *end = strrchr(line, ')'); char state; long parent;
        if (!end || sscanf(end + 1, " %c %ld", &state, &parent) != 2 || parent <= 0 || parent > INT_MAX || parent == current)
            return -1;
        current = (pid_t)parent;
    }
    return current <= 1 ? 0 : -1;
}

/* Identify the target using the server's session list, then compare real terminal devices. */
static int verify_attach_terminal(int fd, const char *session_id) {
    struct stat input, output, target;
    if (!isatty(STDIN_FILENO) || !isatty(STDOUT_FILENO) || fstat(STDIN_FILENO, &input) || fstat(STDOUT_FILENO, &output) ||
        !S_ISCHR(input.st_mode) || !S_ISCHR(output.st_mode) || input.st_rdev != output.st_rdev) {
        fprintf(stderr, "termux-control: attach requires the same interactive terminal for stdin and stdout\n"); return -1;
    }
    char *response = NULL;
    int status = exchange(fd, "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"session.list\",\"params\":{}}", 6, 0, NULL, 0, &response);
    if (status) { free(response); return -1; }
    const char *end = response; struct span root = {response, NULL}, result, sessions;
    char wanted[MAX_REQUEST] = ""; size_t used = 0; pid_t pid = 0; int found = 0;
    if (quoted(wanted, sizeof(wanted), &used, session_id) || !value(&end, 0)) goto invalid;
    ws(&end); root.end = end;
    if (member(root, "result", &result) != 1 || member(result, "sessions", &sessions) != 1) goto invalid;
    const char *p = sessions.start;
    if (*p++ != '[') goto invalid;
    ws(&p);
    while (p < sessions.end && *p != ']') {
        struct span entry, id, state; uint64_t rawpid;
        entry.start = p;
        if (!value(&p, 1)) goto invalid;
        entry.end = p;
        if (member(entry, "id", &id) != 1) goto invalid;
        if (equals(id, wanted)) {
            if (found++ || member(entry, "state", &state) != 1 || !equals(state, "\"running\"") ||
                unsigned_member(entry, "pid", &rawpid) || rawpid == 0 || rawpid > INT_MAX) goto invalid;
            pid = (pid_t)rawpid;
        }
        ws(&p);
        if (*p == ',') { ++p; ws(&p); }
        else if (*p != ']') goto invalid;
    }
    if (*p != ']' || !found) goto invalid;
    free(response); response = NULL;
    char path[64];
    int ancestor = ancestor_matches(pid);
    if (ancestor != 0) {
        fprintf(stderr, "termux-control: target ancestry could not be safely verified or is an ancestor\n"); return -1;
    }
    for (int descriptor = 0; descriptor < 3; ++descriptor) {
        snprintf(path, sizeof(path), "/proc/%ld/fd/%d", (long)pid, descriptor);
        if (stat(path, &target) || (descriptor == 0 && !S_ISCHR(target.st_mode))) {
            fprintf(stderr, "termux-control: target terminal identity could not be safely verified\n"); return -1;
        }
        if (S_ISCHR(target.st_mode) && target.st_rdev == input.st_rdev) {
            fprintf(stderr, "termux-control: refusing to attach a terminal to itself\n"); return -1;
        }
    }
    return 0;
invalid:
    free(response);
    fprintf(stderr, "termux-control: target session is unavailable or session metadata is invalid\n"); return -1;
}

static void attach_signal(int signal_number) {
    if (signal_number == SIGTSTP) attach_suspend = 1;
    else attach_stop = signal_number;
}
static int response_ack(const char *response, const char *field) {
    const char *end = response; struct span root = {response, NULL}, result, ok;
    if (!response || !value(&end, 0)) return -1;
    ws(&end); root.end = end;
    return member(root, "result", &result) == 1 && member(result, field, &ok) == 1 && equals(ok, "true") ? 0 : -1;
}
static int attach_write(int fd, const char *id, const unsigned char *data, size_t length) {
    char request[MAX_REQUEST] = ""; size_t used = 0; char tail[64];
    snprintf(tail, sizeof(tail), ",\"length\":%zu}}", length);
    if (append(request, sizeof(request), &used, "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"session.write\",\"params\":{\"id\":") ||
        quoted(request, sizeof(request), &used, id) || append(request, sizeof(request), &used, tail)) return -1;
    char *response = NULL; int status = exchange(fd, request, 4, 0, data, length, &response);
    if (!status && response_ack(response, "ok")) status = -1;
    free(response);
    if (status < 0) fprintf(stderr, "termux-control: input outcome may be unknown; do not retry automatically\n");
    return status;
}
static int attach_resize(int fd, const char *id, unsigned columns, unsigned rows) {
    char request[MAX_REQUEST] = ""; size_t used = 0; char tail[96];
    snprintf(tail, sizeof(tail), ",\"columns\":%u,\"rows\":%u}}", columns, rows);
    if (append(request, sizeof(request), &used, "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"session.resize\",\"params\":{\"id\":") ||
        quoted(request, sizeof(request), &used, id) || append(request, sizeof(request), &used, tail)) return -1;
    char *response = NULL; int status = exchange(fd, request, 5, 0, NULL, 0, &response);
    if (!status && response_ack(response, "ok")) status = -1;
    free(response);
    return status;
}
static int attach_loop(int *fd, const char *id, const char *path, const char *token) {
    struct termios saved, raw;
    if (tcgetattr(STDIN_FILENO, &saved)) { perror("termux-control: tcgetattr"); return -1; }
    raw = saved; cfmakeraw(&raw);
    int signals[] = {SIGINT, SIGTERM, SIGHUP, SIGQUIT, SIGPIPE, SIGTSTP};
    struct sigaction previous[sizeof(signals) / sizeof(signals[0])], action;
    memset(&action, 0, sizeof(action)); action.sa_handler = attach_signal; sigemptyset(&action.sa_mask);
    attach_stop = 0; attach_suspend = 0;
    size_t installed = 0;
    for (; installed < sizeof(signals) / sizeof(signals[0]); ++installed)
        if (sigaction(signals[installed], &action, &previous[installed])) break;
    if (installed != sizeof(signals) / sizeof(signals[0])) {
        while (installed) { --installed; sigaction(signals[installed], &previous[installed], NULL); }
        fprintf(stderr, "termux-control: cannot install terminal signal handlers\n"); return -1;
    }
    if (tcsetattr(STDIN_FILENO, TCSANOW, &raw)) {
        int error = errno;
        (void)tcsetattr(STDIN_FILENO, TCSANOW, &saved);
        while (installed) { --installed; sigaction(signals[installed], &previous[installed], NULL); }
        errno = error;
        perror("termux-control: cannot enter raw mode"); return -1;
    }
    fprintf(stderr, "termux-control: attached; Ctrl-] then . detaches; Ctrl-] twice sends Ctrl-]\r\n");
    uint64_t offset = 0; unsigned last_columns = 0, last_rows = 0; int escape = 0, result = 0, backlog = 0;
    while (!attach_stop) {
        if (attach_suspend) {
            attach_suspend = 0;
            if (tcsetattr(STDIN_FILENO, TCSANOW, &saved)) { result = -1; break; }
            kill(getpid(), SIGSTOP);
            if (tcsetattr(STDIN_FILENO, TCSANOW, &raw)) { result = -1; break; }
            last_columns = last_rows = 0;
        }
        struct winsize size;
        if (!ioctl(STDIN_FILENO, TIOCGWINSZ, &size) && size.ws_col >= 2 && size.ws_col <= 500 &&
            size.ws_row >= 2 && size.ws_row <= 250 && (last_columns != size.ws_col || last_rows != size.ws_row)) {
            last_columns = size.ws_col; last_rows = size.ws_row;
            int status = attach_resize(*fd, id, last_columns, last_rows);
            if (status < 0) { result = -1; break; }
            if (status > 0) fprintf(stderr, "termux-control: resize was rejected; local display may own the geometry\r\n");
        }
        struct pollfd input = { .fd = STDIN_FILENO, .events = POLLIN };
        int ready_input = poll(&input, 1, backlog ? 0 : 100);
        if (ready_input < 0) { if (errno == EINTR) continue; result = -1; break; }
        if (ready_input > 0 && (input.revents & (POLLIN | POLLHUP))) {
            unsigned char data[4096], sendbuf[8194]; ssize_t count = read(STDIN_FILENO, data, sizeof(data));
            if (count < 0 && (errno == EINTR || errno == EAGAIN)) continue;
            if (count <= 0) break;
            size_t written = 0; int detach = 0;
            for (ssize_t i = 0; i < count; ++i) {
                unsigned char c = data[i];
                if (escape) {
                    escape = 0;
                    if (c == '.') { detach = 1; break; }
                    sendbuf[written++] = 0x1d;
                    if (c == 0x1d) continue;
                }
                if (c == 0x1d) escape = 1; else sendbuf[written++] = c;
            }
            if (written && attach_write(*fd, id, sendbuf, written)) { result = -1; break; }
            if (detach) break;
        } else if (ready_input > 0 && (input.revents & (POLLERR | POLLNVAL))) { result = -1; break; }
        int received = 0;
        int read_status = read_output_once(*fd, id, &offset, &received);
        if (read_status == -2 && token && !attach_stop && !resume_output(fd, path, id, token)) {
            last_columns = last_rows = 0; continue;
        }
        if (read_status) { result = -1; break; }
        backlog = received;
    }
    if (tcsetattr(STDIN_FILENO, TCSANOW, &saved)) { perror("termux-control: cannot restore terminal"); result = -1; }
    while (installed) { --installed; sigaction(signals[installed], &previous[installed], NULL); }
    if (attach_stop) result = 128 + attach_stop;
    return result;
}

static void usage(void) {
    fprintf(stderr, "Usage: termux-control [--socket PATH] [--resume] capabilities|list|create COLS ROWS [NAME]|rename ID NAME|resize ID COLS ROWS|focus ID|snapshot ID|close ID|write ID < FILE|follow ID|attach ID|bridge\n");
}
int main(int argc, char **argv) {
    const char *path = getenv("TERMUX_CONTROL_SOCKET"); char default_path[sizeof(((struct sockaddr_un *)0)->sun_path)];
    int i = 1, resume = 0;
    while (i < argc && argv[i][0] == '-') {
        if (!strcmp(argv[i], "--socket")) { if (++i >= argc) { usage(); return 2; } path = argv[i++]; }
        else if (!strcmp(argv[i], "--resume")) { resume = 1; ++i; }
        else { usage(); return 2; }
    }
    if (!path || !*path) {
        const char *home = getenv("HOME");
        if (!home || snprintf(default_path, sizeof(default_path), "%s/../control-ipc/control.sock", home) >= (int)sizeof(default_path)) {
            fprintf(stderr, "termux-control: HOME or socket path is invalid\n"); return 2;
        }
        path = default_path;
    }
    if (i >= argc || strlen(path) >= sizeof(((struct sockaddr_un *)0)->sun_path)) { usage(); return 2; }
    const char *cmd = argv[i++]; int count = argc - i; char req[MAX_REQUEST] = ""; size_t used = 0;
    if (!strcmp(cmd, "bridge") && count == 0 && !resume) return bridge(path);
    const char *method = NULL;
    if (!strcmp(cmd, "list") && count == 0) method = "session.list";
    else if (!strcmp(cmd, "capabilities") && count == 0) method = "system.capabilities";
    else if (!strcmp(cmd, "create") && (count == 2 || count == 3) && number(argv[i], 2, 500) && number(argv[i + 1], 2, 250)) method = "session.create";
    else if (!strcmp(cmd, "rename") && count == 2) method = "session.rename";
    else if (!strcmp(cmd, "focus") && count == 1) method = "session.focus";
    else if (!strcmp(cmd, "snapshot") && count == 1) method = "session.snapshot";
    else if (!strcmp(cmd, "resize") && count == 3 && number(argv[i + 1], 2, 500) && number(argv[i + 2], 2, 250)) method = "session.resize";
    else if (!strcmp(cmd, "close") && count == 1) method = "session.close";
    else if (!strcmp(cmd, "write") && count == 1) method = "session.write";
    else if ((!strcmp(cmd, "follow") || !strcmp(cmd, "attach")) && count == 1) method = "session.output.subscribe";
    else { usage(); return 2; }
    if (resume && strcmp(cmd, "follow") && strcmp(cmd, "attach")) { usage(); return 2; }
    unsigned char binary[MAX_FRAME + 1];
    size_t binary_len = 0;
    if (!strcmp(cmd, "write")) {
        binary_len = fread(binary, 1, sizeof(binary), stdin);
        if (ferror(stdin)) { fprintf(stderr, "termux-control: cannot read stdin\n"); return 1; }
        if (binary_len == 0 || binary_len > MAX_FRAME) {
            fprintf(stderr, "termux-control: input must contain 1 to 65536 bytes\n"); return 2;
        }
    }
    if (append(req, sizeof(req), &used, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":") || quoted(req, sizeof(req), &used, method) || append(req, sizeof(req), &used, ",\"params\":{")) goto too_long;
    if (!strcmp(cmd, "rename") || !strcmp(cmd, "resize") || !strcmp(cmd, "close") || !strcmp(cmd, "write") || !strcmp(cmd, "follow") || !strcmp(cmd, "attach") || !strcmp(cmd, "focus") || !strcmp(cmd, "snapshot")) {
        if (append(req, sizeof(req), &used, "\"id\":") || quoted(req, sizeof(req), &used, argv[i])) goto too_long;
    }
    if (!strcmp(cmd, "write")) {
        char length[40]; snprintf(length, sizeof(length), ",\"length\":%zu", binary_len);
        if (append(req, sizeof(req), &used, length)) goto too_long;
    }
    if (!strcmp(cmd, "rename")) { if (append(req, sizeof(req), &used, ",\"name\":") || quoted(req, sizeof(req), &used, argv[i + 1])) goto too_long; }
    if (!strcmp(cmd, "create") || !strcmp(cmd, "resize")) {
        int offset = !strcmp(cmd, "resize") ? 1 : 0; char dims[80];
        snprintf(dims, sizeof(dims), "%s\"columns\":%ld,\"rows\":%ld", offset ? "," : "", strtol(argv[i + offset], NULL, 10), strtol(argv[i + offset + 1], NULL, 10));
        if (append(req, sizeof(req), &used, dims)) goto too_long;
        if (!offset && count == 3) { if (append(req, sizeof(req), &used, ",\"name\":") || quoted(req, sizeof(req), &used, argv[i + 2])) goto too_long; }
    }
    if (resume && append(req, sizeof(req), &used, ",\"resumable\":true")) goto too_long;
    if (append(req, sizeof(req), &used, "}}")) goto too_long;
    int fd = connect_control(path);
    if (fd < 0) return 1;
    int result;
    if (!strcmp(cmd, "attach") && verify_attach_terminal(fd, argv[i])) { close(fd); return 1; }
    if (!strcmp(cmd, "follow") || !strcmp(cmd, "attach")) {
        char *response = NULL; char token[65] = "";
        result = exchange(fd, req, 2, 0, NULL, 0, &response);
        if (result == 0) {
            const char *end = response;
            struct span root = {response, NULL}, object, ok;
            if (!value(&end, 0)) result = -1;
            else {
                ws(&end); root.end = end;
                if (member(root, "result", &object) != 1 || member(object, "ok", &ok) != 1 || !equals(ok, "true")) result = -1;
                if (!result && resume) {
                    struct span t;
                    if (member(object, "resumeToken", &t) != 1 || t.end - t.start != 66 || *t.start != '"' || t.end[-1] != '"') result = -1;
                    else {
                        for (int n = 0; n < 64; ++n) if (!((t.start[n+1] >= '0' && t.start[n+1] <= '9') || (t.start[n+1] >= 'a' && t.start[n+1] <= 'f'))) result = -1;
                        if (!result) memcpy(token, t.start + 1, 64);
                    }
                }
            }
            if (result != 0) fprintf(stderr, "termux-control: invalid subscription response\n");
        }
        free(response);
        if (result == 0) result = !strcmp(cmd, "attach") ? attach_loop(&fd, argv[i], path, resume ? token : NULL) : follow_output(&fd, argv[i], path, resume ? token : NULL);
        if (!result && resume && fd >= 0) {
            char unsubscribe[MAX_REQUEST] = ""; size_t used_unsub = 0;
            if (!append(unsubscribe, sizeof(unsubscribe), &used_unsub, "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"session.output.unsubscribe\",\"params\":{\"id\":") &&
                !quoted(unsubscribe, sizeof(unsubscribe), &used_unsub, argv[i]) && !append(unsubscribe, sizeof(unsubscribe), &used_unsub, "}}")) {
                char *ignored = NULL; (void)exchange(fd, unsubscribe, 7, 0, NULL, 0, &ignored); free(ignored);
            }
        }
        memset(token, 0, sizeof(token));
    } else {
        result = exchange(fd, req, 2, 0, binary_len ? binary : NULL, binary_len, NULL);
    }
    if (result < 0 && strcmp(cmd, "list") && strcmp(cmd, "capabilities") && strcmp(cmd, "follow") && strcmp(cmd, "attach"))
        fprintf(stderr, "termux-control: request outcome may be unknown; inspect session state before retrying\n");
    close(fd); return result == 0 ? 0 : 1;
too_long:
    fprintf(stderr, "termux-control: request is too long\n"); return 2;
}
