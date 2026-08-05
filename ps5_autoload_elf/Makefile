# BD-J Stack Security Patch Payload

ifdef PS5_PAYLOAD_SDK
    include $(PS5_PAYLOAD_SDK)/toolchain/prospero.mk
else
    $(error PS5_PAYLOAD_SDK is undefined)
endif

CFLAGS  := -Wall -Os -DZIP_HAVE_SYMLINK=0
LDFLAGS := -lkernel -lc

TARGET := ps5_autoload.elf

all: $(TARGET)

$(TARGET): main.c zip.c
	$(CC) $(CFLAGS) -o $@ $^ $(LDFLAGS)
	@echo "Built $@ ($$(stat -c%s $@) bytes)"

clean:
	rm -f $(TARGET)

.PHONY: all clean
