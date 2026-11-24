CC ?= cc
CFLAGS ?= -Wall -Wextra -O0 -g
JNI := ../../app/src/main/jni

.PHONY: all test clean

all: status_protocol_test final_publish_test

status_protocol_test: status_protocol_test.c $(JNI)/status_protocol.c $(JNI)/status_protocol.h
	$(CC) $(CFLAGS) -o $@ status_protocol_test.c $(JNI)/status_protocol.c

# Exercises the real df_publish_final() implementation against real files,
# including a hostile umask, so the world-readable mode cannot regress silently.
final_publish_test: final_publish_test.c $(JNI)/final_status_publish.c $(JNI)/final_status_publish.h
	$(CC) $(CFLAGS) -I$(JNI) -o $@ final_publish_test.c $(JNI)/final_status_publish.c

test: status_protocol_test final_publish_test
	./status_protocol_test
	./final_publish_test

clean:
	rm -f status_protocol_test final_publish_test
