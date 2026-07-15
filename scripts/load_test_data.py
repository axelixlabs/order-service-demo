#!/usr/bin/env python3
"""
Bulk-load realistic test data into the order-service PostgreSQL database.

Targets (defaults):
  - 10 categories
  - 1,000 products (100 per category)
  - 100,000 customers (with billing + shipping addresses)
  - 10,000,000 purchase orders (100 per customer, evenly distributed)
  - ~25M order line items, payments, and shipments

Requires: psycopg2-binary (see scripts/requirements.txt)

Usage:
  pip install -r scripts/requirements.txt
  python scripts/load_test_data.py

Environment variables (defaults match docker-compose):
  PGHOST=localhost  PGPORT=5432  PGDATABASE=orderdb
  PGUSER=orderuser  PGPASSWORD=orderpass
"""

from __future__ import annotations

import argparse
import io
import os
import random
import sys
import time
import uuid
from datetime import datetime, timedelta, timezone
from decimal import Decimal, ROUND_HALF_UP

try:
    import psycopg2
    from psycopg2 import sql
except ImportError:
    print("Missing dependency: pip install -r scripts/requirements.txt", file=sys.stderr)
    sys.exit(1)

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

NUM_CATEGORIES = 10
PRODUCTS_PER_CATEGORY = 100
NUM_CUSTOMERS = 100_000
NUM_ORDERS = 10_000_000

ORDER_CHUNK_SIZE = 50_000
CUSTOMER_CHUNK_SIZE = 10_000

CARRIERS = ("UPS", "FedEx", "USPS", "DHL")
PAYMENT_METHODS = ("CREDIT_CARD", "DEBIT_CARD", "PAYPAL", "BANK_TRANSFER")
PAYMENT_METHOD_WEIGHTS = (0.55, 0.22, 0.18, 0.05)

ORDER_STATUS_WEIGHTS = (
    ("DELIVERED", 0.62),
    ("SHIPPED", 0.14),
    ("PROCESSING", 0.08),
    ("PAID", 0.06),
    ("CREATED", 0.04),
    ("CANCELLED", 0.06),
)

CATEGORIES = (
    ("Electronics", "Devices, gadgets, and accessories"),
    ("Books", "Printed and digital books"),
    ("Home & Garden", "Furniture, decor, and outdoor living"),
    ("Clothing", "Apparel for men, women, and children"),
    ("Sports & Outdoors", "Athletic gear and camping equipment"),
    ("Toys & Games", "Toys, board games, and puzzles"),
    ("Beauty & Personal Care", "Skincare, cosmetics, and grooming"),
    ("Grocery", "Pantry staples and specialty foods"),
    ("Automotive", "Car care and accessories"),
    ("Office Supplies", "Stationery, printers, and desk accessories"),
)

