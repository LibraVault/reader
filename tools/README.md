# `tools/` — LibraVault Pro key tooling

Scripts that operate on the Ed25519 license-key format consumed by
`core/licensing/.../LicenseVerifier.kt`.

## Why

The verifier is offline-only. There is no API that issues keys for you.
A maintainer must:

1. Generate a keypair (`gen_keypair.py`).
2. Embed the public key in `LicenseVerifier.PUBLIC_KEY_B64`.
3. Sign license keys for paying supporters (`sign_key.py`).

Without these scripts a future contributor cannot:
- Generate a fresh keypair after rotation.
- Produce test vectors for the verifier unit tests.
- Verify the verifier against a known-good key.

## Install

```sh
python3 -m pip install --user cryptography
```

Python 3.9+ recommended; `cryptography>=3.0` is the only third-party dep.

## Quick start

```sh
# Generate a keypair. Writes libravault_pro_private.hex (mode 0600) and
# prints the public key to stdout.
python3 tools/gen_keypair.py --out libravault_pro_private.hex

# Paste the printed public key into LicenseVerifier.PUBLIC_KEY_B64 and rebuild.

# Issue a license key for token id 7f3a...
python3 tools/sign_key.py \
    --private libravault_pro_private.hex \
    --token-id 7f3a0000-0000-0000-0000-000000000001
```

## Security

- `libravault_pro_private.hex` is a 32-byte raw Ed25519 seed — the
  ability to forge license keys. **Never commit it. Never copy it to a
  device. Never email it.**
- Rotate by generating a new keypair, shipping a new app release with
  the new public key, and re-signing all outstanding license tokens
  with the new private key. The verifier has no version bump because
  the v1 payload format does not change.
- The private seed is equivalent to the private key. Treat it like a
  root CA private key.

## License-key wire format

```
base32_no_padding( payload || '|' || signature )
payload   = "pro:v1:<tokenId>"           UTF-8
signature = raw 64-byte Ed25519 signature of payload
```

The verifier accepts display dashes (e.g. `XXXX-XXXX-XXXX-…`) by
stripping non-alphanumerics before decoding.