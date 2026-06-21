#!/usr/bin/env python3
"""
Extraherar bilexpertinsikter från en YouTube-kanal och laddar upp till CarAdvice-databasen.

Krav:
    pip install yt-dlp youtube-transcript-api requests

Miljövariabler (sätt dem innan du kör):
    Windows:  set GROQ_API_KEY=gsk_...
              set ADMIN_KEY=din-admin-nyckel
    Mac/Linux: export GROQ_API_KEY=gsk_...
               export ADMIN_KEY=din-admin-nyckel

Körning:
    python extract_insights.py                  # Extraherar och sparar till CSV
    python extract_insights.py --upload         # Extraherar + laddar upp direkt
    python extract_insights.py --upload-only    # Laddar upp redan extraherad CSV
"""

import csv
import io
import json
import os
import subprocess
import sys
import time
import argparse
import requests
from youtube_transcript_api import YouTubeTranscriptApi, NoTranscriptFound, TranscriptsDisabled

# ── Konfiguration ─────────────────────────────────────────────────────────────
CHANNEL_URL  = "https://www.youtube.com/@PeterEsse/videos"
EXPERT_NAME  = "Peter Esse"
GROQ_API_KEY = os.environ.get("GROQ_API_KEY", "")
ADMIN_KEY    = os.environ.get("ADMIN_KEY", "")
API_URL      = os.environ.get("API_URL", "https://caradvice.onrender.com")
GROQ_MODEL   = "llama-3.3-70b-versatile"
OUTPUT_CSV   = "peter_esse_insights.csv"
PROGRESS_FILE = "peter_esse_progress.json"
DELAY_SECONDS = 3        # Sekunder mellan varje video (respekterar API-ratelimit)
MAX_TRANSCRIPT_CHARS = 6000  # Groq-kontextgräns

SYSTEM_PROMPT = """Du är en assistent som extraherar bilexpertinsikter från svenska YouTube-transkript.

Analysera transkriptet och extrahera KONKRETA insikter om specifika bilar eller bilkategorier.
Returnera ett JSON-objekt med fältet "insights" som en array.

Varje insikt ska ha exakt dessa fält:
- "car_make": biltillverkare (t.ex. "Volvo", "Toyota") eller "" om generell insikt
- "car_model": modell (t.ex. "EX30", "RAV4") eller ""
- "fuel_type": ett av: "elbil", "bensin", "diesel", "hybrid", "laddhybrid" — eller ""
- "category": ett av: "ekonomibil", "familjebil", "suv", "elbil", "laddhybrid", "smaabil" — eller ""
- "insight": 1-3 meningar på svenska med expertens konkreta åsikt eller fakta
- "rating": heltal 1-10 om experten ger betyg, annars ""

Regler:
- Inkludera BARA konkreta påståenden om bilar, inte generella diskussioner
- Varje insikt ska vara självbärande och kunna läsas utan videokontext
- Max 5 insikter per video — välj de mest värdefulla
- Om videon inte handlar om bilar: returnera {"insights": []}
- Svara ENDAST med valid JSON, inget annat
"""

# ── Hjälpfunktioner ───────────────────────────────────────────────────────────

def get_video_ids(channel_url):
    print(f"Hämtar videolista från {channel_url} ...")
    try:
        result = subprocess.run(
            ["yt-dlp", "--flat-playlist", "--get-id", channel_url],
            capture_output=True, text=True, timeout=120
        )
        ids = [line.strip() for line in result.stdout.strip().splitlines() if line.strip()]
        print(f"  Hittade {len(ids)} videor.\n")
        return ids
    except subprocess.TimeoutExpired:
        print("Timeout — försök igen.")
        sys.exit(1)
    except FileNotFoundError:
        print("yt-dlp saknas. Installera med: pip install yt-dlp")
        sys.exit(1)


def get_transcript(video_id):
    for langs in [["sv", "sv-SE"], ["en"]]:
        try:
            parts = YouTubeTranscriptApi.get_transcript(video_id, languages=langs)
            text = " ".join(p["text"] for p in parts)
            return text, langs[0]
        except (NoTranscriptFound, TranscriptsDisabled):
            continue
    return None, None


def extract_insights_groq(transcript, video_id, language):
    trimmed = transcript[:MAX_TRANSCRIPT_CHARS]
    try:
        r = requests.post(
            "https://api.groq.com/openai/v1/chat/completions",
            headers={"Authorization": f"Bearer {GROQ_API_KEY}", "Content-Type": "application/json"},
            json={
                "model": GROQ_MODEL,
                "messages": [
                    {"role": "system", "content": SYSTEM_PROMPT},
                    {"role": "user", "content": f"Transkript (språk: {language}):\n\n{trimmed}"}
                ],
                "max_tokens": 1000,
                "temperature": 0.2
            },
            timeout=30
        )
        r.raise_for_status()
        content = r.json()["choices"][0]["message"]["content"].strip()
        # Strip potential markdown code fences
        if content.startswith("```"):
            content = content.split("```")[1]
            if content.startswith("json"):
                content = content[4:]
        return json.loads(content).get("insights", [])
    except (json.JSONDecodeError, KeyError):
        print(f"  Kunde inte parsa JSON-svar för {video_id}")
        return []
    except requests.RequestException as e:
        print(f"  Groq-fel för {video_id}: {e}")
        return []


