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
#include <stdarg.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>
#include "zip.h"

#define USB_MAX_COUNT 8
#define AUTOLOADER_FOLDER "ps5_autoloader"
#define AUTOLOAD_CONFIG "autoload.txt"
#define ELFLDR_PORT 9021
#define DATA_DIR "/data/ps5_autoloader"
#define UPDATE_FILE "ps5_autoloader_update.zip"

typedef struct {
    char useless1[45];
    char message[3075];
} notify_request_t;

struct extract_ctx {
  FILE *fp;
};

int sceKernelSendNotificationRequest(int, notify_request_t *, size_t, int);

static void notify(const char *fmt, ...) {
    notify_request_t req;
    va_list args;

    bzero(&req, sizeof req);
    va_start(args, fmt);
    vsnprintf(req.message, sizeof req.message, fmt, args);
    va_end(args);

    sceKernelSendNotificationRequest(0, &req, sizeof req, 0);
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

    if (!read_file(path, &data, &size)) {
        notify("[ERROR] Failed to read:\n%s", path);
        return false;
    }

    notify("Loading ELF from:\n%s", path);

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

    // notify("Sent %zu bytes to loader", sent_total);
    return true;
}

static bool check_candidate_dir(const char *dir, char *out_dir, size_t out_size) {
    char config_path[PATH_MAX];

    if (snprintf(config_path, sizeof(config_path), "%s%s", dir, AUTOLOAD_CONFIG) >= (int)sizeof(config_path)) {
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
    int i;

    for (i = 0; i < USB_MAX_COUNT; i++) {
        if (snprintf(candidate, sizeof(candidate), "/mnt/usb%d/%s/", i, AUTOLOADER_FOLDER) < (int)sizeof(candidate) &&
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

    if (snprintf(config_path, sizeof(config_path), "%s%s", base_dir, AUTOLOAD_CONFIG) >= (int)sizeof(config_path)) {
        notify("[ERROR] Config path too long");
        return 1;
    }

    fp = fopen(config_path, "r");
    if (fp == NULL) {
        notify("[ERROR] Failed to open autoload.txt");
        return 1;
    }

    notify("Loading config from:\n%s", config_path);

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
                notify("[ERROR] Invalid sleep time:\n%s", line + 1);
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
                notify("[ERROR] File not found:\n%s", full_path);
                continue;
            }
            if (!send_file_to_loader(full_path, ELFLDR_PORT)) {
                fclose(fp);
                return 1;
            }
            continue;
        }

        notify("[ERROR] Unsupported file type:\n%s", line);
    }

    fclose(fp);
    return 0;
}

static int path_exists(const char *path) {
    struct stat st;
    return stat(path, &st) == 0;
}

static int find_update_zip(char *out_path, size_t out_size) {
    int i;
    for (i = 0; i < USB_MAX_COUNT; i++) {
        char path[PATH_MAX];
        snprintf(path, sizeof(path), "/mnt/usb%d/%s", i, UPDATE_FILE);
        if (path_exists(path)) {
            snprintf(out_path, out_size, "%s", path);
            // notify("Found Autoloader update file:\n%s", path);
            return true;
        }
    }
    return false;
}

static int ensure_dir(const char *path) {
    struct stat st;

    if (stat(path, &st) == 0) {
        if (S_ISDIR(st.st_mode)) {
            return 0;
        }
        notify("[ERROR] path exists but is not a directory: %s\n", path);
        return -1;
    }

    if (mkdir(path, 0755) == 0) {
        return 0;
    }

    if (errno == EEXIST) {
        return 0;
    }

    notify("[ERROR] mkdir failed:\n%s", path);
    return -1;
}

int main(void) {
    // notify("PS5 C autoloader starting");

    char base_dir[PATH_MAX];
    char update_zip_path[PATH_MAX];

    if (find_update_zip(update_zip_path, sizeof(update_zip_path))) {
        // notify("Updating 'ps5_autoloader' from %s", update_zip_path);
        notify("Updating 'ps5_autoloader' from USB");
        
        if (ensure_dir(DATA_DIR) == 0) {
            int ret = zip_extract(update_zip_path, DATA_DIR, NULL, NULL);
            if (ret != 0) {
                notify("[ERROR] Update extract failed:\n%s", zip_strerror(ret));
            }
        }

        usleep((useconds_t)1000000U);
    }

    if (!find_autoload_base(base_dir, sizeof(base_dir))) {
        notify("Autoload config not found");
        return 1;
    }

    if (process_config(base_dir) != 0) {
        return 1;
    }

    notify("Loader finished");
    return 0;
}
