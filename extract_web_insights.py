#!/usr/bin/env python3
"""
Extraherar bilexpertinsikter från svenska motorsajter och laddar upp till CarAdvice-databasen.

Källor:
    - Teknikens Värld   (teknikensvarld.se, via sitemap — /feed/ blockeras med 406)
    - Vi Bilägare       (vibilagare.se, via RSS)
    - M Sverige         (msverige.se, biltester via artikellistan)
    - car.info          (ägaromdömen direkt på sidan)

Krav:
    pip install requests

Miljövariabler:
    GROQ_API_KEY  — Groq API-nyckel
    ADMIN_KEY     — admin-nyckel till caradvice.onrender.com

Körning:
    python extract_web_insights.py              # Extraherar och sparar till CSV (granska först)
    python extract_web_insights.py --upload     # Extraherar + laddar upp nya insikter

Skriptet är inkrementellt och säkert att schemalägga nattligen:
    - Redan processade artikel-URL:er hoppas över (web_insights_progress.json)
    - Ägaromdömen dedupliceras per recensent+datum
    - Misslyckade uppladdningar sparas och skickas om vid nästa körning
"""

import argparse
import csv
import html as html_lib
import io
import json
import os
import re
import sys
import time

import requests

# ── Konfiguration ─────────────────────────────────────────────────────────────
GROQ_API_KEY = os.environ.get("GROQ_API_KEY", "")
ADMIN_KEY    = os.environ.get("ADMIN_KEY", "")
API_URL      = os.environ.get("API_URL", "https://caradvice.onrender.com")
GROQ_MODEL   = os.environ.get("GROQ_MODEL", "openai/gpt-oss-120b")

PROGRESS_FILE = "web_insights_progress.json"
OUTPUT_CSV    = "web_insights.csv"
USER_AGENT    = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                 "(KHTML, like Gecko) Chrome/126.0 Safari/537.36")

DELAY_SECONDS = 5          # paus mellan Groq-anrop (rate limit)
FETCH_DELAY   = 1.5        # paus mellan sidhämtningar (artighet mot sajterna)
MAX_ARTICLE_CHARS = 7000   # trunkering av artikeltext inför Groq
MAX_ARTICLES_PER_SOURCE = 12  # per körning — nattliga deltan är små, förstakörningen betar av i omgångar

# mode "articles": hämta artikellänkar från listing/rss/sitemap, extrahera per artikel (dedup på URL)
# mode "page":     extrahera insikter direkt från sidan (dedup på recensent+datum via source_ref)
SOURCES = [
    {
        "expert": "Teknikens Värld",
        "mode": "articles",
        "discover": "sitemap",
        "url": "https://teknikensvarld.se/sitemap.xml",
        "kind": "artikel/test från motortidningen Teknikens Värld",
    },
    {
        "expert": "Vi Bilägare",
        "mode": "articles",
        "discover": "rss",
        "url": "https://www.vibilagare.se/rss.xml",
        "kind": "artikel/test från motortidningen Vi Bilägare",
    },
    {
        "expert": "M Sverige",
        "mode": "articles",
        "discover": "listing",
        "url": "https://msverige.se/allt-om-bilen/motor-testar/bilar/",
        "base": "https://msverige.se",
        "link_pattern": r'href="(/allt-om-bilen/motor-testar/bilar/[a-z0-9\-]+/?)"',
        "kind": "biltest från Riksförbundet M Sverige",
    },
    {
        "expert": "Bytbil",
        "mode": "articles",
        "discover": "listing",
        "url": "https://nybil.bytbil.com/posts",
        "base": "https://nybil.bytbil.com",
        "link_pattern": r'href="(/posts/[a-z0-9\-]+)"',
        "kind": "biltest/nybilsartikel från Bytbil",
    },
    {
        "expert": "M3",
        "mode": "articles",
        "discover": "rss",
        "url": "https://www.m3.se/feed/",
        # M3 är en teknikssajt — icke-bilartiklar ger tom insiktslista och filtreras bort
        "kind": "test/artikel från teknikmagasinet M3",
        "extra_urls": ["https://www.m3.se/article/1860897/basta-elbil-test.html"],
    },
    {
        "expert": "Bilägare (car.info)",
        "mode": "page",
        "url": "https://www.car.info/sv-se/user-reviews",
        "kind": "ägaromdömen från verkliga bilägare på car.info",
    },
    {
        "expert": "Folksam",
        "mode": "page",
        "url": "https://www.folksam.se/tester-och-goda-rad/vara-tester/hur-saker-ar-bilen",
        "kind": "Folksams krocksäkerhetsstudie 'Hur säker är bilen' baserad på verkliga olyckor",
    },
]

