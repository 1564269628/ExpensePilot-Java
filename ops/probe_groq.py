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

payload = json.dumps({
    "model": "qwen/qwen3.8-27b",
    "messages": [{"role": "user", "content": "请只回答：OK"}],
    "max_completion_tokens": 64,
}).encode("utf-8")
req = urllib.request.Request(
    "https://api.groq.com/openai/v1/chat/completions",
    data=payload,
    method="POST",
    headers={
        "Authorization": "Bearer " + token,
        "Content-Type": "application/json",
    },
)
try:
    with urllib.request.urlopen(req, timeout=45) as res:
        body = json.load(res)
        answer = (body.get("choices") or [{}])[0].get("message", {}).get("content")
        print("GROQ DIRECT API: HTTP", res.status,
              "model:", body.get("model"),
              "has_response:", bool(answer), flush=True)
        if not answer:
            print("Note: API returned a completion without visible content")
except urllib.error.HTTPError as error:
    raw = error.read().decode("utf-8", errors="replace")[:600]
    raw = re.sub(r"gsk_[A-Za-z0-9_-]+", "[REDACTED]", raw)
    print("GROQ DIRECT API: HTTP", error.code, "reason:", raw, flush=True)
    sys.exit(1)
except Exception as error:
    cause = re.sub(r"gsk_[A-Za-z0-9_-]+", "[REDACTED]", str(error))
    print("GROQ DIRECT API: failed:", type(error).__name__, cause[:600], flush=True)
    sys.exit(1)
