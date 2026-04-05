#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <netinet/in.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

typedef struct {
    char useless1[45];
    char message[3075];
} notify_request_t;

int sceKernelSendNotificationRequest(int, notify_request_t *, size_t, int);

static const char *k_autoload_dir = "ps5_autoloader";
static const char *k_autoload_config = "autoload.txt";
static const int k_loader_port = 9021;

static void notify(const char *msg) {
    notify_request_t req;

    memset(&req, 0, sizeof(req));
    strncpy(req.message, msg, sizeof(req.message) - 1);
    sceKernelSendNotificationRequest(0, &req, sizeof(req), 0);
}

static bool file_exists(const char *path) {
    struct stat st;
    return stat(path, &st) == 0;
}

static bool read_file(const char *path, void **data_out, size_t *size_out) {
    int fd;
    struct stat st;
    void *buf;
    ssize_t got;
    size_t total;

    *data_out = NULL;
    *size_out = 0;

    fd = open(path, O_RDONLY);
    if (fd < 0) {
        return false;
    }

    if (fstat(fd, &st) != 0 || st.st_size < 0) {
        close(fd);
        return false;
    }

    buf = malloc((size_t)st.st_size);
    if (buf == NULL) {
        close(fd);
        return false;
    }

    total = 0;
    while (total < (size_t)st.st_size) {
        got = read(fd, (char *)buf + total, (size_t)st.st_size - total);
        if (got <= 0) {
            free(buf);
            close(fd);
            return false;
        }
        total += (size_t)got;
    }

    close(fd);
    *data_out = buf;
    *size_out = total;
    return true;
}

static void trim_line(char *line) {
    char *start = line;
    char *end;
    size_t len;

    while (*start == ' ' || *start == '\t' || *start == '\r' || *start == '\n') {
        start++;
    }

    len = strlen(start);
    memmove(line, start, len + 1);

    len = strlen(line);
    while (len > 0) {
        end = &line[len - 1];
        if (*end == ' ' || *end == '\t' || *end == '\r' || *end == '\n') {
            *end = '\0';
            len--;
            continue;
        }
        break;
    }
}

static bool ends_with(const char *str, const char *suffix) {
    size_t str_len = strlen(str);
    size_t suffix_len = strlen(suffix);

    if (suffix_len > str_len) {
        return false;
    }

    return strcmp(str + str_len - suffix_len, suffix) == 0;
}

static bool is_blocked_loader_name(const char *name) {
    return strcmp(name, "elfldr.elf") == 0 || strcmp(name, "elfldr.bin") == 0;
}

static bool send_file_to_loader(const char *path, int port) {
    int sockfd;
    struct sockaddr_in addr;
    void *data;
    size_t size;
    size_t sent_total;
    char msg[512];

    if (!read_file(path, &data, &size)) {
        snprintf(msg, sizeof(msg), "[ERROR] Failed to read:\n%s", path);
        notify(msg);
        return false;
    }

    snprintf(msg, sizeof(msg), "Loading ELF from:\n%s", path);
    notify(msg);

    sockfd = socket(AF_INET, SOCK_STREAM, 0);
    if (sockfd < 0) {
        free(data);
        notify("[ERROR] socket() failed");
        return false;
    }

    memset(&addr, 0, sizeof(addr));
    addr.sin_len = sizeof(addr);
    addr.sin_family = AF_INET;
    addr.sin_port = htons((uint16_t)port);
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);

    if (connect(sockfd, (const struct sockaddr *)&addr, sizeof(addr)) != 0) {
        close(sockfd);
        free(data);
        notify("[ERROR] connect() to 127.0.0.1:9021 failed");
        return false;
    }

    sent_total = 0;
    while (sent_total < size) {
        ssize_t sent_now = send(sockfd, (const char *)data + sent_total, size - sent_total, 0);
        if (sent_now <= 0) {
            close(sockfd);
            free(data);
            notify("[ERROR] send() failed while streaming ELF");
            return false;
        }
        sent_total += (size_t)sent_now;
    }

    close(sockfd);
    free(data);

    snprintf(msg, sizeof(msg), "Sent %zu bytes to loader", sent_total);
    notify(msg);
    return true;
}