SYSTEM_PROMPT = """Du är en assistent som extraherar bilexpertinsikter ur text från svenska motorsajter.

Analysera texten och extrahera KONKRETA insikter om specifika bilar eller bilkategorier.
Returnera ett JSON-objekt med fältet "insights" som en array.

Varje insikt ska ha exakt dessa fält:
- "car_make": biltillverkare (t.ex. "Volvo", "Toyota") eller "" om generell insikt
- "car_model": modell (t.ex. "EX30", "RAV4") eller ""
- "fuel_type": ett av: "elbil", "bensin", "diesel", "hybrid", "laddhybrid" — eller ""
- "category": ett av: "ekonomibil", "familjebil", "suv", "elbil", "laddhybrid", "smaabil" — eller ""
- "insight": 1-3 meningar på svenska med källans konkreta åsikt eller fakta, i tredje person
- "rating": heltal 1-10 om källan ger betyg, annars ""
- "source_ref": för ägaromdömen: recensentens namn + datum (t.ex. "Andreas Skoglund 2026-06-12"); för testresultat om en specifik bil: "märke modell" (t.ex. "Volvo V60"); annars ""

Regler:
- Inkludera BARA konkreta påståenden om bilar (styrkor, svagheter, mätvärden, testresultat, kända fel)
- Ignorera navigationstext, annonser, medlemserbjudanden och orelaterat innehåll
- Varje insikt ska vara självbärande och kunna läsas utan artikelkontext
- Max 5 insikter per artikel, max 10 för sidor med många ägaromdömen
- Om texten inte innehåller något konkret om bilar: returnera {"insights": []}
- Svara ENDAST med valid JSON, inget annat
"""

# ── HTTP-hjälpare ─────────────────────────────────────────────────────────────

def fetch(url, timeout=25):
    r = requests.get(url, headers={
        "User-Agent": USER_AGENT,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "sv-SE,sv;q=0.9",
    }, timeout=timeout)
    r.raise_for_status()
    return r.text


def html_to_text(html):
    text = re.sub(r"(?is)<(script|style|noscript|svg|nav|footer|header)[^>]*>.*?</\1>", " ", html)
    text = re.sub(r"(?s)<!--.*?-->", " ", text)
    text = re.sub(r"<[^>]+>", " ", text)
    text = html_lib.unescape(text)
    return re.sub(r"\s+", " ", text).strip()


# ── Artikelupptäckt per källa ─────────────────────────────────────────────────

def discover_sitemap(url):
    """WordPress-sitemapindex: ta senaste post-sitemapen och returnera dess nyaste URL:er."""
    index = fetch(url)
    children = re.findall(r"<loc>([^<]+)</loc>", index)
    post_maps = [c for c in children if "post" in c.lower()] or children
    urls = []
    for child in post_maps[-1:]:  # nyaste post-sitemapen ligger sist
        xml = fetch(child)
        entries = re.findall(r"<url>\s*<loc>([^<]+)</loc>(?:\s*<lastmod>([^<]+)</lastmod>)?", xml)
        entries.sort(key=lambda e: e[1] or "", reverse=True)
        urls.extend(e[0] for e in entries)
    return urls