FIRST_NAMES = (
    "James", "Mary", "Robert", "Patricia", "John", "Jennifer", "Michael", "Linda",
    "David", "Elizabeth", "William", "Barbara", "Richard", "Susan", "Joseph", "Jessica",
    "Thomas", "Sarah", "Christopher", "Karen", "Charles", "Lisa", "Daniel", "Nancy",
    "Matthew", "Betty", "Anthony", "Margaret", "Mark", "Sandra", "Donald", "Ashley",
    "Steven", "Kimberly", "Paul", "Emily", "Andrew", "Donna", "Joshua", "Michelle",
    "Kenneth", "Dorothy", "Kevin", "Carol", "Brian", "Amanda", "George", "Melissa",
    "Timothy", "Deborah", "Ronald", "Stephanie", "Edward", "Rebecca", "Jason", "Sharon",
    "Jeffrey", "Laura", "Ryan", "Cynthia", "Jacob", "Kathleen", "Gary", "Amy",
    "Nicholas", "Angela", "Eric", "Shirley", "Jonathan", "Anna", "Stephen", "Brenda",
    "Larry", "Pamela", "Justin", "Emma", "Scott", "Nicole", "Brandon", "Helen",
    "Benjamin", "Samantha", "Samuel", "Katherine", "Gregory", "Christine", "Alexander", "Debra",
    "Patrick", "Rachel", "Frank", "Carolyn", "Raymond", "Janet", "Jack", "Catherine",
    "Dennis", "Maria", "Jerry", "Heather", "Tyler", "Diane", "Aaron", "Ruth",
    "Henry", "Julie", "Adam", "Olivia", "Douglas", "Joyce", "Nathan", "Virginia",
    "Peter", "Victoria", "Zachary", "Kelly", "Kyle", "Lauren", "Noah", "Christina",
    "Ethan", "Joan", "Jeremy", "Evelyn", "Walter", "Judith", "Christian", "Megan",
    "Keith", "Andrea", "Roger", "Cheryl", "Terry", "Hannah", "Austin", "Jacqueline",
    "Sean", "Martha", "Gerald", "Gloria", "Carl", "Teresa", "Harold", "Ann",
    "Dylan", "Sara", "Arthur", "Madison", "Lawrence", "Frances", "Jordan", "Kathryn",
    "Jesse", "Janice", "Bryan", "Jean", "Billy", "Abigail", "Bruce", "Alice",
    "Gabriel", "Julia", "Joe", "Judy", "Logan", "Sophia", "Alan", "Grace",
    "Juan", "Denise", "Wayne", "Amber", "Roy", "Marilyn", "Ralph", "Beverly",
    "Randy", "Danielle", "Eugene", "Theresa", "Vincent", "Diana", "Russell", "Natalie",
    "Louis", "Brittany", "Philip", "Charlotte", "Bobby", "Marie", "Johnny", "Kayla",
    "Bradley", "Alexis", "Emma", "Liam", "Olivia", "Noah", "Ava", "Oliver",
    "Sophia", "Elijah", "Isabella", "Lucas", "Mia", "Mason", "Amelia", "Logan",
)

LAST_NAMES = (
    "Smith", "Johnson", "Williams", "Brown", "Jones", "Garcia", "Miller", "Davis",
    "Rodriguez", "Martinez", "Hernandez", "Lopez", "Gonzalez", "Wilson", "Anderson", "Thomas",
    "Taylor", "Moore", "Jackson", "Martin", "Lee", "Perez", "Thompson", "White",
    "Harris", "Sanchez", "Clark", "Ramirez", "Lewis", "Robinson", "Walker", "Young",
    "Allen", "King", "Wright", "Scott", "Torres", "Nguyen", "Hill", "Flores",
    "Green", "Adams", "Nelson", "Baker", "Hall", "Rivera", "Campbell", "Mitchell",
    "Carter", "Roberts", "Gomez", "Phillips", "Evans", "Turner", "Diaz", "Parker",
    "Cruz", "Edwards", "Collins", "Reyes", "Stewart", "Morris", "Morales", "Murphy",
    "Cook", "Rogers", "Gutierrez", "Ortiz", "Morgan", "Cooper", "Peterson", "Bailey",
    "Reed", "Kelly", "Howard", "Ramos", "Kim", "Cox", "Ward", "Richardson",
    "Watson", "Brooks", "Chavez", "Wood", "James", "Bennett", "Gray", "Mendoza",
    "Ruiz", "Hughes", "Price", "Alvarez", "Castillo", "Sanders", "Patel", "Myers",
    "Long", "Ross", "Foster", "Jimenez", "Powell", "Jenkins", "Perry", "Russell",
    "Sullivan", "Bell", "Coleman", "Butler", "Henderson", "Barnes", "Gonzales", "Fisher",
    "Vasquez", "Simmons", "Romero", "Jordan", "Patterson", "Alexander", "Hamilton", "Graham",
    "Reynolds", "Griffin", "Wallace", "Moreno", "West", "Cole", "Hayes", "Bryant",
    "Herrera", "Gibson", "Ellis", "Tran", "Medina", "Aguilar", "Stevens", "Murray",
    "Ford", "Castro", "Marshall", "Owens", "Harrison", "Fernandez", "McDonald", "Woods",
    "Washington", "Kennedy", "Wells", "Vargas", "Henry", "Chen", "Freeman", "Webb",
    "Tucker", "Guzman", "Burns", "Crawford", "Olson", "Simpson", "Porter", "Hunter",
    "Gordon", "Mendez", "Silva", "Shaw", "Snyder", "Mason", "Dixon", "Munoz",
    "Hunt", "Hicks", "Holmes", "Palmer", "Wagner", "Black", "Robertson", "Boyd",
    "Rose", "Stone", "Salazar", "Fox", "Warren", "Mills", "Meyer", "Rice",
    "Schmidt", "Garza", "Daniels", "Ferguson", "Nichols", "Stephens", "Soto", "Weaver",
    "Ryan", "Gardner", "Payne", "Grant", "Dunn", "Kelley", "Spencer", "Hawkins",
)

