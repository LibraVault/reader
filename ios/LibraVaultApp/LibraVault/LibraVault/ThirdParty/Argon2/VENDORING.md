# Vendored: Argon2 reference implementation

Source: https://github.com/P-H-C/phc-winner-argon2
Pinned commit: `62358ba2123abd17fccf2a108a301d4b52c01a7c` (tag `20190702`, the
project's last tagged release)

Vendored directly (not via a third-party Swift wrapper package) per the #200
scoping decision — see the issue for the rationale: wrapper packages like
`swift-argon2` just repackage this same C source, so vendoring it ourselves
cuts out an extra maintainer-trust hop without adding any real risk, since
this *is* the canonical upstream source either way.

## What's included

Only the **reference** (`ref.c`) backend, not the SSE2-optimized `opt.c`
backend — `opt.c` requires x86 SIMD intrinsics that don't exist on arm64, and
this library's Argon2id calls happen once per PIN unlock/vault creation, not
in a hot loop, so the reference backend's portability is worth far more here
than `opt.c`'s speed would be.

```
include/argon2.h        — public API (argon2id_hash_raw, error codes, etc.)
src/argon2.c             — top-level API implementation
src/core.c / core.h       — core Argon2 algorithm
src/ref.c                 — reference (portable, non-SIMD) fill_segment
src/encoding.c / .h       — PHC string format encoding (unused by us — we call
                            *_hash_raw, not *_hash_encoded — but kept in since
                            argon2.c's translation unit references it)
src/thread.c / .h         — pthread wrapper (parallelism == 1 for our usage,
                            so this compiles in but isn't exercised)
src/blake2/*              — BLAKE2b, Argon2's internal hash primitive
```

Not vendored: `bench.c`, `genkat.c`/`.h`, `run.c`, `test.c` — upstream's own
CLI/benchmark/test-vector tooling, not part of the library.

## License

Dual CC0-1.0 / Apache-2.0 (see `LICENSE`), both compatible with LibraVault's
GPL-3.0 (same reasoning as the `SHERPA_ONNX_SETUP.md` espeak-ng precedent —
a permissively-licensed dependency statically linked into a GPL-3.0 whole).

## Updating

To move to a newer upstream commit: re-fetch the same file list from the new
commit's raw.githubusercontent.com tree, diff against what's here, and update
the pinned commit above. Don't hand-edit these files directly — any local fix
would be silently lost on the next re-vendor.