def load_progress():
    if os.path.exists(PROGRESS_FILE):
        with open(PROGRESS_FILE, "r", encoding="utf-8") as f:
            return json.load(f)
    return {"processed": [], "rows": []}


def save_progress(progress):
    with open(PROGRESS_FILE, "w", encoding="utf-8") as f:
        json.dump(progress, f, ensure_ascii=False, indent=2)


def save_csv(rows):
    with open(OUTPUT_CSV, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f, quoting=csv.QUOTE_ALL)
        writer.writerow(["car_make", "car_model", "fuel_type", "category", "insight", "rating"])
        writer.writerows(rows)
    print(f"\n✓ {len(rows)} insikter sparade i {OUTPUT_CSV}")


def upload_csv(rows):
    if not ADMIN_KEY:
        print("ADMIN_KEY saknas — kan inte ladda upp. Sätt miljövariabeln och kör med --upload-only.")
        return
    buf = io.StringIO()
    writer = csv.writer(buf, quoting=csv.QUOTE_ALL)
    writer.writerow(["car_make", "car_model", "fuel_type", "category", "insight", "rating"])
    writer.writerows(rows)
    csv_data = buf.getvalue()

    url = f"{API_URL}/api/admin/import/insights?expert={EXPERT_NAME.replace(' ', '+')}"
    print(f"\nLaddar upp till {url} ...")
    try:
        r = requests.post(
            url,
            headers={"X-Admin-Key": ADMIN_KEY, "Content-Type": "text/plain; charset=utf-8"},
            data=csv_data.encode("utf-8"),
            timeout=30
        )
        r.raise_for_status()
        print(f"✓ Uppladdning klar: {r.json()}")
    except requests.RequestException as e:
        print(f"Uppladdning misslyckades: {e}")


# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--upload", action="store_true", help="Ladda upp direkt efter extraktion")
    parser.add_argument("--upload-only", action="store_true", help="Ladda upp befintlig CSV utan att extrahera")
    args = parser.parse_args()

    if not GROQ_API_KEY and not args.upload_only:
        print("GROQ_API_KEY saknas.\nWindows: set GROQ_API_KEY=gsk_...\nMac/Linux: export GROQ_API_KEY=gsk_...")
        sys.exit(1)

    # ── Bara uppladdning ──────────────────────────────────────────────────────
    if args.upload_only:
        if not os.path.exists(OUTPUT_CSV):
            print(f"{OUTPUT_CSV} hittades inte. Kör utan --upload-only först.")
            sys.exit(1)
        rows = []
        with open(OUTPUT_CSV, newline="", encoding="utf-8") as f:
            reader = csv.reader(f)
            next(reader)  # hoppa över header
            rows = list(reader)
        upload_csv(rows)
        return

    # ── Extraktion ────────────────────────────────────────────────────────────
    progress = load_progress()
    processed = set(progress["processed"])
    all_rows = progress["rows"]

    if processed:
        print(f"Återupptar — {len(processed)} videor klara, {len(all_rows)} insikter hittade hittills.\n")

    video_ids = get_video_ids(CHANNEL_URL)
    remaining = [v for v in video_ids if v not in processed]
    print(f"{len(remaining)} videor kvar att processa.\n")

    for i, vid in enumerate(remaining, 1):
        print(f"[{i}/{len(remaining)}] https://youtu.be/{vid}")

        transcript, lang = get_transcript(vid)
        if not transcript:
            print("  Ingen textning — hoppar över.")
            progress["processed"].append(vid)
            save_progress(progress)
            time.sleep(1)
            continue

        print(f"  Transkript: {lang}, {len(transcript)} tecken")
        insights = extract_insights_groq(transcript, vid, lang)

        if insights:
            print(f"  ✓ {len(insights)} insikter:")
            for ins in insights:
                car = f"{ins.get('car_make','')} {ins.get('car_model','')}".strip() or "Generell"
                print(f"    — {car}: {ins.get('insight','')[:80]}...")
            for ins in insights:
                all_rows.append([
                    ins.get("car_make", ""),
                    ins.get("car_model", ""),
                    ins.get("fuel_type", ""),
                    ins.get("category", ""),
                    ins.get("insight", ""),
                    ins.get("rating", ""),
                ])
        else:
            print("  Inga relevanta bilinsikter.")

        progress["processed"].append(vid)
        progress["rows"] = all_rows
        save_progress(progress)
        time.sleep(DELAY_SECONDS)

    save_csv(all_rows)

    if args.upload:
        upload_csv(all_rows)
    else:
        print(f"\nGranska {OUTPUT_CSV} och kör sedan:")
        print(f"  python extract_insights.py --upload-only")

    # Rensa progress-filen när allt är klart
    if os.path.exists(PROGRESS_FILE):
        os.remove(PROGRESS_FILE)


if __name__ == "__main__":
    main()
