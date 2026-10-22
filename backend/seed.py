"""Creates receiptsnap.db: ~200 card transactions with messy descriptors and
near-duplicate decoys, the charges that match the bundled sample receipts,
and a few pending approvals. All data is fictional.

    python3 backend/seed.py            # rebuild from scratch
"""
from __future__ import annotations

import json
import random
import sqlite3
from datetime import datetime, time, timedelta, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

HERE = Path(__file__).parent
DB = HERE / "receiptsnap.db"
PT = ZoneInfo("America/Los_Angeles")

SCHEMA = """
CREATE TABLE transactions (id TEXT PRIMARY KEY, merchant_descriptor TEXT, amount_cents INTEGER, currency TEXT,
  authorized_at TEXT, card_last4 TEXT, receipt_id TEXT, updated_at TEXT);
CREATE TABLE receipts (id TEXT PRIMARY KEY, remote_id TEXT, merchant TEXT, total_cents INTEGER, date TEXT,
  transaction_id TEXT, image_bytes INTEGER, created_at TEXT);
CREATE TABLE approvals (id TEXT PRIMARY KEY, requester_name TEXT, amount_cents INTEGER, merchant TEXT, memo TEXT,
  status TEXT, version INTEGER, decided_by TEXT, created_at TEXT);
CREATE TABLE idempotency (key TEXT PRIMARY KEY, status INTEGER, body TEXT, created_at TEXT);
"""

# (descriptor template, typical amount range in dollars)
MERCHANTS = [
    ("SQ *SIGHTGLASS COFFEE", (4, 14)), ("TST* TARTINE BAKERY", (6, 40)), ("UBER *TRIP", (9, 55)),
    ("LYFT *RIDE", (8, 48)), ("AMZN Mktp US*2K4", (12, 240)), ("GOOGLE *CLOUD", (40, 600)),
    ("SP *LINEAR.APP", (8, 96)), ("PAYPAL *FIGMA", (15, 75)), ("DOORDASH*SWEETGREEN", (14, 38)),
    ("SAFEWAY #1490 SAN FRANCISCO CA", (20, 160)), ("SHELL OIL 57444", (35, 90)), ("UNITED 0162345", (180, 640)),
    ("MARRIOTT SF MARQUIS", (220, 900)), ("OFFICE DEPOT #2194", (18, 220)), ("WEWORK MEMBERSHIP", (300, 650)),
    ("SQ *BLUE BOTTLE COFFEE", (4, 12)), ("SQ *JOES PIZZA #12 SAN FRANCISCO CA", (18, 70)),
]


def iso(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def at(day_offset: int, hour: int = 12, minute: int = 0) -> datetime:
    d = datetime.now(PT).date() - timedelta(days=day_offset)
    return datetime.combine(d, time(hour, minute), tzinfo=PT)


def main() -> None:
    random.seed(7)
    DB.unlink(missing_ok=True)
    conn = sqlite3.connect(DB)
    conn.executescript(SCHEMA)
    rows = []

    def add(descriptor: str, cents: int, when: datetime) -> None:
        rows.append((f"txn_{len(rows) + 1:04d}", descriptor, cents, "USD", iso(when), random.choice(["4242", "1881", "0057"]), None, iso(when)))

    for _ in range(190):
        desc, (lo, hi) = random.choice(MERCHANTS)
        add(desc, random.randint(lo * 100, hi * 100), at(random.randint(4, 40), random.randint(7, 22), random.randint(0, 59)))

    samples = json.loads((HERE / "samples.json").read_text())["receipts"]
    for s in samples:
        for c in [s.get("charge"), *s.get("decoys", [])]:
            if c:
                add(c["descriptor"], c["cents"], at(c["day_offset"], 20, 10))
    # a decoy far outside the matching window for the pizza place, plus a same-week coffee decoy
    add("SQ *JOES PIZZA #12 SAN FRANCISCO CA", 4415, at(16, 19, 0))
    add("TST* BLUE BOTTLE COFFEE 0042", 725, at(1, 15, 30))

    conn.executemany("INSERT INTO transactions VALUES (?, ?, ?, ?, ?, ?, ?, ?)", rows)
    approvals = [
        ("apr_01", "Maya Chen", 48900, "Figma", "Annual seat for new designer"),
        ("apr_02", "Leo Park", 12650, "Office Depot", "Standing desk mats"),
        ("apr_03", "Ava Brooks", 214000, "United Airlines", "Customer onsite, NYC"),
        ("apr_04", "Sam Ortiz", 3200, "Uber", "Airport ride"),
    ]
    conn.executemany(
        "INSERT INTO approvals VALUES (?, ?, ?, ?, ?, 'PENDING', 1, NULL, ?)",
        [(a, n, c, m, memo, iso(at(i, 9))) for i, (a, n, c, m, memo) in enumerate(approvals)],
    )
    conn.commit()
    print(f"seeded {len(rows)} transactions and {len(approvals)} approvals into {DB}")


if __name__ == "__main__":
    main()
