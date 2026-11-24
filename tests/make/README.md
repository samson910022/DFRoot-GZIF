# Make contract tests

```sh
python3 -B tests/make/test_make.py
```

Standard library only. These tests need neither Podman nor signing credentials:
they combine static Makefile/script assertions with `make -n` dry runs (the one
`make clean` case runs in a throwaway directory). They cover:

- no-credential clean/dry runs; command-line `SIGN_PASS`/`SIGN_ALIAS` rejected at
  parse time while environment-origin credentials still work and are never echoed;
- target-scoped credential exports (no global `export`);
- `scripts/ddk-image.sh` printing the exact digest-pinned CI image for the one KMI;
- the container command: no host PID/whole-repo mount, only `lkm/` and KO output
  mounted, `--cap-drop=ALL`, `no-new-privileges`, `--network=none`, output dir created;
- one KMI only (default `android13-5.15`, multi-KMI invocation rejected);
- phony APK target (Gradle always decides incremental work) with no handwritten
  Kotlin/Gradle/resource prerequisites and LKMs before signing material;
- signing files never feeding the DDK build; Phase 2 order-only verification intact;
- `OBJCOPY ?= llvm-objcopy` in `lkm/Makefile`.
