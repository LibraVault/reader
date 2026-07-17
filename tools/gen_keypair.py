#!/usr/bin/env python3
"""Generate an Ed25519 keypair for LibraVault Pro license keys.

Output:
  - private seed (32 bytes hex) — keep secret, never commit
  - public key (32 bytes base64, NO_WRAP) — paste into LicenseVerifier.PUBLIC_KEY_B64

Usage:
    python3 tools/gen_keypair.py [--out PRIVATE_KEY_FILE]

The private seed is what tools/sign_key.py needs to issue license keys.
The public key is what the Android app embeds in LicenseVerifier.PUBLIC_KEY_B64.

Install:
    python3 -m pip install --user cryptography
"""
from __future__ import annotations

import argparse
import base64
import os
import sys

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives import serialization


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--out",
        default="libravault_pro_private.hex",
        help="Where to write the 32-byte private seed (hex-encoded). Default: libravault_pro_private.hex",
    )
    args = parser.parse_args(argv)

    sk = Ed25519PrivateKey.generate()
    private_bytes = sk.private_bytes(
        encoding=serialization.Encoding.Raw,
        format=serialization.PrivateFormat.Raw,
        encryption_algorithm=serialization.NoEncryption(),
    )
    public_bytes = sk.public_key().public_bytes(
        encoding=serialization.Encoding.Raw,
        format=serialization.PublicFormat.Raw,
    )

    private_hex = private_bytes.hex()
    public_b64  = base64.b64encode(public_bytes).decode("ascii").rstrip("=")

    if os.path.exists(args.out):
        print(f"refusing to overwrite existing {args.out}", file=sys.stderr)
        return 1
    with open(args.out, "w", encoding="utf-8") as f:
        f.write(private_hex + "\n")
    os.chmod(args.out, 0o600)

    print(f"private seed  → {args.out} (mode 0600)")
    print(f"public  b64   → {public_b64}")
    print()
    print("Paste the public key into:")
    print("  core/licensing/src/main/kotlin/xyz/libravault/core/licensing/LicenseVerifier.kt")
    print("  const val PUBLIC_KEY_B64 = \"<value above>\"")
    print()
    print("Sign test keys with:")
    print(f"  python3 tools/sign_key.py --private {args.out} --token-id <uuid>")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))