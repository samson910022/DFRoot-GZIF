CC ?= cc
CFLAGS ?= -Wall -Wextra -O0 -g
JNI := ../../app/src/main/jni

.PHONY: all test clean

all: status_poll_test

status_poll_test: status_poll_test.c $(JNI)/status_poll.c $(JNI)/status_poll.h
	$(CC) $(CFLAGS) -o $@ status_poll_test.c $(JNI)/status_poll.c

test: status_poll_test
	./status_poll_test

clean:
	rm -f status_poll_test
