# ExpensePilot-Java: Windows local development (in progress)

## Current scope

The Java 21 Dockerfile is committed. The Groq local profile is configured
for the OpenAI-compatible endpoint `https://api.groq.com/openai/v1` and
the requested model `qwen/qwen3.8-27b`.

**This is not yet a standalone one-command application.** The existing
`docker-compose.yml` defines infrastructure only (MySQL, Redis,
RocketMQ, OpenTelemetry) and does not contain an `app` service.
The Java backend still requires an OAuth2 JWT issuer and real MCP servers
(email, drive, travel, expense). Groq provides LLM inference only.

## Base64 key loading

On the `local` Spring profile, the registered
`LocalGroqKeyEnvironmentPostProcessor` runs before Spring AI model
auto-configuration. It reads `expensepilot.groq.api-key-base64` from
`application-local.yml`, decodes it as UTF-8 Base64 and injects
`spring.ai.openai.api-key` automatically. The unencoded `GROQ_API_KEY`
environment variable takes precedence when present.

GitHub repository push protection **rejected both the cleartext key and
its Base64 representation**, so this branch does not contain an
actual credential. Base64 is reversible, not secret storage.

Temporary Windows PowerShell configuration (current shell only):

```powershell
$groqKey = Read-Host "Groq API Key"
$env:GROQ_API_KEY_BASE64 = [Convert]::ToBase64String(
    [System.Text.Encoding]::UTF8.GetBytes($groqKey))
Remove-Variable groqKey
```

If a user has access to a legitimate secret store, supplying
`GROQ_API_KEY` directly is simpler. Never commit the real key
or its reversible encoding to a public repository without accepting
that it will be publicly recoverable.

## Infrastructure on Windows

1. Install Docker Desktop and enable Linux containers.
2. Clone this repository and enter the repository folder.
3. Run `docker compose up -d` to start infrastructure containers.
4. Run `docker compose ps` to inspect status.

The application container and production MCP substitutes must be
added before the original end-to-end Windows requirement is met.
Running real integration tests (Groq + OAuth + MCP + MQ + frontend)
is still pending; do not merge this draft PR on the assumption that
all functionality works.
