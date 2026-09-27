#!/usr/bin/env python3
"""Sinh file CSV khách hàng (15 cột) để thử ImportService — docs/csv-import-design.md mục 5.

Cách dùng:
    python3 scripts/gen-customers-csv.py 1000000 customers-1m.csv            # 1 triệu dòng hợp lệ
    python3 scripts/gen-customers-csv.py 10000 small.csv --invalid-every 100  # cứ 100 dòng có 1 dòng sai email
"""
import argparse
import csv
import random

HEADER = ["external_id", "full_name", "email", "phone", "date_of_birth",
          "gender", "address", "city", "country", "postal_code",
          "company", "job_title", "annual_income", "signup_date", "status"]
FIRST = ["An", "Binh", "Chi", "Dung", "Giang", "Ha", "Khanh", "Linh", "Minh", "Nam", "Phuong", "Quan", "Thao", "Vy"]
LAST = ["Nguyen", "Tran", "Le", "Pham", "Hoang", "Vu", "Dang", "Bui", "Do", "Ngo"]
CITIES = [("Ha Noi", "100000"), ("Ho Chi Minh", "700000"), ("Da Nang", "550000"), ("Can Tho", "900000")]
COMPANIES = ["ACME", "Globex", "Initech", "Umbrella", "Hooli"]
TITLES = ["Engineer", "Designer", "Manager", "Analyst", "Sales"]
STATUSES = ["ACTIVE", "INACTIVE", "PENDING"]


def row(i: int, rnd: random.Random, invalid: bool) -> list:
    city, postal = rnd.choice(CITIES)
    name = f"{rnd.choice(LAST)} {rnd.choice(FIRST)}"
    email = "not-an-email" if invalid else f"customer{i}@example.com"
    return [
        f"C-{i}", name, email, f"09{i % 100_000_000:08d}",
        f"{rnd.randint(1960, 2005)}-{rnd.randint(1, 12):02d}-{rnd.randint(1, 28):02d}",
        rnd.choice(["M", "F"]),
        f"{rnd.randint(1, 999)} Le Loi, Ward {rnd.randint(1, 20)}",  # có dấu phẩy → được đặt trong "..."
        city, "VN", postal, rnd.choice(COMPANIES), rnd.choice(TITLES),
        f"{rnd.randint(5_000, 500_000)}.{rnd.randint(0, 99):02d}",
        f"{rnd.randint(2018, 2026)}-{rnd.randint(1, 12):02d}-{rnd.randint(1, 28):02d}",
        rnd.choice(STATUSES),
    ]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("rows", type=int, help="số dòng dữ liệu (không tính header)")
    parser.add_argument("output", help="file CSV đầu ra")
    parser.add_argument("--invalid-every", type=int, default=0, help="cứ N dòng thì 1 dòng sai email (0 = không)")
    parser.add_argument("--seed", type=int, default=42, help="cùng seed → cùng dữ liệu")
    args = parser.parse_args()

    rnd = random.Random(args.seed)
    with open(args.output, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(HEADER)
        for i in range(1, args.rows + 1):
            writer.writerow(row(i, rnd, args.invalid_every > 0 and i % args.invalid_every == 0))
    print(f"Wrote {args.rows} rows to {args.output}")


if __name__ == "__main__":
    main()
