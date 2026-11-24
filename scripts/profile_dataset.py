"""Profiles the Zomato CSV and prints every finding behind the seeder's cleanup rules."""

import csv
import re
import sys
from collections import Counter
from pathlib import Path

CSV_PATH = Path(__file__).resolve().parent.parent / "src/main/resources/zomato.csv"
DISHES = [
    "vada pav", "pav bhaji", "chole bhature", "chaat", "golgappa", "dosa", "idli",
    "uttapam", "butter chicken", "paneer", "momos", "pasta", "kebab", "dim sum",
    "biryani", "burger", "sushi", "pizza",
]


def cuisines(row):
    return [c.strip() for c in row["Cuisines"].split(",") if c.strip()]


def section(title):
    print(f"\n== {title}")


def main():
    path = Path(sys.argv[1]) if len(sys.argv) > 1 else CSV_PATH
    with path.open(encoding="latin-1", newline="") as f:
        rows = list(csv.DictReader(f))

    section("Rows and identity")
    ids = Counter(r["Restaurant ID"] for r in rows)
    names = Counter(r["Restaurant Name"] for r in rows)
    name_address = Counter((r["Restaurant Name"], r["Address"]) for r in rows)
    print(f"rows: {len(rows)}, unique Restaurant IDs: {len(ids)}")
    print(f"duplicate IDs: {sum(1 for n in ids.values() if n > 1)}")
    print(f"rows sharing name and address: {sum(n for n in name_address.values() if n > 1)}")
    print(f"names used by more than one outlet: {sum(1 for n in names.values() if n > 1)}, "
          f"largest chains: {names.most_common(3)}")

    section("Currency label per Zomato country code")
    labels = Counter((int(r["Country Code"]), r["Currency"]) for r in rows)
    for (code, label), n in sorted(labels.items()):
        print(f"{code:>4}  {label!r:28} {n:>5} rows")

    section("Ratings")
    not_rated = [r for r in rows if r["Rating text"] == "Not rated"]
    print(f"'Not rated' rows: {len(not_rated)} ({len(not_rated) * 100 // len(rows)}%)")
    print(f"their Aggregate rating values: {dict(Counter(r['Aggregate rating'] for r in not_rated))}")
    print(f"their maximum votes: {max(int(r['Votes']) for r in not_rated)}")
    zero_but_rated = sum(
        1 for r in rows if float(r["Aggregate rating"]) == 0 and r["Rating text"] != "Not rated")
    print(f"rating 0 with any other rating text: {zero_but_rated}")

    section("Cuisines")
    all_cuisines = Counter(c for r in rows for c in cuisines(r))
    print(f"distinct cuisines: {len(all_cuisines)}, top 5: {all_cuisines.most_common(5)}")
    repeated = [r for r in rows if len(cuisines(r)) != len(set(cuisines(r)))]
    print(f"rows listing a cuisine twice: {len(repeated)}")
    for r in repeated:
        print(f"  {r['Restaurant ID']:>9}  {r['Restaurant Name']}: {r['Cuisines']}")

    section("Coordinates")
    zero = sum(1 for r in rows if float(r["Latitude"]) == 0 and float(r["Longitude"]) == 0)
    print(f"rows at (0, 0): {zero}")

    section("Counts behind the brief's example queries")
    gurgaon = [r for r in rows if r["City"].strip().lower() == "gurgaon"]
    print(f"Gurgaon: {len(gurgaon)}")
    print(f"Gurgaon, South Indian: {sum('South Indian' in cuisines(r) for r in gurgaon)}")
    print(f"Gurgaon, cost for two <= 1000: "
          f"{sum(int(r['Average Cost for two']) <= 1000 for r in gurgaon)}")
    print(f"Street Food anywhere: {sum('Street Food' in cuisines(r) for r in rows)}")

    section("Dish words in restaurant names, and the cuisines those restaurants list")
    known = {c.lower() for c in all_cuisines}
    for dish in DISHES:
        hits = [r for r in rows
                if re.search(rf"\b{re.escape(dish)}\b", r["Restaurant Name"].lower())]
        top = Counter(c for r in hits for c in cuisines(r)).most_common(2)
        shares = ", ".join(f"{c} {n * 100 // len(hits)}%" for c, n in top) if hits else "-"
        flag = "  (already a cuisine)" if dish in known else ""
        print(f"{dish:15} {len(hits):>4} restaurants  {shares}{flag}")


if __name__ == "__main__":
    main()