def discover_rss(url):
    xml = fetch(url)
    links = re.findall(r"<item>.*?<link>([^<]+)</link>", xml, flags=re.S)
    if not links:  # vissa flöden CDATA-wrappar länken
        links = re.findall(r"<item>.*?<link><!\[CDATA\[([^\]]+)\]\]></link>", xml, flags=re.S)
    return links


def discover_listing(source):
    html = fetch(source["url"])
    paths = re.findall(source["link_pattern"], html)
    listing_path = source["url"].replace(source["base"], "").rstrip("/")
    urls = []
    for p in paths:
        if p.rstrip("/") == listing_path:
            continue
        urls.append(source["base"] + p)
    return list(dict.fromkeys(urls))  # unika, bevarad ordning


def discover_articles(source):
    if source["discover"] == "sitemap":
        urls = discover_sitemap(source["url"])
    elif source["discover"] == "rss":
        urls = discover_rss(source["url"])
    else:
        urls = discover_listing(source)
    return list(dict.fromkeys(source.get("extra_urls", []) + urls))


# ── Groq-extraktion ───────────────────────────────────────────────────────────

def extract_insights_groq(text, kind, label):
    payload = {
        "model": GROQ_MODEL,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": f"Källa: {kind}\n\nText:\n\n{text[:MAX_ARTICLE_CHARS]}"},
        ],
        "max_tokens": 1500,
        "temperature": 0.2,
        # gpt-oss är en reasoning-modell — utan low kan hela tokenbudgeten gå åt till reasoning
        "reasoning_effort": "low",
    }
    for attempt in range(3):
        try:
            r = requests.post(
                "https://api.groq.com/openai/v1/chat/completions",
                headers={"Authorization": f"Bearer {GROQ_API_KEY}", "Content-Type": "application/json"},
                json=payload,
                timeout=45,
            )
            if r.status_code == 429:
                wait = 30 * (attempt + 1)
                print(f"  Rate limit — väntar {wait}s...")
                time.sleep(wait)
                continue
            r.raise_for_status()
            content = r.json()["choices"][0]["message"]["content"].strip()
            if content.startswith("```"):
                content = content.split("```")[1]
                if content.startswith("json"):
                    content = content[4:]
            return json.loads(content).get("insights", [])
        except (json.JSONDecodeError, KeyError):
            print(f"  Kunde inte parsa JSON-svar för {label}")
            return []
        except requests.RequestException as e:
            print(f"  Groq-fel för {label}: {e}")
            return []
    print(f"  Rate limit kvarstår efter 3 försök — hoppar över {label}")
    return []


# ── Progress & uppladdning ────────────────────────────────────────────────────

def load_progress():
    if os.path.exists(PROGRESS_FILE):
        with open(PROGRESS_FILE, "r", encoding="utf-8") as f:
            return json.load(f)
    return {"processed_urls": [], "seen_refs": [], "pending": {}}


def save_progress(progress):
    with open(PROGRESS_FILE, "w", encoding="utf-8") as f:
        json.dump(progress, f, ensure_ascii=False, indent=2)


def insight_to_row(ins):
    rating = ins.get("rating", "")
    return [
        ins.get("car_make", ""), ins.get("car_model", ""),
        ins.get("fuel_type", ""), ins.get("category", ""),
        ins.get("insight", ""), str(rating) if rating != "" else "",
    ]


def upload_rows(expert, rows):
    """Laddar upp rader för en expert. Returnerar True vid lyckad uppladdning."""
    buf = io.StringIO()
    writer = csv.writer(buf, quoting=csv.QUOTE_ALL)
    writer.writerow(["car_make", "car_model", "fuel_type", "category", "insight", "rating"])
    writer.writerows(rows)
    try:
        r = requests.post(
            f"{API_URL}/api/admin/import/insights",
            params={"expert": expert},
            headers={"X-Admin-Key": ADMIN_KEY, "Content-Type": "text/plain; charset=utf-8"},
            data=buf.getvalue().encode("utf-8"),
            timeout=30,
        )
        r.raise_for_status()
        print(f"  ✓ {expert}: {r.json()}")
        return True
    except requests.RequestException as e:
        print(f"  Uppladdning misslyckades för {expert}: {e}")
        return False