STREET_NAMES = (
    "Main", "Oak", "Pine", "Maple", "Cedar", "Elm", "Washington", "Lake",
    "Hill", "Park", "River", "Sunset", "Highland", "Church", "Spring", "Center",
    "Walnut", "Chestnut", "Willow", "Lincoln", "Madison", "Jefferson", "Franklin", "Adams",
    "Broadway", "Market", "Union", "Liberty", "Valley", "Forest", "Summit", "Bridge",
)

STREET_TYPES = ("St", "Ave", "Blvd", "Dr", "Ln", "Rd", "Way", "Ct", "Pl")

US_CITIES = (
    ("New York", "NY", "10001", "US"),
    ("Los Angeles", "CA", "90001", "US"),
    ("Chicago", "IL", "60601", "US"),
    ("Houston", "TX", "77001", "US"),
    ("Phoenix", "AZ", "85001", "US"),
    ("Philadelphia", "PA", "19101", "US"),
    ("San Antonio", "TX", "78201", "US"),
    ("San Diego", "CA", "92101", "US"),
    ("Dallas", "TX", "75201", "US"),
    ("San Jose", "CA", "95101", "US"),
    ("Austin", "TX", "78701", "US"),
    ("Jacksonville", "FL", "32099", "US"),
    ("Fort Worth", "TX", "76101", "US"),
    ("Columbus", "OH", "43004", "US"),
    ("Charlotte", "NC", "28201", "US"),
    ("Indianapolis", "IN", "46201", "US"),
    ("Seattle", "WA", "98101", "US"),
    ("Denver", "CO", "80201", "US"),
    ("Boston", "MA", "02101", "US"),
    ("Nashville", "TN", "37201", "US"),
    ("Portland", "OR", "97201", "US"),
    ("Las Vegas", "NV", "89101", "US"),
    ("Detroit", "MI", "48201", "US"),
    ("Memphis", "TN", "38101", "US"),
    ("Louisville", "KY", "40201", "US"),
    ("Baltimore", "MD", "21201", "US"),
    ("Milwaukee", "WI", "53201", "US"),
    ("Albuquerque", "NM", "87101", "US"),
    ("Tucson", "AZ", "85701", "US"),
    ("Atlanta", "GA", "30301", "US"),
)

EU_CITIES = (
    ("London", None, "EC1A 1BB", "UK"),
    ("Berlin", None, "10115", "DE"),
    ("Paris", None, "75001", "FR"),
    ("Madrid", None, "28001", "ES"),
    ("Amsterdam", None, "1011", "NL"),
    ("Dublin", None, "D01", "IE"),
    ("Stockholm", None, "111 20", "SE"),
    ("Copenhagen", None, "1050", "DK"),
    ("Vienna", None, "1010", "AT"),
    ("Brussels", None, "1000", "BE"),
)

PRODUCT_ADJECTIVES = (
    "Premium", "Classic", "Essential", "Pro", "Compact", "Deluxe", "Everyday",
    "Ultra", "Smart", "Eco", "Heritage", "Studio", "Travel", "Family", "Artisan",
)

