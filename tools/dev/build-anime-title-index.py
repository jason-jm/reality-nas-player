"""Build the bundled factual title index from the pinned public bangumi-data release."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path

SOURCE_SHA256 = "8a0333126dd0108392ad9dec8116e7f511112ac5c3080ffa93dfdbb9c213a7e7"
OUTPUT = Path(__file__).resolve().parents[2] / "core/src/main/resources/reality-nas-player/anime-titles.tsv.gz"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="package/dist/data.json from the bangumi-data 0.3.237 npm archive")
    source = parser.parse_args().source.read_bytes()
    if hashlib.sha256(source).hexdigest() != SOURCE_SHA256:
        parser.error("The input does not match the pinned dataset")
    rows = set()
    for item in json.loads(source)["items"]:
        subject = next((s["id"] for s in item["sites"] if s["site"] == "bangumi" and s["id"].isdigit()), None)
        if not subject or item["type"] not in {"movie", "tv", "ova", "web"}:
            continue
        translations = item.get("titleTranslate", {})
        names = [translations.get(lang, []) for lang in ("zh-Hans", "zh-Hant", "en", "ja")]
        primary = [item["title"]] + [group[0] if group else "" for group in names[:3]]
        rest = dict.fromkeys(n for group in names for n in group if n not in primary)
        anilist = next((s["id"] for s in item["sites"] if s["site"] == "aniList" and s["id"].isdigit()), "")
        fields = [subject, item["type"], (item.get("begin") or "")[:4], anilist, *primary, *rest]
        rows.add("\t".join(" ".join(value.split()) for value in fields))
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    with OUTPUT.open("wb") as out, gzip.GzipFile(filename="", fileobj=out, mode="wb", mtime=0) as gz:
        gz.write(("\n".join(sorted(rows)) + "\n").encode("utf-8"))
    print(f"{len(rows)} rows, {OUTPUT.stat().st_size:,} bytes")


if __name__ == "__main__":
    main()