def append_csv(expert, rows):
    exists = os.path.exists(OUTPUT_CSV)
    with open(OUTPUT_CSV, "a", newline="", encoding="utf-8") as f:
        writer = csv.writer(f, quoting=csv.QUOTE_ALL)
        if not exists:
            writer.writerow(["expert", "car_make", "car_model", "fuel_type", "category", "insight", "rating"])
        for row in rows:
            writer.writerow([expert] + row)


# ── Källbearbetning ───────────────────────────────────────────────────────────

def process_article_source(source, progress):
    new_rows = []
    try:
        urls = discover_articles(source)
    except requests.RequestException as e:
        print(f"  Kunde inte hämta artikellista: {e}")
        return new_rows
    processed = set(progress["processed_urls"])
    fresh = [u for u in urls if u not in processed][:MAX_ARTICLES_PER_SOURCE]
    print(f"  {len(urls)} artiklar hittade, {len(fresh)} nya att processa")

    for url in fresh:
        try:
            time.sleep(FETCH_DELAY)
            text = html_to_text(fetch(url))
        except requests.RequestException as e:
            print(f"  Hoppar över {url}: {e}")
            continue
        if len(text) < 400:
            print(f"  För lite text ({len(text)} tecken): {url}")
            progress["processed_urls"].append(url)
            continue

        insights = extract_insights_groq(text, source["kind"], url)
        for ins in insights:
            if ins.get("insight"):
                new_rows.append(insight_to_row(ins))
        print(f"  [{len(insights)} insikter] {url}")
        progress["processed_urls"].append(url)
        save_progress(progress)
        time.sleep(DELAY_SECONDS)
    return new_rows


def process_page_source(source, progress):
    new_rows = []
    try:
        text = html_to_text(fetch(source["url"]))
    except requests.RequestException as e:
        print(f"  Kunde inte hämta sidan: {e}")
        return new_rows
    insights = extract_insights_groq(text, source["kind"], source["url"])
    seen = set(progress["seen_refs"])
    for ins in insights:
        ref = (ins.get("source_ref") or "").strip()
        key = f"{source['expert']}|{ref or ins.get('insight', '')[:60]}"
        if key in seen or not ins.get("insight"):
            continue
        new_rows.append(insight_to_row(ins))
        progress["seen_refs"].append(key)
    print(f"  {len(insights)} omdömen på sidan, {len(new_rows)} nya")
    save_progress(progress)
    time.sleep(DELAY_SECONDS)
    return new_rows


# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--upload", action="store_true", help="Ladda upp nya insikter efter extraktion")
    args = parser.parse_args()

    if not GROQ_API_KEY:
        print("GROQ_API_KEY saknas.")
        sys.exit(1)
    if args.upload and not ADMIN_KEY:
        print("ADMIN_KEY saknas — kan inte ladda upp.")
        sys.exit(1)

    progress = load_progress()
    total_new = 0

    for source in SOURCES:
        print(f"\n=== {source['expert']} ({source['url']}) ===")
        if source["mode"] == "page":
            rows = process_page_source(source, progress)
        else:
            rows = process_article_source(source, progress)
        if rows:
            append_csv(source["expert"], rows)
            progress["pending"].setdefault(source["expert"], []).extend(rows)
            save_progress(progress)
        total_new += len(rows)

    print(f"\n{total_new} nya insikter extraherade totalt.")

    if args.upload:
        print("\nLaddar upp väntande insikter...")
        for expert in list(progress["pending"].keys()):
            rows = progress["pending"][expert]
            if not rows:
                del progress["pending"][expert]
                continue
            if upload_rows(expert, rows):
                del progress["pending"][expert]
                save_progress(progress)
    else:
        pending_count = sum(len(v) for v in progress["pending"].values())
        if pending_count:
            print(f"\n{pending_count} insikter väntar på uppladdning — granska {OUTPUT_CSV} och kör med --upload.")


if __name__ == "__main__":
    main()
