SHELL       := bash
.SHELLFLAGS := -euo pipefail -c
MAKEFLAGS   += --no-print-directory

ARCH      := aarch64
ABI       := arm64-v8a
ENGINE    := podman
JAVA      ?= java

# This GZIF fork embeds exactly one KO (app/src/main/jni/CMakeLists.txt) and CI's
# build-lkms matrix has exactly one entry.  Validate the RAW, *unexpanded* value
# before it reaches any $(shell) or recipe: only the empty value (the explicit
# no-DDK escape hatch for no-Podman runs) or the exact literal android13-5.15 is
# accepted.  The raw text is deliberately never echoed in the error, so an
# injected shell fragment cannot be printed or executed.
RAW_KMIS := $(value KMIS)
ifneq ($(RAW_KMIS),)
ifneq ($(RAW_KMIS),android13-5.15)
$(error Invalid KMIS: only empty (skip DDK) or the exact literal android13-5.15 is supported)
endif
endif
ifeq ($(origin KMIS),undefined)
KMIS := android13-5.15
else
KMIS := $(RAW_KMIS)
endif

# Credentials on the command line are echoed by make, stored in shell history and
# visible in process listings.  Reject that origin at parse time, including an
# assignment smuggled through MAKEFLAGS/MAKEOVERRIDES.  The environment is the
# only supported source.  Patterns are inlined; no overrideable helper variable.
ifneq ($(filter command line,$(origin SIGN_PASS) $(origin SIGN_ALIAS)),)
$(error SIGN_PASS/SIGN_ALIAS must not be passed on the make command line; export them in the environment instead (e.g. export SIGN_PASS=... SIGN_ALIAS=...; make ...))
endif
ifneq ($(findstring SIGN_PASS,$(MAKEOVERRIDES) $(MAKEFLAGS)),)
$(error SIGN_PASS must not be passed on the make command line; export it in the environment instead)
endif
ifneq ($(findstring SIGN_ALIAS,$(MAKEOVERRIDES) $(MAKEFLAGS)),)
$(error SIGN_ALIAS must not be passed on the make command line; export it in the environment instead)
endif

# The exact digest-pinned image CI uses is the single source of truth.  It is
# never caller-supplied: reject command-line/environment origins (including via
# MAKEFLAGS/MAKEOVERRIDES), then derive it from the validated KMIS above.
ifneq ($(filter command line environment override file,$(origin DDK_IMAGE)),)
$(error DDK_IMAGE must not be set; it is derived from .github/workflows/build.yml)
endif
ifneq ($(findstring DDK_IMAGE,$(MAKEOVERRIDES) $(MAKEFLAGS)),)
$(error DDK_IMAGE must not be set; it is derived from .github/workflows/build.yml)
endif
DDK_IMAGE ?= $(if $(strip $(KMIS)),$(shell bash scripts/ddk-image.sh $(KMIS)),)

# Credentials are exported only into signing recipes (and inherited by their
# prerequisites), never globally: clean, dry runs and LKM/DDK targets do not
# need them.  Verification stays phony so it runs on every signing build, while
# order-only prerequisites prevent needless rewriting of up-to-date material.
keystore.jks signing.properties verify-signing-permissions: export SIGN_PASS := $(SIGN_PASS)
keystore.jks signing.properties verify-signing-permissions: export SIGN_ALIAS := $(SIGN_ALIAS)

LKM_SRCS    := lkm/dfroot.c lkm/Makefile
LKM_KO_DIR  := app/src/main/jni/ko
LKM_KOS     := $(KMIS:%=$(LKM_KO_DIR)/dfroot-%.ko)
APK         := out/DFRoot.apk

.PHONY: all clean verify-signing-permissions $(APK)

all: $(APK)

keystore.jks:
	@: "$${SIGN_PASS:?SIGN_PASS is not set; export SIGN_PASS/SIGN_ALIAS for local signing, or use CI KEYSTORE secrets}"; \
	: "$${SIGN_ALIAS:?SIGN_ALIAS is not set; export SIGN_PASS/SIGN_ALIAS for local signing, or use CI KEYSTORE secrets}"; \
	LC_ALL=C.UTF-8 "$(JAVA)" scripts/WriteSigningProperties.java create-local .

verify-signing-permissions: | keystore.jks
	@"$(JAVA)" scripts/WriteSigningProperties.java verify-local .

signing.properties: | verify-signing-permissions
	@: "$${SIGN_PASS:?SIGN_PASS is not set; export SIGN_PASS/SIGN_ALIAS for local signing, or use CI KEYSTORE secrets}"; \
	: "$${SIGN_ALIAS:?SIGN_ALIAS is not set; export SIGN_PASS/SIGN_ALIAS for local signing, or use CI KEYSTORE secrets}"; \
	"$(JAVA)" scripts/WriteSigningProperties.java local .

# One static pattern rule: the stem ($*) is built exactly once.  There is no
# all-KMI loop (the old recipe rebuilt eight KMIs to produce one consumed KO).
# The container receives only lkm/ and the KO output directory -- never the whole
# repository, no host PID namespace and no network, and it drops all capabilities.
$(LKM_KOS): $(LKM_KO_DIR)/dfroot-%.ko: $(LKM_SRCS)
	@mkdir -p $(LKM_KO_DIR)
	@printf '\n\033[1;33m▶  PODMAN  $*\033[0m\n'
	@$(ENGINE) run --rm --network=none \
	    --cap-drop=ALL \
	    --security-opt=no-new-privileges \
	    -v $(abspath lkm):/proj/lkm \
	    -v $(abspath $(LKM_KO_DIR)):/proj/ko \
	    -e KMI=$* \
	    $(DDK_IMAGE) \
	    bash -c 'set -e; \
	        cd /proj/lkm; \
	        make -j$$(nproc); \
	        cp dfroot.ko /proj/ko/dfroot-$$KMI.ko; \
	        make clean; \
	        printf "\033[1;32m  ✔  DF LKM   $$KMI\033[0m\n"; \
	    '

# Phony so Gradle always decides incremental work itself: the incomplete
# source-file wildcard graph is intentionally gone (Kotlin/Gradle config and
# resource changes are Gradle's business, not Makefile prerequisites).  LKMs are
# listed before signing material, and this target is serial even under `make -j`
# so the DDK build really happens first.
.NOTPARALLEL: $(APK)
$(APK): $(LKM_KOS) signing.properties keystore.jks
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