PRODUCT_NOUNS = {
    "Electronics": ("Headphones", "Keyboard", "Monitor", "Webcam", "Speaker", "Tablet Stand", "USB Hub", "Power Bank", "Smart Watch", "Router"),
    "Books": ("Cookbook", "Mystery Novel", "History Guide", "Poetry Collection", "Science Textbook", "Biography", "Travel Guide", "Fantasy Epic", "Self-Help Guide", "Art Album"),
    "Home & Garden": ("Throw Pillow", "Desk Lamp", "Garden Hose", "Storage Bin", "Wall Clock", "Plant Pot", "Area Rug", "Tool Set", "Candle Set", "Picture Frame"),
    "Clothing": ("T-Shirt", "Jeans", "Hoodie", "Running Shoes", "Winter Jacket", "Dress Shirt", "Socks Pack", "Baseball Cap", "Scarf", "Leggings"),
    "Sports & Outdoors": ("Yoga Mat", "Camping Tent", "Water Bottle", "Dumbbell Set", "Hiking Backpack", "Tennis Racket", "Cycling Helmet", "Sleeping Bag", "Fitness Tracker Band", "Cooler"),
    "Toys & Games": ("Building Blocks", "Board Game", "Puzzle", "Action Figure", "Plush Toy", "Card Game", "RC Car", "Craft Kit", "Doll Set", "Strategy Game"),
    "Beauty & Personal Care": ("Face Cream", "Shampoo", "Body Wash", "Lip Balm", "Sunscreen", "Hair Dryer", "Electric Razor", "Perfume", "Hand Soap", "Moisturizer"),
    "Grocery": ("Olive Oil", "Coffee Beans", "Granola", "Pasta Pack", "Honey Jar", "Tea Selection", "Snack Mix", "Spice Set", "Hot Sauce", "Protein Bars"),
    "Automotive": ("Floor Mats", "Phone Mount", "Tire Gauge", "Car Vacuum", "Wax Kit", "Jump Starter", "Seat Cover", "Air Freshener", "Dash Cam", "Oil Filter"),
    "Office Supplies": ("Notebook", "Ballpoint Pen", "Stapler", "Desk Organizer", "Printer Paper", "Whiteboard", "Sticky Notes", "File Folder", "Label Maker", "Mouse Pad"),
}

TRUNCATE_SQL = """
TRUNCATE TABLE
    shipments,
    payments,
    order_items,
    purchase_orders,
    addresses,
    products,
    customers,
    categories
RESTART IDENTITY CASCADE;
"""

SCHEMA_SQL = """
CREATE TABLE IF NOT EXISTS categories (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL UNIQUE,
    description VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS customers (
    id          BIGSERIAL PRIMARY KEY,
    first_name  VARCHAR(255) NOT NULL,
    last_name   VARCHAR(255) NOT NULL,
    email       VARCHAR(255) NOT NULL UNIQUE,
    phone       VARCHAR(255),
    created_at  TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS addresses (
    id          BIGSERIAL PRIMARY KEY,
    customer_id BIGINT NOT NULL REFERENCES customers(id),
    type        VARCHAR(255) NOT NULL,
    street      VARCHAR(255) NOT NULL,
    city        VARCHAR(255) NOT NULL,
    state       VARCHAR(255),
    postal_code VARCHAR(255) NOT NULL,
    country     VARCHAR(255) NOT NULL
);

CREATE TABLE IF NOT EXISTS products (
    id             BIGSERIAL PRIMARY KEY,
    sku            VARCHAR(255) NOT NULL UNIQUE,
    name           VARCHAR(255) NOT NULL,
    description    VARCHAR(2000),
    price          NUMERIC(12, 2) NOT NULL,
    stock_quantity INTEGER NOT NULL,
    category_id    BIGINT NOT NULL REFERENCES categories(id),
    version        BIGINT
);

CREATE TABLE IF NOT EXISTS purchase_orders (
    id           BIGSERIAL PRIMARY KEY,
    order_number VARCHAR(255) NOT NULL UNIQUE,
    customer_id  BIGINT NOT NULL REFERENCES customers(id),
    status       VARCHAR(255) NOT NULL,
    total_amount NUMERIC(14, 2) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS order_items (
    id         BIGSERIAL PRIMARY KEY,
    order_id   BIGINT NOT NULL REFERENCES purchase_orders(id),
    product_id BIGINT NOT NULL REFERENCES products(id),
    quantity   INTEGER NOT NULL,
    unit_price NUMERIC(12, 2) NOT NULL,
    line_total NUMERIC(14, 2) NOT NULL
);

CREATE TABLE IF NOT EXISTS payments (
    id             BIGSERIAL PRIMARY KEY,
    order_id       BIGINT NOT NULL UNIQUE REFERENCES purchase_orders(id),
    amount         NUMERIC(14, 2) NOT NULL,
    method         VARCHAR(255) NOT NULL,
    status         VARCHAR(255) NOT NULL,
    transaction_id VARCHAR(255),
    paid_at        TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS shipments (
    id                  BIGSERIAL PRIMARY KEY,
    order_id            BIGINT NOT NULL UNIQUE REFERENCES purchase_orders(id),
    shipping_address_id BIGINT NOT NULL REFERENCES addresses(id),
    status              VARCHAR(255) NOT NULL,
    carrier             VARCHAR(255),
    tracking_number     VARCHAR(255),
    shipped_at          TIMESTAMPTZ,
    delivered_at        TIMESTAMPTZ
);
"""