static bool check_candidate_dir(const char *dir, char *out_dir, size_t out_size) {
    char config_path[PATH_MAX];

    if (snprintf(config_path, sizeof(config_path), "%s%s", dir, k_autoload_config) >= (int)sizeof(config_path)) {
        return false;
    }

    if (!file_exists(config_path)) {
        return false;
    }

    strncpy(out_dir, dir, out_size - 1);
    out_dir[out_size - 1] = '\0';
    return true;
}

static bool find_autoload_base(char *out_dir, size_t out_size) {
    char candidate[PATH_MAX];
    int usb;

    for (usb = 0; usb <= 7; usb++) {
        if (snprintf(candidate, sizeof(candidate), "/mnt/usb%d/%s/", usb, k_autoload_dir) < (int)sizeof(candidate) &&
            check_candidate_dir(candidate, out_dir, out_size)) {
            return true;
        }
    }

    if (check_candidate_dir("/data/ps5_autoloader/", out_dir, out_size)) {
        return true;
    }

    if (check_candidate_dir("/mnt/disc/ps5_autoloader/", out_dir, out_size)) {
        return true;
    }

    return false;
}

static int process_config(const char *base_dir) {
    char config_path[PATH_MAX];
    FILE *fp;
    char line[1024];
    char msg[512];

    if (snprintf(config_path, sizeof(config_path), "%s%s", base_dir, k_autoload_config) >= (int)sizeof(config_path)) {
        notify("[ERROR] Config path too long");
        return 1;
    }

    fp = fopen(config_path, "r");
    if (fp == NULL) {
        notify("[ERROR] Failed to open autoload.txt");
        return 1;
    }

    snprintf(msg, sizeof(msg), "Loading config from:\n%s", config_path);
    notify(msg);

    while (fgets(line, sizeof(line), fp) != NULL) {
        char full_path[PATH_MAX];

        trim_line(line);

        if (line[0] == '\0' || line[0] == '#') {
            continue;
        }

        if (line[0] == '!') {
            char *endptr = NULL;
            long sleep_ms = strtol(line + 1, &endptr, 10);

            if (endptr == line + 1 || *endptr != '\0' || sleep_ms < 0) {
                snprintf(msg, sizeof(msg), "[ERROR] Invalid sleep time:\n%s", line + 1);
                notify(msg);
                fclose(fp);
                return 1;
            }

            usleep((useconds_t)sleep_ms * 1000U);
            continue;
        }

        if (snprintf(full_path, sizeof(full_path), "%s%s", base_dir, line) >= (int)sizeof(full_path)) {
            notify("[ERROR] Entry path too long");
            fclose(fp);
            return 1;
        }

        if (ends_with(line, ".elf") || ends_with(line, ".bin")) {
            if (is_blocked_loader_name(line)) {
                notify("[ERROR] Remove elfldr from autoload.txt");
                fclose(fp);
                return 1;
            }
            if (!file_exists(full_path)) {
                snprintf(msg, sizeof(msg), "[ERROR] File not found:\n%s", full_path);
                notify(msg);
                continue;
            }
            if (!send_file_to_loader(full_path, k_loader_port)) {
                fclose(fp);
                return 1;
            }
            continue;
        }

        snprintf(msg, sizeof(msg), "[ERROR] Unsupported file type:\n%s", line);
        notify(msg);
    }

    fclose(fp);
    return 0;
}

int main(void) {
    char base_dir[PATH_MAX];

    // notify("PS5 C autoloader starting");

    if (!find_autoload_base(base_dir, sizeof(base_dir))) {
        notify("autoload config not found");
        return 1;
    }

    if (process_config(base_dir) != 0) {
        return 1;
    }

    notify("Loader finished");
    return 0;
}
