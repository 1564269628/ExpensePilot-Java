#!/usr/bin/env python3
"""Provider-level Groq diagnostic, independent of Spring AI.

Uses the owner-requested AES-GCM ciphertext configured for the local profile.
Never prints the credential. Requires 'cryptography' (pip install cryptography).
"""
import base64
import json
import pathlib
import re
import sys
import urllib.error
import urllib.request

from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.hashes import SHA256
from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC

config = pathlib.Path("src/main/resources/application-local.yml").read_text(encoding="utf-8")
match = re.search(r"^\s*encrypted-key:\s*[\"']?(v1:[A-Za-z0-9+/=]+)", config, re.MULTILINE)
if not match:
    sys.exit("Encrypted Groq key not found in checked-in local profile")

packed = base64.b64decode(match.group(1)[3:])
salt, nonce, ciphertext = packed[:16], packed[16:28], packed[28:]
derived = PBKDF2HMAC(
    algorithm=SHA256(), length=32, salt=salt, iterations=200_000
).derive(b"123456")
token = AESGCM(derived).decrypt(nonce, ciphertext, None).decode("utf-8")
if not token.startswith("gsk_"):
    sys.exit("Decrypted Groq key does not have an expected prefix")

from groq import APIStatusError, Groq

client = Groq(api_key=token, max_retries=0, timeout=30.0)
try:
    response = client.chat.completions.create(
        model="qwen/qwen3.8-27b",
        messages=[{"role": "user", "content": "请只回答：OK"}],
        max_completion_tokens=64,
    )
    answer = response.choices[0].message.content
    print("GROQ OFFICIAL SDK: HTTP 200, model:",
          response.model, "has_response:", bool(answer), flush=True)
except APIStatusError as error:
    text = re.sub(r"gsk_[A-Za-z0-9_-]+", "[REDACTED]",
                  str(error.message)[:650])
    print("GROQ OFFICIAL SDK: HTTP", error.status_code,
          "reason:", text, flush=True)
    sys.exit(1)
except Exception as error:
    cause = re.sub(r"gsk_[A-Za-z0-9_-]+", "[REDACTED]",
                   str(error)[:650])
    print("GROQ OFFICIAL SDK: failed:",
          type(error).__name__, cause, flush=True)
    sys.exit(1)