def money(value: float | Decimal) -> str:
    return str(Decimal(str(value)).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP))


def ts(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).isoformat()


def weighted_choice(rng: random.Random, choices: tuple[tuple[str, float], ...]) -> str:
    roll = rng.random()
    cumulative = 0.0
    for value, weight in choices:
        cumulative += weight
        if roll <= cumulative:
            return value
    return choices[-1][0]


def payment_status_for_order(rng: random.Random, order_status: str) -> str:
    if order_status == "CANCELLED":
        return rng.choices(("FAILED", "REFUNDED"), weights=(0.7, 0.3), k=1)[0]
    if order_status == "CREATED":
        return rng.choices(("PENDING", "AUTHORIZED"), weights=(0.75, 0.25), k=1)[0]
    return "CAPTURED"


def shipment_status_for_order(order_status: str) -> str:
    mapping = {
        "CREATED": "PENDING",
        "PAID": "PENDING",
        "PROCESSING": "PENDING",
        "SHIPPED": "IN_TRANSIT",
        "DELIVERED": "DELIVERED",
        "CANCELLED": "RETURNED",
    }
    return mapping[order_status]


def copy_buffer(cur, table: str, columns: list[str], rows: list[str]) -> None:
    if not rows:
        return
    buf = io.StringIO("\n".join(rows) + "\n")
    cur.copy_from(buf, table, columns=columns, null="\\N")


def log(msg: str) -> None:
    print(msg, flush=True)


def connect_db(args: argparse.Namespace):
    return psycopg2.connect(
        host=args.host,
        port=args.port,
        dbname=args.database,
        user=args.user,
        password=args.password,
    )


def ensure_schema(cur) -> None:
    cur.execute(SCHEMA_SQL)


def load_categories(cur, rng: random.Random) -> None:
    rows = [f"{name}\t{desc}" for name, desc in CATEGORIES[:NUM_CATEGORIES]]
    copy_buffer(cur, "categories", ["name", "description"], rows)
    log(f"  categories: {NUM_CATEGORIES}")


def load_products(cur, rng: random.Random) -> list[tuple[int, Decimal]]:
    """Returns list of (product_id, price) for order generation."""
    rows: list[str] = []
    catalog: list[tuple[int, Decimal]] = []
    product_id = 1
    for cat_idx, (cat_name, _) in enumerate(CATEGORIES[:NUM_CATEGORIES], start=1):
        nouns = PRODUCT_NOUNS[cat_name]
        for i in range(1, PRODUCTS_PER_CATEGORY + 1):
            adj = PRODUCT_ADJECTIVES[(product_id + i) % len(PRODUCT_ADJECTIVES)]
            noun = nouns[(product_id + i) % len(nouns)]
            name = f"{adj} {noun}"
            sku = f"SKU-{cat_name[:3].upper()}-{i:04d}"
            base_price = 4.99 + (product_id % 97) * 3.17 + (cat_idx * 2.5)
            price = Decimal(str(base_price)).quantize(Decimal("0.01"))
            stock = 10_000 + (product_id * 17) % 50_000
            desc = f"{name} from the {cat_name} catalogue"
            rows.append(
                f"{sku}\t{name}\t{desc}\t{money(price)}\t{stock}\t{cat_idx}\t0"
            )
            catalog.append((product_id, price))
            product_id += 1
    copy_buffer(
        cur,
        "products",
        ["sku", "name", "description", "price", "stock_quantity", "category_id", "version"],
        rows,
    )
    log(f"  products: {len(catalog)}")
    return catalog


def customer_created_at(rng: random.Random, customer_index: int) -> datetime:
    # Spread sign-ups over the last 5 years; earlier indices slightly older on average.
    days_ago = 30 + (customer_index % 1780) + rng.randint(0, 60)
    hour = rng.randint(8, 20)
    minute = rng.randint(0, 59)
    base = datetime.now(timezone.utc) - timedelta(days=days_ago)
    return base.replace(hour=hour, minute=minute, second=rng.randint(0, 59), microsecond=0)


