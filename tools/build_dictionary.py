"""Build the offline Dahl text index. Pass the verified upstream daldic-dump.sql."""
from collections import defaultdict
import gzip
import hashlib
from pathlib import Path
import sqlite3
import sys
import zlib

source = Path(sys.argv[1]).read_bytes()
blob = b"blob " + str(len(source)).encode() + b"\0" + source
if hashlib.sha1(blob).hexdigest() != "d41129fd92daaa842261721168e190cb71599378":
    raise SystemExit("Expected the SQL dump from dal-dictionary commit 901636df3bf8003f35cbcf989d2e1b92310470dc")
db = sqlite3.connect(":memory:")
db.executescript(source.decode("utf-8"))
records = defaultdict(list)
for word, description in db.execute("""
    SELECT w.word, COALESCE(ref.description, w.description)
    FROM word w LEFT JOIN word ref ON w.word_ref = ref.word_id ORDER BY w.word_id
"""):
    if not word or not description:
        continue
    key = word.lower().replace("ё", "е").replace("\u0301", "").strip()
    text = " ".join(description.split())
    if text and text not in records[key]:
        records[key].append(text)
out = Path(__file__).resolve().parents[1] / "app/src/main/assets/dictionary"
out.mkdir(exist_ok=True)
buckets = [[] for _ in range(64)]
for key, senses in sorted(records.items()):
    bucket = zlib.crc32(key[:2].encode()) % len(buckets)
    buckets[bucket].append(key + "\t" + " • ".join(senses))
for i, lines in enumerate(buckets):
    (out / f"{i:02d}.gz").write_bytes(gzip.compress("\n".join(lines).encode(), compresslevel=9, mtime=0))
print(f"{len(records)} headwords")
