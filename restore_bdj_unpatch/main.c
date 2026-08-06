/* Copyright (C) 2026
This program is free software; you can redistribute it and/or modify it
under the terms of the GNU General Public License as published by the
Free Software Foundation; either version 3, or (at your option) any
later version.
This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.
You should have received a copy of the GNU General Public License
along with this program; see the file COPYING. If not, see
<http://www.gnu.org/licenses/>.  */
#include <errno.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include <sys/_iovec.h>
#include <sys/mount.h>

#define IOVEC_SIZE(x) (sizeof(x) / sizeof(struct iovec))
#define IOVEC_ENTRY(x) {x ? x : 0, x ? strlen(x) + 1 : 0}

#define JAR_PATH     "/system_ex/app/NPXS40140/cdc/bdjstack.jar"
#define JAR_BAK_PATH "/system_ex/app/NPXS40140/cdc/bdjstack.jar.bak"

typedef struct notify_request {
  char useless1[45];
  char message[3075];
} notify_request_t;

int sceKernelSendNotificationRequest(int, notify_request_t *, size_t, int);

static void
notify(const char *fmt, ...) {
  notify_request_t req;
  va_list args;

  bzero(&req, sizeof req);
  va_start(args, fmt);
  vsnprintf(req.message, sizeof req.message, fmt, args);
  va_end(args);

  sceKernelSendNotificationRequest(0, &req, sizeof req, 0);
}

static struct iovec iov_sysex[] = {
  IOVEC_ENTRY("from"),      IOVEC_ENTRY("/dev/ssd0.system_ex"),
  IOVEC_ENTRY("fspath"),    IOVEC_ENTRY("/system_ex"),
  IOVEC_ENTRY("fstype"),    IOVEC_ENTRY("exfatfs"),
  IOVEC_ENTRY("large"),     IOVEC_ENTRY("yes"),
  IOVEC_ENTRY("timezone"),  IOVEC_ENTRY("static"),
  IOVEC_ENTRY("async"),     IOVEC_ENTRY(NULL),
  IOVEC_ENTRY("ignoreacl"), IOVEC_ENTRY(NULL),
};

int
main(void) {
  FILE *backup = fopen(JAR_BAK_PATH, "rb");
  if (!backup) {
    notify("bdjstack.jar.bak not found: %s", strerror(errno));
    return 1;
  }
  fclose(backup);

  if (nmount(iov_sysex, IOVEC_SIZE(iov_sysex), MNT_UPDATE)) {
    notify("system_ex remount rw failed: %s", strerror(errno));
    return 1;
  }

  /* rename() replaces the patched jar and consumes the backup atomically. */
  if (rename(JAR_BAK_PATH, JAR_PATH)) {
    int saved_errno = errno;

    notify("restore bdjstack.jar failed: %s", strerror(saved_errno));
    nmount(iov_sysex, IOVEC_SIZE(iov_sysex), MNT_UPDATE | MNT_RDONLY);
    return 1;
  }

  notify("Restored bdjstack.jar from backup");

  if (nmount(iov_sysex, IOVEC_SIZE(iov_sysex), MNT_UPDATE | MNT_RDONLY)) {
    notify("system_ex remount ro failed: %s", strerror(errno));
    return 1;
  }

  return 0;
}
