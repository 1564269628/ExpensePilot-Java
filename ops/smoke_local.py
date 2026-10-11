#!/usr/bin/env python3
"""Real HTTP end-to-end smoke test against the Windows-compatible local stack.

Uses Groq through the running Java backend. No mocked model responses.
Does not print credential material.
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE = os.environ.get("EXPENSEPILOT_BASE_URL", "http://127.0.0.1:8080")
TIMEOUT = 90


def request(method, path, payload=None, headers=None):
    body = json.dumps(payload).encode("utf-8") if payload is not None else None
    req = urllib.request.Request(
        BASE + path, data=body, method=method,
        headers={"Content-Type": "application/json", **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else {}
    except urllib.error.HTTPError as error:
        raw = error.read().decode("utf-8", errors="replace")
        try:
            message = json.loads(raw)
        except ValueError:
            message = raw[:500]
        return error.code, message


def check(condition, message):
    if not condition:
        raise AssertionError(message)
    print("PASS:", message, flush=True)


def get(path):
    return request("GET", path)


def post(path, data=None, headers=None):
    return request("POST", path, data, headers)


def wait_for_state(task_id, desired, seconds=220):
    started = time.monotonic()
    last = None
    while time.monotonic() - started < seconds:
        status, data = get("/api/v1/expense-tasks/" + str(task_id))
        assert status == 200, (status, data)
        state = (data["status"], data.get("currentNode"))
        if state != last:
            print("task", task_id, "transition", state, flush=True)
            last = state
        if data["status"] in desired:
            return data
        if data["status"] == "MANUAL_TAKEOVER":
            raise AssertionError("Agent permanently failed: " + str(data.get("lastError")))
        time.sleep(3)
    raise AssertionError("Timed out waiting for " + str(desired) + ", final=" + str(last))


def main():
    status, payload = get("/")
    # Homepage is HTML, so the JSON helper cannot be used for this endpoint.
    # Check the health and API endpoints directly instead.
    status, health = get("/actuator/health")
    check(status == 200 and health.get("status") == "UP",
          "Spring Boot health is UP")

    status, seed = post("/api/v1/local/demo-seed", {})
    check(status == 200 and seed.get("sample") is True,
          "Demo trip and invoice data are durably seeded")

    status, documents = get("/api/v1/local/documents")
    check(status == 200 and len(documents) >= 2,
          "Stored email/drive document metadata available")

    status, trips = get("/api/v1/local/trips")
    check(status == 200 and len(trips) >= 1,
          "Stored trip data available")

    status, policies = get("/api/v1/local/policies")
    check(status == 200 and len(policies) >= 4,
          "Deterministic local policy rules available")

    status, model = post("/api/v1/local/groq-check", {})
    check(status == 200 and model.get("live") is True
          and bool(model.get("response")), "Real Groq API call returned a response")

    idem = str(uuid.uuid4())
    request_text = seed["suggestedRequest"]
    status, created = post("/api/v1/expense-tasks",
                           {"requestText": request_text},
                           {"Idempotency-Key": idem})
    check(status == 202 and created.get("taskId"),
          "Agent task accepted asynchronously")
    task_id = created["taskId"]

    status, duplicate = post("/api/v1/expense-tasks",
                             {"requestText": request_text},
                             {"Idempotency-Key": idem})
    check(status == 202 and duplicate.get("taskId") == task_id,
          "Idempotency-Key does not create duplicate tasks")

    status, conflict = post("/api/v1/expense-tasks",
                            {"requestText": "other text"},
                            {"Idempotency-Key": idem})
    check(status == 409, "Reusing the idempotency key with new text is rejected")

    current = wait_for_state(task_id,
                             {"WAITING_APPROVAL", "WAITING_MATERIAL",
                              "WAITING_INPUT", "SUCCEEDED"})
    if current["status"] == "WAITING_INPUT":
        status, _ = post("/api/v1/expense-tasks/" + str(task_id) + "/clarification",
                         {"city": "上海", "startDate": "2026-10-01",
                          "endDate": "2026-10-03"})
        check(status == 202, "Planner clarification resumes Graph")
        current = wait_for_state(task_id,
                                 {"WAITING_APPROVAL", "WAITING_MATERIAL", "SUCCEEDED"})
    if current["status"] == "WAITING_MATERIAL":
        refs = [d["documentRef"] for d in documents]
        status, _ = post("/api/v1/expense-tasks/" + str(task_id) + "/supplement",
                         {"materialRefs": refs})
        check(status == 202, "Supplement API resumes Graph from checkpoint")
        current = wait_for_state(task_id, {"WAITING_APPROVAL", "SUCCEEDED"})
    for _ in range(2):
        if current["status"] == "SUCCEEDED":
            break
        check(current["status"] == "WAITING_APPROVAL",
              "Workflow stops at explicit human approval")
        operation = ("POLICY_EXCEPTION" if current.get("currentNode")
                     == "humanApproval" else "SUBMIT_REPORT")
        status, decided = post(
            "/api/v1/expense-tasks/" + str(task_id)
            + "/approval/approve?operationType=" + operation
            + "&comment=verified-local-workflow")
        check(status == 200 and decided.get("status") == "APPROVED",
              "Approval decision persisted: " + operation)
        current = wait_for_state(task_id, {"WAITING_APPROVAL", "SUCCEEDED"})
    check(current["status"] == "SUCCEEDED", "Graph and local expense submission succeeded")

    status, submissions = get("/api/v1/local/submissions")
    check(status == 200 and len(submissions) > 0,
          "Local expense receipt stored with unique external business number")

    for _ in range(40):
        status, notifications = get("/api/v1/local/notifications")
        if status == 200 and notifications:
            break
        time.sleep(3)
    check(status == 200 and len(notifications) > 0,
          "RocketMQ/Outbox notification processed and recorded")
    print("END-TO-END RESULT: PASS", flush=True)


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print("END-TO-END RESULT: FAIL", type(exc).__name__, str(exc), file=sys.stderr)
        sys.exit(1)