def load_customers_and_addresses(cur, rng: random.Random) -> None:
    log(f"  customers: {NUM_CUSTOMERS} (+ {NUM_CUSTOMERS * 2} addresses)")
    for start in range(1, NUM_CUSTOMERS + 1, CUSTOMER_CHUNK_SIZE):
        end = min(start + CUSTOMER_CHUNK_SIZE, NUM_CUSTOMERS + 1)
        customer_rows: list[str] = []
        address_rows: list[str] = []

        for cid in range(start, end):
            first = FIRST_NAMES[cid % len(FIRST_NAMES)]
            last = LAST_NAMES[(cid * 7) % len(LAST_NAMES)]
            email = f"{first.lower()}.{last.lower()}.{cid}@mail.example.com"
            phone = f"+1-{555 + (cid % 400):03d}-{(cid * 13) % 10000:04d}"
            created = ts(customer_created_at(rng, cid))
            customer_rows.append(f"{first}\t{last}\t{email}\t{phone}\t{created}")

            city_tuple = US_CITIES[cid % len(US_CITIES)] if cid % 5 else EU_CITIES[cid % len(EU_CITIES)]
            city, state, postal, country = city_tuple
            street_num = 100 + (cid * 3) % 9900
            street = f"{street_num} {STREET_NAMES[cid % len(STREET_NAMES)]} {STREET_TYPES[cid % len(STREET_TYPES)]}"
            state_val = state if state else "\\N"

            # Billing then shipping for predictable shipping_address_id = 2 * customer_id
            for addr_type in ("BILLING", "SHIPPING"):
                address_rows.append(
                    f"{cid}\t{addr_type}\t{street}\t{city}\t{state_val}\t{postal}\t{country}"
                )

        copy_buffer(cur, "customers", ["first_name", "last_name", "email", "phone", "created_at"], customer_rows)
        copy_buffer(
            cur,
            "addresses",
            ["customer_id", "type", "street", "city", "state", "postal_code", "country"],
            address_rows,
        )
        log(f"    ... customers {start:,} – {end - 1:,}")


