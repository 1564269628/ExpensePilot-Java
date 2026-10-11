#!/usr/bin/env python3
"""Exercise the real local Agent's human-in-the-loop exceptional branches.

Runs after smoke_local.py in the same full Docker test environment.
No model mocks: every new Agent task invokes the configured live Groq model.
"""
import uuid

from smoke_local import check, get, post, wait_for_state


def create_task(request_text):
    status, created = post(
        "/api/v1/expense-tasks",
        {"requestText": request_text},
        {"Idempotency-Key": str(uuid.uuid4())},
    )
    check(status == 202 and isinstance(created.get("taskId"), str),
          "Branch Agent task created with lossless task ID")
    return created["taskId"]


def ensure_scope(task_id, city, start, end):
    state = wait_for_state(task_id, {
        "WAITING_INPUT", "WAITING_MATERIAL", "WAITING_APPROVAL", "SUCCEEDED"
    }, seconds=180)
    if state["status"] == "WAITING_INPUT":
        status, _ = post(
            "/api/v1/expense-tasks/" + task_id + "/clarification",
            {"city": city, "startDate": start, "endDate": end})
        check(status == 202, "Human trip clarification saved into Graph checkpoint")
        state = wait_for_state(task_id, {
            "WAITING_MATERIAL", "WAITING_APPROVAL", "SUCCEEDED"
        }, seconds=180)
    return state


def assert_no_extra_submissions(expected):
    status, rows = get("/api/v1/local/submissions")
    check(status == 200 and len(rows) == expected,
          "Rejected tasks did not cause an external expense side effect")


def missing_material_then_reject():
    before_status, submissions = get("/api/v1/local/submissions")
    assert before_status == 200
    count = len(submissions)
    city, start, end = "南京", "2026-11-05", "2026-11-07"
    task_id = create_task(
        "请处理2026年11月5日至2026年11月7日南京出差的费用报销。")
    state = ensure_scope(task_id, city, start, end)
    check(state["status"] == "WAITING_MATERIAL",
          "Missing receipts and trip stop Graph at human material checkpoint")

    doc_status, doc = post("/api/v1/local/documents", {
        "sourceType": "EMAIL", "fileName": "nanjing-supplement.pdf",
        "category": "HOTEL", "amount": 400,
        "city": city, "issuedOn": "2026-11-06"
    })
    trip_status, _ = post("/api/v1/local/trips", {
        "city": city, "startDate": start, "endDate": end,
        "purpose": "Human material supplement acceptance scenario"
    })
    check(doc_status == 200 and trip_status == 200,
          "A human can supply a missing receipt and travel record")

    status, _ = post(
        "/api/v1/expense-tasks/" + task_id + "/supplement",
        {"materialRefs": [doc["documentRef"]]})
    check(status == 202, "Real supplement API resumed Graph")
    state = wait_for_state(task_id, {"WAITING_APPROVAL"}, seconds=180)
    check(state.get("currentNode") == "submitApproval",
          "Supplemented facts flowed through material and policy checks")
    status, response = post(
        "/api/v1/expense-tasks/" + task_id
        + "/approval/reject?operationType=SUBMIT_REPORT&comment=branch-test")
    check(status == 200 and response.get("status") == "REJECTED",
          "Submit approval rejection recorded")
    wait_for_state(task_id, {"REJECTED"}, seconds=120)
    assert_no_extra_submissions(count)


def policy_violation_then_reject():
    before_status, submissions = get("/api/v1/local/submissions")
    assert before_status == 200
    count = len(submissions)
    city, start, end = "合肥", "2026-11-10", "2026-11-12"
    status, doc = post("/api/v1/local/documents", {
        "sourceType": "DRIVE", "fileName": "hefei-expensive-hotel.pdf",
        "category": "HOTEL", "amount": 850,
        "city": city, "issuedOn": "2026-11-11"
    })
    status2, _ = post("/api/v1/local/trips", {
        "city": city, "startDate": start, "endDate": end,
        "purpose": "Policy exception acceptance scenario"
    })
    check(status == 200 and status2 == 200 and bool(doc.get("documentRef")),
          "Over-policy hotel invoice and trip persisted")
    task_id = create_task(
        "请处理2026年11月10日至2026年11月12日合肥出差的费用报销。")
    state = ensure_scope(task_id, city, start, end)
    check(state["status"] == "WAITING_APPROVAL"
          and state.get("currentNode") == "humanApproval",
          "Over-limit hotel invoice requires policy exception approval")
    status, result = post(
        "/api/v1/expense-tasks/" + task_id
        + "/approval/reject?operationType=POLICY_EXCEPTION&comment=policy-reject")
    check(status == 200 and result.get("status") == "REJECTED",
          "Policy rejection recorded before any expense submission")
    wait_for_state(task_id, {"REJECTED"}, seconds=120)
    assert_no_extra_submissions(count)


if __name__ == "__main__":
    missing_material_then_reject()
    policy_violation_then_reject()
    print("GRAPH BRANCH END-TO-END RESULT: PASS", flush=True)
