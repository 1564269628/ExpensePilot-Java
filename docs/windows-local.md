# ExpensePilot-Java: Windows and Groq local profile (work in progress)

## Groq credential loading: no manual API configuration

The owner-selected local profile now contains an AES-256-GCM encrypted
Groq API key and the password `123456`. Both values are versioned in
`src/main/resources/application-local.yml`.

On startup, Spring Boot loads the registered
`LocalGroqKeyEnvironmentPostProcessor`, which reads
`expensepilot.groq.encrypted-key`, derives an AES key using
PBKDF2-HMAC-SHA256 (200,000 iterations) and
`expensepilot.groq.decryption-password`, decrypts the ciphertext
and injects `spring.ai.openai.api-key` before the Spring AI client
is configured.

The model endpoint is `https://api.groq.com/openai/v1` and the
model setting is `qwen/qwen3.8-27b`.
No API key input is necessary to **decrypt the key** in this checked-in
`local` profile. Setting `GROQ_API_KEY` still overrides the embedded
ciphertext (for credential rotation without source changes).

**Security: Password 123456 and ciphertext are both publicly readable,
so anyone can recover and use the owner's Groq account token. This is
obfuscation rather than real security. The owner requested this tradeoff
for local development; do not reuse it for production. Rotate/revoke the
token before sharing this repository with untrusted users or deploying
an internet-facing instance.**

## Java cryptography verification

A standalone Java 21 smoke test confirmed that the checked-in AES-GCM
ciphertext decrypts to the original key using password `123456`.
Decrypting with a wrong password fails GCM authentication.

Unit test source was committed at
`src/test/java/com/expensepilot/config/LocalGroqKeyEnvironmentPostProcessorTest.java`.
The complete Maven test suite and the Spring Boot application startup
have **not** been executed successfully in this environment.

## Windows Docker status

Windows 10/11 with Docker Desktop Linux containers can run infrastructure:

```powershell
git clone https://github.com/1564269628/ExpensePilot-Java.git
cd ExpensePilot-Java
docker compose up -d
docker compose ps
```

**Important:** `docker-compose.yml` currently only defines MySQL,
Redis, RocketMQ and OpenTelemetry. The application service and frontend
are not yet orchestrated. The existing Java backend still requires
an OAuth2 JWT issuer plus email, drive, travel, and expense MCP servers.
A live Groq model API is not a substitute for those business systems.
Do not claim end-to-end Windows functionality or merge the draft PR
until the missing services, user interface, and integration tests are done.
