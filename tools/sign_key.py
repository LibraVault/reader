#!/usr/bin/env python3
"""Sign a Pro license key for testing.

Wire format (matches LicenseVerifier.kt):
    base32_no_padding( "pro:v1:<tokenId>" || '|' || raw_64_byte_signature )

Output: a single uppercase base32 string with optional '-' dashes every
8 characters for display. Both forms verify identically — the verifier
strips dashes/spaces and uppercases before decoding.

Usage:
    python3 tools/sign_key.py --private libravault_pro_private.hex --token-id 7f3a... [--dashed]

Install:
    python3 -m pip install --user cryptography
"""
from __future__ import annotations

import argparse
import base64
import sys

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey


def _base32_no_padding(data: bytes) -> str:
    return base64.b32encode(data).decode("ascii").rstrip("=")


def _add_display_dashes(s: str, every: int = 8) -> str:
    return "-".join(s[i:i + every] for i in range(0, len(s), every))


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--private", required=True, help="Path to hex-encoded private seed produced by gen_keypair.py")
    parser.add_argument("--token-id", required=True, help="UUID or opaque token id for this license (max 64 chars)")
    parser.add_argument("--dashed", action="store_true", help="Insert '-' every 8 chars for display")
    args = parser.parse_args(argv)

    with open(args.private, "r", encoding="utf-8") as f:
        private_hex = f.read().strip()
    sk = Ed25519PrivateKey.from_private_bytes(bytes.fromhex(private_hex))

    payload   = f"pro:v1:{args.token_id}".encode("utf-8")
    signature = sk.sign(payload)  # 64 raw bytes
    encoded   = _base32_no_padding(payload + b"|" + signature)

    print(encoded if not args.dashed else _add_display_dashes(encoded))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))