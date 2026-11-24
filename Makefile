SHELL       := bash
.SHELLFLAGS := -euo pipefail -c
MAKEFLAGS   += --no-print-directory

ARCH      := aarch64
ABI       := arm64-v8a
ENGINE    := podman
JAVA      ?= java

KMIS :=        \
    android12-5.10  \
    android13-5.10  \
    android13-5.15  \
    android14-5.15  \
    android14-6.1   \
    android15-6.6   \
    android16-6.12  \
    android17-6.18

DDK_WF      := .github/workflows/build.yml

DDK_IMAGE   ?= $(shell awk '/image: ghcr\.io\//{gsub(/.*image: /,"");gsub(/:.*$$/,"");print;exit}' $(DDK_WF))
# Strip the @sha256 digest before taking the release: the CI image is digest-pinned,
# and without this the local podman pull would get a bogus "...-20260828@sha256:..." tag.
# (DDK_IMAGE is unaffected: its gsub(/:.*$/,"") already cuts at the first colon.)
DDK_RELEASE ?= $(shell awk '/image: ghcr\.io\/ylarod\/ddk-min:/{sub(/@sha256:.*$$/,"");sub(/.*-/,"");print;exit}' $(DDK_WF))

# Modified 2026-11-24 (DFRoot GZIF): signing credentials are no longer required at
# parse time. They are exported into recipe environments and validated only in the
# targets that actually sign, so `make clean` (and any non-signing query) works
# without SIGN_PASS/SIGN_ALIAS. Exporting instead of interpolating into a command
# line keeps credentials out of make's echo; the shared Java writer reads them from
# the environment and never places their values in arguments or logs.
export SIGN_PASS
export SIGN_ALIAS

LKM_SRCS    := lkm/dfroot.c lkm/Makefile
LKM_KOS     := $(KMIS:%=app/src/main/jni/ko/dfroot-%.ko)
APK_SRCS    := $(wildcard app/src/main/java/df/root/*.java \
                               app/src/main/jni/*.c app/src/main/jni/*.h \
                               app/src/main/jni/*.S app/src/main/jni/*.inc \
                               app/src/main/res/layout/*.xml \
                               app/src/main/res/values/*.xml \
                               app/src/main/jniLibs/arm64-v8a/*.so) \
                   app/src/main/AndroidManifest.xml \
                   app/build.gradle.kts \
                   app/src/main/jni/CMakeLists.txt
APK   := out/DFRoot.apk


.PHONY: all clean verify-signing-permissions

all: $(APK)

keystore.jks:
	@: "$${SIGN_PASS:?SIGN_PASS is not set; export SIGN_PASS/SIGN_ALIAS for local signing, or use CI KEYSTORE secrets}"; \
	: "$${SIGN_ALIAS:?SIGN_ALIAS is not set; export SIGN_PASS/SIGN_ALIAS for local signing, or use CI KEYSTORE secrets}"; \
	LC_ALL=C.UTF-8 "$(JAVA)" scripts/WriteSigningProperties.java create-local .

# Phony verification runs on every signing build. Order-only prerequisites keep an
# up-to-date signing.properties file from being needlessly rewritten.
verify-signing-permissions: | keystore.jks
	@"$(JAVA)" scripts/WriteSigningProperties.java verify-local .

signing.properties: | verify-signing-permissions
	@: "$${SIGN_PASS:?SIGN_PASS is not set; export SIGN_PASS/SIGN_ALIAS for local signing, or use CI KEYSTORE secrets}"; \
	: "$${SIGN_ALIAS:?SIGN_ALIAS is not set; export SIGN_PASS/SIGN_ALIAS for local signing, or use CI KEYSTORE secrets}"; \
	"$(JAVA)" scripts/WriteSigningProperties.java local .

$(LKM_KOS): $(LKM_SRCS)
	@printf '\n\033[1;35m══  Building LKMs  ══\033[0m\n'; \
	for kmi in $(KMIS); do \
	    printf '\n\033[1;33m▶  PODMAN  %s\033[0m\n' "$$kmi"; \
	    $(ENGINE) run --rm --pid=host --network=none \
	        -v $(abspath .):/proj \
	        -e KMI=$$kmi \
	        $(DDK_IMAGE):$$kmi-$(DDK_RELEASE) \
	        bash -c 'set -e; \
	            cd /proj/lkm; \
	            make -j$$(nproc); \
	            mkdir -p /proj/app/src/main/jni/ko; \
	            cp dfroot.ko /proj/app/src/main/jni/ko/dfroot-$$KMI.ko; \
	            make clean; \
	            printf "\033[1;32m  ✔  DF LKM   $$KMI\033[0m\n"; \
	        ' || exit 1; \
	done; \

$(APK): signing.properties keystore.jks $(LKM_KOS) $(APK_SRCS)
	@printf '\n\033[1;36m══  DFRoot APK  ══\033[0m\n'
	@printf '\033[1;35m  → gradle assembleRelease\033[0m\n'
	./gradlew :app:assembleRelease
	mkdir -p $(dir $@)
	cp app/build/outputs/apk/release/DFRoot_*.apk $@
	@printf '\033[1;32m  ✔  DFRoot APK  →  $@\033[0m\n'

clean:
	rm -f app/src/main/jni/splicehelper
	rm -f lkm/*.o lkm/*.cmd lkm/.*.cmd lkm/*.mod lkm/*.mod.c lkm/*.lto.o lkm/modules.order lkm/Module.symvers lkm/dfroot.lds lkm/dfroot.ko
	rm -rf app/src/main/jni/ko
	rm -f .project app/.project app/.classpath
	rm -rf .settings app/.settings
	rm -rf build out app/.cxx app/build .gradle
