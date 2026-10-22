#!/usr/bin/env python3
"""Fetch the game input for the one-click GitHub Actions workflow.

    python tools/ci_fetch.py <url> <dest_dir> [--password <pw>]

Accepts anything a phone can hand over as a link:
  * a direct URL to default.xex, an .iso, a GoD/STFS package or an archive
  * a MediaFire share / download link (resolves direct download link automatically)
  * a Google Drive share link (any of the usual shapes) -- via gdown
  * a Dropbox share link (dl=0 is rewritten to dl=1)
  * a MEGA share link -- via mega.py
  * .zip / .7z / .rar archives (plain or password-protected, default password:
    LegionGamesGod) -> extracted; the default.xex (or ISO / GoD / STFS) inside
    is what the pipeline gets

Prints the container path rexauto.py should receive on the last stdout line
and writes it to $GITHUB_OUTPUT (container=...) when running in Actions.
"""
import argparse
import base64
import http.cookiejar
import os
import re
import shutil
import subprocess
import sys
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)
import extract as _extract  # noqa: E402

DEFAULT_PASSWORD = "LegionGamesGod"
BROWSER_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
)
ARCHIVE_EXT = _extract.ARCHIVE_EXT
XEX_MAGIC = b"XEX2"


def log(msg):
    print("[ci_fetch] " + msg, flush=True)


# ---------------------------------------------------------------- downloading
def _gdrive_id(url):
    u = urllib.parse.urlparse(url)
    if "drive.google.com" not in u.netloc and "docs.google.com" not in u.netloc:
        return None
    m = re.search(r"/file/d/([A-Za-z0-9_-]+)", u.path)
    if m:
        return m.group(1)
    q = urllib.parse.parse_qs(u.query)
    if "id" in q:
        return q["id"][0]
    return None


def _mediafire_clean_share_url(url):
    """Extract canonical https://www.mediafire.com/file/<quickkey>/ from a
    MediaFire share link, expired ?dkey=... URL, or expired download host URL."""
    u = urllib.parse.urlparse(url)
    host = u.netloc.lower()
    if "mediafire.com" not in host:
        return None
    m = re.search(r"/(?:file|file_premium|download)/([A-Za-z0-9]+)", u.path)
    if m:
        return "https://www.mediafire.com/file/%s/" % m.group(1)
    if host.startswith("download"):
        parts = [p for p in u.path.split("/") if p]
        if len(parts) >= 2:
            return "https://www.mediafire.com/file/%s/" % parts[1]
    return None


def _extract_mediafire_direct_url(html_bytes):
    """Parse a MediaFire HTML download page for the direct file URL, handling
    both plain hrefs and base64 data-scrambled-url attributes."""
    html = html_bytes.decode("utf-8", "ignore")
    for m in re.finditer(r'data-scrambled-url=["\']([A-Za-z0-9+/=]+)["\']', html):
        try:
            decoded = base64.b64decode(m.group(1)).decode("utf-8", "ignore").strip()
            if decoded.startswith(("http://", "https://")):
                return decoded
        except Exception:
            pass
    for pat in (
        r'id=["\']downloadButton["\'][^>]*href=["\'](https?://[^"\']+)["\']',
        r'href=["\'](https?://[^"\']+)["\'][^>]*id=["\']downloadButton["\']',
        r'aria-label=["\']Download file["\'][^>]*href=["\'](https?://[^"\']+)["\']',
        r'href=["\'](https?://download[0-9a-z.-]*\.mediafire\.com/[^"\']+)["\']',
    ):
        m = re.search(pat, html, re.I)
        if m:
            candidate = m.group(1).strip()
            if "javascript:" not in candidate.lower():
                return candidate
    return None


def _sanitize_filename(name):
    name = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", name or "").strip(" .")
    return name or "download.bin"


def _filename_from_response(resp, url):
    cd = resp.headers.get("Content-Disposition", "")
    m = (
        re.search(r"filename\*=(?:UTF-8'')?\"?([^\";\r\n]+)", cd, re.I)
        or re.search(r'filename="?([^";\r\n]+)"?', cd, re.I)
    )
    if m:
        return _sanitize_filename(os.path.basename(urllib.parse.unquote(m.group(1).strip())))
    name = os.path.basename(urllib.parse.urlparse(url).path)
    return _sanitize_filename(urllib.parse.unquote(name))


def _http_fetch_to_file(opener, url, dest_dir):
    log("GET " + url)
    req = urllib.request.Request(url, headers={"User-Agent": BROWSER_UA})
    with opener.open(req, timeout=180) as resp:
        final_url = resp.geturl()
        content_type = resp.headers.get("Content-Type", "").lower()
        name = _filename_from_response(resp, final_url)
        path = os.path.join(dest_dir, name)
        total = int(resp.headers.get("Content-Length") or 0)
        got, last = 0, -1
        with open(path, "wb") as f:
            while True:
                chunk = resp.read(1 << 22)
                if not chunk:
                    break
                f.write(chunk)
                got += len(chunk)
                if total >= (8 << 20):
                    pct = got * 100 // total
                    if pct // 10 != last:
                        last = pct // 10
                        log("  %d%% (%.1f / %.1f MB)" % (pct, got / 1e6, total / 1e6))
        log("saved %s (%.1f MB)" % (path, got / 1e6))
    return path, got, content_type, final_url


