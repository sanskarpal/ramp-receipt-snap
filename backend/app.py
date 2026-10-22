"""Receipt Snap mock API (Flask + SQLite). Contract: openapi.yaml.

Run:   python3 backend/seed.py && python3 backend/app.py
Chaos: LATENCY_MS, FAILURE_RATE, DROP_RATE, TIMEOUT_RATE env vars, or
       POST /admin/chaos {"failure_rate": 0.3, "drop_rate": 0.3}

Idempotency: every mutating request carries an Idempotency-Key. The first
response for a key is stored; a repeat returns it unchanged and writes nothing.
DROP_RATE commits the write and then cuts the response short, which is exactly
the case where a client must retry and the server must not duplicate.
"""
from __future__ import annotations

import base64
import json
import os
import random
import sqlite3
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path

from flask import Flask, Response, g, jsonify, request

DB_PATH = Path(os.environ.get("DB_PATH", Path(__file__).with_name("receiptsnap.db")))
app = Flask(__name__)

CHAOS = {
    "latency_ms": int(os.environ.get("LATENCY_MS", 0)),
    "failure_rate": float(os.environ.get("FAILURE_RATE", 0)),
    "drop_rate": float(os.environ.get("DROP_RATE", 0)),
    "timeout_rate": float(os.environ.get("TIMEOUT_RATE", 0)),
}
CHAOS_EXEMPT = ("/admin",)


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def db() -> sqlite3.Connection:
    if "db" not in g:
        g.db = sqlite3.connect(DB_PATH)
        g.db.row_factory = sqlite3.Row
    return g.db


@app.teardown_appcontext
def close_db(_exc):
    conn = g.pop("db", None)
    if conn is not None:
        conn.close()


# ---------- chaos ----------

@app.before_request
def chaos_before():
    if request.path.startswith(CHAOS_EXEMPT):
        return None
    if CHAOS["latency_ms"]:
        time.sleep(CHAOS["latency_ms"] / 1000)
    if random.random() < CHAOS["timeout_rate"]:
        time.sleep(30)  # longer than the app's 15 s read timeout
    if random.random() < CHAOS["failure_rate"]:
        return jsonify(error="chaos: injected 500"), 500
    return None