def order_timestamps(rng: random.Random, order_index: int) -> datetime:
    # Orders span ~3 years with mild seasonality and growth toward recent dates.
    days_ago = int(1095 * (1 - (order_index / NUM_ORDERS) ** 0.85)) + rng.randint(0, 14)
    month_bias = int(15 * (1 + 0.25 * ((order_index // 100_000) % 12 - 6) / 6))
    days_ago = max(0, days_ago - month_bias)
    hour = rng.randint(0, 23)
    minute = rng.randint(0, 59)
    base = datetime.now(timezone.utc) - timedelta(days=days_ago)
    return base.replace(hour=hour, minute=minute, second=rng.randint(0, 59), microsecond=0)


def pick_order_status(rng: random.Random, created: datetime) -> str:
    age_days = (datetime.now(timezone.utc) - created).days
    if age_days < 3:
        return rng.choices(["CREATED", "PAID", "PROCESSING"], weights=[0.5, 0.35, 0.15], k=1)[0]
    if age_days < 14:
        return rng.choices(["PAID", "PROCESSING", "SHIPPED"], weights=[0.2, 0.35, 0.45], k=1)[0]
    return weighted_choice(rng, ORDER_STATUS_WEIGHTS)


def generate_order_items(
    rng: random.Random, order_index: int, catalog: list[tuple[int, Decimal]]
) -> tuple[list[str], Decimal]:
    num_items = 1 + ((order_index * 17 + 3) % 4)  # 1–4 items, deterministic per order
    item_rows: list[str] = []
    total = Decimal("0.00")
    used_products: set[int] = set()
    for line in range(num_items):
        product_id, unit_price = catalog[(order_index + line * 997) % len(catalog)]
        # Avoid duplicate products in the same order when possible
        attempts = 0
        while product_id in used_products and attempts < 5:
            product_id, unit_price = catalog[(product_id + 13) % len(catalog)]
            attempts += 1
        used_products.add(product_id)
        qty = 1 + ((order_index + line) % 3)
        line_total = (unit_price * qty).quantize(Decimal("0.01"))
        total += line_total
        # order_id placeholder 0 — filled after we know batch base id
        item_rows.append((product_id, qty, money(unit_price), money(line_total)))
    return item_rows, total


def load_orders(cur, rng: random.Random, catalog: list[tuple[int, Decimal]]) -> None:
    log(f"  orders: {NUM_ORDERS:,} (chunk size {ORDER_CHUNK_SIZE:,})")
    orders_per_customer = NUM_ORDERS // NUM_CUSTOMERS

    for chunk_start in range(0, NUM_ORDERS, ORDER_CHUNK_SIZE):
        chunk_end = min(chunk_start + ORDER_CHUNK_SIZE, NUM_ORDERS)
        chunk_len = chunk_end - chunk_start

        order_rows: list[str] = []
        item_rows: list[str] = []
        payment_rows: list[str] = []
        shipment_rows: list[str] = []

        for offset in range(chunk_len):
            order_index = chunk_start + offset + 1
            customer_id = ((order_index - 1) % NUM_CUSTOMERS) + 1
            created = order_timestamps(rng, order_index)
            status = pick_order_status(rng, created)
            updated = created + timedelta(hours=rng.randint(1, 72))
            if status == "DELIVERED":
                updated = created + timedelta(days=rng.randint(3, 10))
            elif status == "CANCELLED":
                updated = created + timedelta(hours=rng.randint(1, 48))

            items, total = generate_order_items(rng, order_index, catalog)
            order_number = f"ORD-{created.strftime('%Y%m%d')}-{order_index:09d}"

            # Temporary order_id within chunk (1-based); remapped after insert via currval/offset
            local_order_id = offset + 1
            order_rows.append(
                f"{order_number}\t{customer_id}\t{status}\t{money(total)}\t{ts(created)}\t{ts(updated)}"
            )

            for product_id, qty, unit_price, line_total in items:
                item_rows.append(f"{local_order_id}\t{product_id}\t{qty}\t{unit_price}\t{line_total}")

            method = rng.choices(PAYMENT_METHODS, weights=PAYMENT_METHOD_WEIGHTS, k=1)[0]
            pay_status = payment_status_for_order(rng, status)
            txn = f"TXN-{uuid.uuid4().hex[:16].upper()}" if pay_status == "CAPTURED" else "\\N"
            paid_at = ts(created + timedelta(minutes=rng.randint(5, 120))) if pay_status == "CAPTURED" else "\\N"
            payment_rows.append(f"{local_order_id}\t{money(total)}\t{method}\t{pay_status}\t{txn}\t{paid_at}")

            ship_status = shipment_status_for_order(status)
            shipping_address_id = customer_id * 2  # even ids are SHIPPING addresses
            carrier = rng.choice(CARRIERS)
            tracking = f"{carrier[:2].upper()}{rng.randint(10**9, 10**10 - 1)}" if ship_status != "PENDING" else "\\N"
            shipped = ts(updated - timedelta(days=rng.randint(1, 3))) if ship_status in ("IN_TRANSIT", "DELIVERED") else "\\N"
            delivered = ts(updated) if ship_status == "DELIVERED" else "\\N"
            shipment_rows.append(
                f"{local_order_id}\t{shipping_address_id}\t{ship_status}\t{carrier}\t{tracking}\t{shipped}\t{delivered}"
            )

        # Insert orders and capture the first id of this chunk
        copy_buffer(
            cur,
            "purchase_orders",
            ["order_number", "customer_id", "status", "total_amount", "created_at", "updated_at"],
            order_rows,
        )
        cur.execute("SELECT currval(pg_get_serial_sequence('purchase_orders', 'id'))")
        last_id = cur.fetchone()[0]
        first_id = last_id - chunk_len + 1

        def remap_local_ids(rows: list[str]) -> list[str]:
            remapped = []
            for row in rows:
                local_id_str, rest = row.split("\t", 1)
                global_id = first_id + int(local_id_str) - 1
                remapped.append(f"{global_id}\t{rest}")
            return remapped

        copy_buffer(cur, "order_items", ["order_id", "product_id", "quantity", "unit_price", "line_total"], remap_local_ids(item_rows))
        copy_buffer(cur, "payments", ["order_id", "amount", "method", "status", "transaction_id", "paid_at"], remap_local_ids(payment_rows))
        copy_buffer(cur, "shipments", ["order_id", "shipping_address_id", "status", "carrier", "tracking_number", "shipped_at", "delivered_at"], remap_local_ids(shipment_rows))

        log(f"    ... orders {chunk_start + 1:,} – {chunk_end:,} ({100 * chunk_end / NUM_ORDERS:.1f}%)")

    log(f"  (even distribution: {orders_per_customer} orders per customer)")


def verify_counts(cur) -> None:
    checks = (
        ("categories", NUM_CATEGORIES),
        ("products", NUM_CATEGORIES * PRODUCTS_PER_CATEGORY),
        ("customers", NUM_CUSTOMERS),
        ("addresses", NUM_CUSTOMERS * 2),
        ("purchase_orders", NUM_ORDERS),
    )
    log("\nVerification:")
    for table, expected in checks:
        cur.execute(sql.SQL("SELECT COUNT(*) FROM {}").format(sql.Identifier(table)))
        count = cur.fetchone()[0]
        ok = "OK" if count == expected else "MISMATCH"
        log(f"  {table}: {count:,} (expected {expected:,}) [{ok}]")

    cur.execute("SELECT COUNT(*) FROM order_items")
    log(f"  order_items: {cur.fetchone()[0]:,}")
    cur.execute("SELECT COUNT(*) FROM payments")
    log(f"  payments: {cur.fetchone()[0]:,}")
    cur.execute("SELECT COUNT(*) FROM shipments")
    log(f"  shipments: {cur.fetchone()[0]:,}")

    cur.execute(
        """
        SELECT MIN(order_count), MAX(order_count), AVG(order_count)::numeric(10,2)
        FROM (
            SELECT customer_id, COUNT(*) AS order_count
            FROM purchase_orders
            GROUP BY customer_id
        ) t
        """
    )
    min_c, max_c, avg_c = cur.fetchone()
    log(f"  orders per customer: min={min_c}, max={max_c}, avg={avg_c}")


def parse_args() -> argparse.Namespace:
    global NUM_CATEGORIES, NUM_CUSTOMERS, NUM_ORDERS

    parser = argparse.ArgumentParser(description="Load realistic test data into orderdb.")
    parser.add_argument("--host", default=os.getenv("PGHOST", "localhost"))
    parser.add_argument("--port", type=int, default=int(os.getenv("PGPORT", "5432")))
    parser.add_argument("--database", default=os.getenv("PGDATABASE", "orderdb"))
    parser.add_argument("--user", default=os.getenv("PGUSER", "orderuser"))
    parser.add_argument("--password", default=os.getenv("PGPASSWORD", "orderpass"))
    parser.add_argument("--seed", type=int, default=42, help="Random seed for reproducibility")
    parser.add_argument("--categories", type=int, default=NUM_CATEGORIES)
    parser.add_argument("--customers", type=int, default=NUM_CUSTOMERS)
    parser.add_argument("--orders", type=int, default=NUM_ORDERS)
    parser.add_argument(
        "--skip-truncate",
        action="store_true",
        help="Do not truncate existing data (will likely fail on unique constraints)",
    )
    parser.add_argument(
        "--create-schema",
        action="store_true",
        help="Create tables if they do not exist (normally created by the Spring app)",
    )
    args = parser.parse_args()

    NUM_CATEGORIES = args.categories
    NUM_CUSTOMERS = args.customers
    NUM_ORDERS = args.orders
    if NUM_ORDERS % NUM_CUSTOMERS != 0:
        parser.error("--orders must be evenly divisible by --customers for even distribution")
    return args


def main() -> int:
    args = parse_args()
    rng = random.Random(args.seed)

    log("Connecting to PostgreSQL ...")
    conn = connect_db(args)
    conn.autocommit = False

    try:
        with conn.cursor() as cur:
            if args.create_schema:
                log("Ensuring schema exists ...")
                ensure_schema(cur)

            if not args.skip_truncate:
                log("Truncating existing data ...")
                cur.execute(TRUNCATE_SQL)

            t0 = time.perf_counter()
            log("Loading categories ...")
            load_categories(cur, rng)

            log("Loading products ...")
            catalog = load_products(cur, rng)

            log("Loading customers and addresses ...")
            load_customers_and_addresses(cur, rng)

            log("Loading orders (this will take a while) ...")
            load_orders(cur, rng, catalog)

            verify_counts(cur)
            conn.commit()
            elapsed = time.perf_counter() - t0
            log(f"\nDone in {elapsed / 60:.1f} minutes.")
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