def _looks_like_html(path, content_type):
    with open(path, "rb") as f:
        head = f.read(4096).lstrip().lower()
    return (
        "text/html" in content_type
        or head.startswith(b"<!doctype html")
        or head.startswith(b"<html")
        or b"<html" in head[:1024]
    )


def download(url, dest_dir):
    os.makedirs(dest_dir, exist_ok=True)

    gid = _gdrive_id(url)
    if gid:
        log("Google Drive link -> gdown (id=%s)" % gid)
        subprocess.check_call([
            sys.executable, "-m", "pip", "install", "-q", "gdown"
        ])
        import gdown
        out = gdown.download(
            id=gid, output=os.path.join(dest_dir, ""),
            quiet=False, fuzzy=True
        )
        if not out or not os.path.isfile(out):
            raise SystemExit(
                "gdown could not fetch the file -- check link permissions"
            )
        return out

    if "dropbox.com" in url:
        url = re.sub(r"[?&]dl=0", lambda m: m.group(0)[0] + "dl=1", url)
        if "dl=1" not in url:
            url += ("&" if "?" in url else "?") + "dl=1"

    if "mega.nz" in url or "mega.co.nz" in url:
        log("MEGA link -> trying mega.py")
        subprocess.check_call([
            sys.executable, "-m", "pip", "install", "-q", "mega.py"
        ])
        from mega import Mega
        return str(Mega().login().download_url(url, dest_dir))

    # Não tente corrigir silenciosamente tokens de download corrompidos.
    if any(ch.isspace() for ch in url):
        raise SystemExit(
            "URL contains spaces or line breaks. Paste the complete URL."
        )

    jar = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))

    path, got, content_type, final_url = _http_fetch_to_file(opener, url, dest_dir)

    if _looks_like_html(path, content_type):
        with open(path, "rb") as f:
            html_bytes = f.read(512 * 1024)
        os.remove(path)

        direct = _extract_mediafire_direct_url(html_bytes)
        if not direct:
            clean_mf = _mediafire_clean_share_url(url) or _mediafire_clean_share_url(final_url)
            if clean_mf and clean_mf.rstrip("/") != url.rstrip("/"):
                log("MediaFire token expired -> resolving clean share page %s" % clean_mf)
                page_path, _, _, _ = _http_fetch_to_file(opener, clean_mf, dest_dir)
                with open(page_path, "rb") as f:
                    html_bytes = f.read(512 * 1024)
                os.remove(page_path)
                direct = _extract_mediafire_direct_url(html_bytes)

        if direct:
            log("MediaFire page -> resolved direct download link")
            path, got, content_type, final_url = _http_fetch_to_file(opener, direct, dest_dir)

    if _looks_like_html(path, content_type):
        os.remove(path)
        raise SystemExit(
            "The link returned an HTML page instead of the game file. "
            "Use a direct download URL or a valid MediaFire / Google Drive / Dropbox link."
        )

    if got == 0:
        raise SystemExit("Downloaded file is empty.")

    return path


# ---------------------------------------------------------------- unpacking
def unpack(path, dest_dir, password=None):
    if not _extract.is_archive_file(path):
        return None
    pw = password if password is not None else (
        os.environ.get("ARCHIVE_PASSWORD")
        or os.environ.get("EXTRACT_PASSWORD")
        or DEFAULT_PASSWORD
    )
    out = os.path.join(dest_dir, "unpacked")
    os.makedirs(out, exist_ok=True)
    _extract.unpack_archive(path, out, password=pw, log=log)
    try:
        os.remove(path)
    except OSError:
        pass
    return out


# ---------------------------------------------------------------- selection
def pick_container(root):
    """Return the path rexauto.py should get: a folder containing default.xex,
    an ISO/GoD/STFS file, or the folder itself."""
    if os.path.isfile(root) and _extract._looks_like_xex(root):
        folder = os.path.dirname(root)
        want = os.path.join(folder, "default.xex")
        if os.path.abspath(root) != os.path.abspath(want):
            log("renaming %s -> default.xex" % os.path.basename(root))
            shutil.move(root, want)
        return folder
    return _extract.pick_unpacked_container(root)


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("url", help="Game URL (direct link, MediaFire, Google Drive, Dropbox, MEGA)")
    ap.add_argument("dest", help="Temporary directory for download and extraction")
    ap.add_argument(
        "-p", "--password",
        default=(
            os.environ.get("ARCHIVE_PASSWORD")
            or os.environ.get("EXTRACT_PASSWORD")
            or DEFAULT_PASSWORD
        ),
        help="Password to extract encrypted archives (default: %s)" % DEFAULT_PASSWORD,
    )
    args = ap.parse_args()
    url, dest = args.url.strip(), os.path.abspath(args.dest)
    if not url:
        raise SystemExit("empty URL")
    pw = args.password or DEFAULT_PASSWORD
    got = download(url, os.path.join(dest, "dl"))
    root = unpack(got, dest, password=pw) or got
    container = pick_container(root)
    log("container for rexauto: %s" % container)
    gh = os.environ.get("GITHUB_OUTPUT")
    if gh:
        with open(gh, "a", encoding="utf-8") as f:
            f.write("container=%s\n" % container)
    print(container)


if __name__ == "__main__":
    main()
