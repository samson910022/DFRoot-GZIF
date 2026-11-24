CC ?= cc
CFLAGS ?= -Wall -Wextra -O0 -g
JNI := ../../app/src/main/jni

.PHONY: all test clean fixtures

all: status_protocol_test final_publish_test elf_parser_test device_version_test

status_protocol_test: status_protocol_test.c $(JNI)/status_protocol.c $(JNI)/status_protocol.h
	$(CC) $(CFLAGS) -o $@ status_protocol_test.c $(JNI)/status_protocol.c

# Exercises the real df_publish_final() implementation against real files,
# including a hostile umask, so the world-readable mode cannot regress silently.
final_publish_test: final_publish_test.c $(JNI)/final_status_publish.c $(JNI)/final_status_publish.h
	$(CC) $(CFLAGS) -I$(JNI) -o $@ final_publish_test.c $(JNI)/final_status_publish.c

# Table-driven parser tests over deterministic, checked-in ELF fixtures.
elf_parser_test: elf_parser_test.c $(JNI)/elf_parser.c $(JNI)/elf_parser.h
	$(CC) $(CFLAGS) -I$(JNI) -o $@ elf_parser_test.c $(JNI)/elf_parser.c

device_version_test: device_version_test.c $(JNI)/device_version.c $(JNI)/device_version.h
	$(CC) $(CFLAGS) -I$(JNI) -o $@ device_version_test.c $(JNI)/device_version.c

# Regenerate the binary fixtures (checked in; run only on deliberate changes).
fixtures:
	python3 fixtures/gen_fixtures.py

test: status_protocol_test final_publish_test elf_parser_test device_version_test
	./status_protocol_test
	./final_publish_test
	./elf_parser_test
	./device_version_test

clean:
	rm -f status_protocol_test final_publish_test elf_parser_test device_version_test