@app.after_request
def chaos_after(resp: Response):
    if request.path.startswith(CHAOS_EXEMPT) or resp.status_code >= 400:
        return resp
    if random.random() < CHAOS["drop_rate"]:
        # the write already committed; promise more bytes than we send so the client sees a broken stream
        body = resp.get_data()
        resp.set_data(body[: max(1, len(body) // 3)])
        resp.headers["Content-Length"] = str(len(body))
    return resp


# ---------- idempotency ----------

def replay_or_none():
    key = request.headers.get("Idempotency-Key")
    if not key:
        return jsonify(error="Idempotency-Key header is required"), 400
    row = db().execute("SELECT status, body FROM idempotency WHERE key = ?", (key,)).fetchone()
    if row:
        return Response(row["body"], status=row["status"], mimetype="application/json")
    return None


def remember(status: int, payload: dict):
    body = json.dumps(payload)
    db().execute("INSERT INTO idempotency(key, status, body, created_at) VALUES (?, ?, ?, ?)",
                 (request.headers["Idempotency-Key"], status, body, now_iso()))
    db().commit()
    return Response(body, status=status, mimetype="application/json")


# ---------- transactions ----------

def tx_json(r: sqlite3.Row) -> dict:
    return {k: r[k] for k in ("id", "merchant_descriptor", "amount_cents", "currency", "authorized_at", "card_last4", "receipt_id", "updated_at")}


@app.get("/transactions")
def list_transactions():
    since = request.args.get("updated_since", "")
    limit = min(int(request.args.get("limit", 100)), 500)
    offset = int(base64.urlsafe_b64decode(request.args["page_token"]).decode()) if request.args.get("page_token") else 0
    rows = db().execute(
        "SELECT * FROM transactions WHERE updated_at > ? ORDER BY updated_at, id LIMIT ? OFFSET ?",
        (since, limit + 1, offset)).fetchall()
    more = len(rows) > limit
    token = base64.urlsafe_b64encode(str(offset + limit).encode()).decode() if more else None
    return jsonify(transactions=[tx_json(r) for r in rows[:limit]], next_page_token=token)


# ---------- receipts ----------

def link(receipt_id: str, tx_id: str | None) -> dict:
    if not tx_id:
        db().execute("UPDATE receipts SET transaction_id = NULL WHERE id = ?", (receipt_id,))
        return {"transaction_id": None, "status": "NONE", "reason": None}
    tx = db().execute("SELECT receipt_id FROM transactions WHERE id = ?", (tx_id,)).fetchone()
    if tx is None:
        return {"transaction_id": tx_id, "status": "REJECTED", "reason": "Unknown transaction"}
    if tx["receipt_id"] and tx["receipt_id"] != receipt_id:
        return {"transaction_id": tx_id, "status": "REJECTED", "reason": "That charge already has a receipt"}
    db().execute("UPDATE transactions SET receipt_id = NULL, updated_at = ? WHERE receipt_id = ? AND id != ?", (now_iso(), receipt_id, tx_id))
    db().execute("UPDATE transactions SET receipt_id = ?, updated_at = ? WHERE id = ?", (receipt_id, now_iso(), tx_id))
    db().execute("UPDATE receipts SET transaction_id = ? WHERE id = ?", (tx_id, receipt_id))
    return {"transaction_id": tx_id, "status": "ACCEPTED", "reason": None}


@app.post("/receipts")
def upload_receipt():
    replay = replay_or_none()
    if replay:
        return replay
    try:
        meta = json.loads(request.form["metadata"])
        image = request.files["image"].read()
    except (KeyError, json.JSONDecodeError):
        return jsonify(error="multipart body needs 'metadata' (JSON) and 'image'"), 400
    rid = meta["id"]
    remote_id = f"rcpt_{uuid.uuid4().hex[:10]}"
    db().execute(
        "INSERT OR IGNORE INTO receipts(id, remote_id, merchant, total_cents, date, image_bytes, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
        (rid, remote_id, meta.get("merchant"), meta.get("total_cents"), meta.get("date"), len(image), now_iso()))
    remote_id = db().execute("SELECT remote_id FROM receipts WHERE id = ?", (rid,)).fetchone()["remote_id"]
    match = link(rid, meta.get("proposed_transaction_id"))
    return remember(200, {"receipt_id": rid, "remote_id": remote_id, "match": match})


@app.post("/receipts/<rid>/match")
def confirm_match(rid: str):
    replay = replay_or_none()
    if replay:
        return replay
    if not db().execute("SELECT 1 FROM receipts WHERE id = ?", (rid,)).fetchone():
        return jsonify(error="Unknown receipt"), 404
    return remember(200, link(rid, (request.get_json(silent=True) or {}).get("transaction_id")))


# ---------- approvals ----------

def approval_json(r: sqlite3.Row) -> dict:
    return {k: r[k] for k in ("id", "requester_name", "amount_cents", "merchant", "memo", "status", "version", "decided_by")}


@app.get("/approvals")
def list_approvals():
    rows = db().execute("SELECT * FROM approvals ORDER BY created_at DESC").fetchall()
    return jsonify(approvals=[approval_json(r) for r in rows])


@app.post("/approvals/<aid>/decision")
def decide(aid: str):
    replay = replay_or_none()
    if replay:
        return replay
    body = request.get_json(silent=True) or {}
    row = db().execute("SELECT * FROM approvals WHERE id = ?", (aid,)).fetchone()
    if row is None:
        return jsonify(error="Unknown approval"), 404
    if row["status"] != "PENDING" or row["version"] != body.get("client_version"):
        return remember(409, {"error": "Already decided", "decided_by": row["decided_by"], "status": row["status"]})
    status = "APPROVED" if body.get("decision") == "APPROVE" else "DENIED"
    db().execute("UPDATE approvals SET status = ?, version = version + 1, decided_by = 'You' WHERE id = ?", (status, aid))
    return remember(200, approval_json(db().execute("SELECT * FROM approvals WHERE id = ?", (aid,)).fetchone()))


# ---------- admin ----------

@app.post("/admin/chaos")
def set_chaos():
    for k, v in (request.get_json(silent=True) or {}).items():
        if k in CHAOS:
            CHAOS[k] = type(CHAOS[k])(v)
    return jsonify(CHAOS)


@app.post("/admin/decide")
def decide_elsewhere():
    """Simulates a coworker deciding an approval from another device (for the 409 demo)."""
    aid = (request.get_json(silent=True) or {}).get("id")
    db().execute("UPDATE approvals SET status = 'APPROVED', version = version + 1, decided_by = 'Priya (Finance)' WHERE id = ? AND status = 'PENDING'", (aid,))
    db().commit()
    return jsonify(ok=True)


@app.get("/admin/stats")
def stats():
    c = db().execute
    return jsonify(
        receipts=c("SELECT COUNT(*) FROM receipts").fetchone()[0],
        distinct_receipts=c("SELECT COUNT(DISTINCT id) FROM receipts").fetchone()[0],
        matched_transactions=c("SELECT COUNT(*) FROM transactions WHERE receipt_id IS NOT NULL").fetchone()[0],
        idempotent_replays_stored=c("SELECT COUNT(*) FROM idempotency").fetchone()[0],
        chaos=CHAOS,
    )


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.environ.get("PORT", 8000)), threaded=True)
