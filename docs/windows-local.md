# Windows development and Docker (partial support)

Windows 10/11 with Docker Desktop (Linux containers) is required.

```powershell
git clone https://github.com/1564269628/ExpensePilot-Java.git
cd ExpensePilot-Java
docker compose up --build -d
docker compose logs -f app
```

**Not yet an end-to-end standalone stack.** Current docker-compose.yml starts MySQL, Redis, RocketMQ and OpenTelemetry but does not include the application service. Application code also requires a production JWT issuer and four working external MCP endpoints. A Groq LLM API is not a replacement for these business systems.

The local Spring profile points at Groq's OpenAI-compatible API and the requested Qwen model. GitHub push protection rejected the owner-supplied token (HTTP 409), so a token cannot be precommitted under the active repository rules. Configure the GROQ_API_KEY environment variable for future local integration.

No claim of successful build, integration test, or real Groq inference is made here. Do not merge until the missing dependencies and smoke tests are addressed.
