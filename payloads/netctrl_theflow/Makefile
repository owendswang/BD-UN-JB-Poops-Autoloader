JAR_NAME := netctrl_theflow.jar

MAKEFILE_DIR := $(dir $(realpath $(lastword $(MAKEFILE_LIST))))
BDJSDK_HOME  ?= $(MAKEFILE_DIR)/../../../../
JAVA8_HOME   ?= $(BDJSDK_HOME)/host/jdk8
JAVAC        := $(JAVA8_HOME)/bin/javac
JAR          := $(JAVA8_HOME)/bin/jar

export JAVA8_HOME

CLASSPATH     := $(BDJSDK_HOME)/target/lib/enhanced-stubs.zip:$(BDJSDK_HOME)/target/lib/bdjstack.jar:$(BDJSDK_HOME)/target/lib/pbp.jar:../../discdir/BDMV/JAR/00000.jar
SOURCES       := $(wildcard src/org/bdj/external/*.java)
JFLAGS        := -Xlint:-options -source 1.4 -target 1.4

all: $(JAR_NAME)

# Create JAR with manifest
$(JAR_NAME): $(SOURCES) manifest.txt
	$(JAVAC) $(JFLAGS) -cp $(CLASSPATH) $(SOURCES)
	mkdir -p build
	rsync -a $(MAKEFILE_DIR)/../../bin/ $(MAKEFILE_DIR)/build/
	rsync -a --exclude='*.java' --exclude='*.bak' src/ $(MAKEFILE_DIR)/build/
	rsync -a $(MAKEFILE_DIR)/../../ps5_autoload_elf/ps5_autoload.elf $(MAKEFILE_DIR)/build/ps5_autoload.elf
	rsync -a $(MAKEFILE_DIR)/../../ps5_killdiscplayer_elf/ps5_killdiscplayer.elf $(MAKEFILE_DIR)/build/ps5_killdiscplayer.elf
	$(JAR) cfm $@ manifest.txt -C $(MAKEFILE_DIR)/build/ .
	cp -f $(JAR_NAME) $(MAKEFILE_DIR)/../../payload.jar

clean:
	rm -rf build src/org/bdj/external/*.class $(JAR_NAME)
