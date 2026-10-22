#!/usr/bin/env python3
"""Fetch the game input for the one-click GitHub Actions workflow.

    python tools/ci_fetch.py <url> <dest_dir> [--password <pw>]

Accepts anything a phone can hand over as a link:
  * a direct URL to default.xex, an .iso, a GoD/STFS package or an archive
  * a MediaFire share / download link (resolves via Chrome TLS + real browser fallback)
  * a Google Drive share link (any of the usual shapes) -- via gdown
  * a Dropbox share link (dl=0 is rewritten to dl=1)
  * a Pixeldrain link (pixeldrain.com/u/<id>)
  * a MEGA share link -- via mega.py
  * .zip / .7z / .rar archives (plain or password-protected, default password:
    LegionGamesGod) -> extracted; the default.xex (or ISO / GoD / STFS) inside
    is what the pipeline gets

Prints the container path rexauto.py should receive on the last stdout line
and writes it to $GITHUB_OUTPUT (container=...) when running in Actions.
"""
import argparse
import base64
import os
import re
import shutil
import subprocess
import sys
import urllib.parse

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)
import extract as _extract  # noqa: E402

DEFAULT_PASSWORD = "LegionGamesGod"
BROWSER_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)


def log(msg):
    print("[ci_fetch] " + msg, flush=True)


def _gdrive_id(url):
    u = urllib.parse.urlparse(url)
    if "drive.google.com" not in u.netloc and "docs.google.com" not in u.netloc:
        return None
    m = re.search(r"/file/d/([A-Za-z0-9_-]+)", u.path)
    if m:
        return m.group(1)
    q = urllib.parse.parse_qs(u.query)
    return q["id"][0] if "id" in q else None


def _mediafire_candidates(url):
    """Return candidate MediaFire share URLs with clean URLs tried before stale ?dkey= URLs."""
    u = urllib.parse.urlparse(url)
    host = u.netloc.lower()
    if "mediafire.com" not in host:
        return [url]
    out = []
    m = re.search(r"/(?:file|file_premium|download)/([A-Za-z0-9]+)(?:/([^/?#]+))?", u.path)
    if m:
        qk, slug = m.group(1), m.group(2)
        if slug and slug.lower() != "file":
            out.append("https://www.mediafire.com/file/%s/%s/file" % (qk, slug))
        out.append("https://www.mediafire.com/file/%s/file" % qk)
    elif host.startswith("download"):
        out.append(url)
        parts = [p for p in u.path.split("/") if p]
        if len(parts) >= 2:
            out.append("https://www.mediafire.com/file/%s/file" % parts[1])
    if url not in out:
        out.append(url)
    return out


def _extract_mediafire_direct_url(html_bytes):
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
        if m and "javascript:" not in m.group(1).lower():
            return m.group(1).strip()
    return None


def _sanitize_filename(name):
    name = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", name or "").strip(" .")
    return name or "download.bin"


def _filename_from_headers(headers, url):
    cd = ""
    for k, v in (headers or {}).items():
        if k.lower() == "content-disposition":
            cd = v
            break
    m = (
        re.search(r"filename\*=(?:UTF-8'')?\"?([^\";\r\n]+)", cd, re.I)
        or re.search(r'filename="?([^";\r\n]+)"?', cd, re.I)
    )
    if m:
        return _sanitize_filename(os.path.basename(urllib.parse.unquote(m.group(1).strip())))
    return _sanitize_filename(urllib.parse.unquote(os.path.basename(urllib.parse.urlparse(url).path)))


def _is_html_head(head_bytes, content_type=""):
    h = (head_bytes or b"")[:4096].lstrip().lower()
    return (
        "text/html" in (content_type or "").lower()
        or h.startswith(b"<!doctype html")
        or h.startswith(b"<html")
        or b"<html" in h[:1024]
    )


def _ensure_pkg(mod_name, pip_name=None):
    try:
        __import__(mod_name)
    except ImportError:
        subprocess.check_call([sys.executable, "-m", "pip", "install", "-q", pip_name or mod_name])


def _download_curl_cffi(url, dest_dir, cookies=None, referer=None, ua=None):
    """Stream using real Chrome TLS/HTTP2 impersonation via curl_cffi."""
    _ensure_pkg("curl_cffi")
    from curl_cffi import requests as creq

    headers = {
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "en-US,en;q=0.9,pt-BR;q=0.8",
        "Upgrade-Insecure-Requests": "1",
    }
    if ua:
        headers["User-Agent"] = ua
    if referer:
        headers["Referer"] = referer

    with creq.Session(impersonate="chrome") as sess:
        if cookies:
            sess.cookies.update(cookies)
        for cand in _mediafire_candidates(url):
            try:
                log("GET (chrome-tls) " + cand)
                resp = sess.get(cand, headers=headers, stream=True, allow_redirects=True, timeout=180)
                if resp.status_code >= 400:
                    log("HTTP %d on %s" % (resp.status_code, cand))
                    resp.close()
                    continue

                ctype = resp.headers.get("Content-Type", "")
                final_u = str(resp.url)
                it = resp.iter_content(chunk_size=1 << 20)
                first = b""
                for chunk in it:
                    if chunk:
                        first = chunk
                        break

                if not first:
                    resp.close()
                    continue

                if _is_html_head(first, ctype):
                    buf = bytearray(first)
                    for chunk in it:
                        buf.extend(chunk)
                        if len(buf) >= 512 * 1024:
                            break
                    resp.close()
                    direct = _extract_mediafire_direct_url(bytes(buf))
                    if direct:
                        log("resolved direct download link via Chrome TLS")
                        r2 = sess.get(
                            direct,
                            headers=dict(headers, Referer=final_u),
                            stream=True,
                            allow_redirects=True,
                            timeout=300,
                        )
                        if r2.status_code < 400:
                            ct2 = r2.headers.get("Content-Type", "")
                            it2 = r2.iter_content(chunk_size=1 << 20)
                            f2 = b""
                            for ch in it2:
                                if ch:
                                    f2 = ch
                                    break
                            if f2 and not _is_html_head(f2, ct2):
                                out_path = os.path.join(dest_dir, _filename_from_headers(r2.headers, str(r2.url)))
                                total = int(r2.headers.get("Content-Length") or 0)
                                got, last = len(f2), -1
                                with open(out_path, "wb") as out_f:
                                    out_f.write(f2)
                                    for ch in it2:
                                        if ch:
                                            out_f.write(ch)
                                            got += len(ch)
                                            if total >= (8 << 20):
                                                pct = got * 100 // total
                                                if pct // 10 != last:
                                                    last = pct // 10
                                                    log("  %d%% (%.1f / %.1f MB)" % (pct, got / 1e6, total / 1e6))
                                r2.close()
                                log("saved %s (%.1f MB)" % (out_path, got / 1e6))
                                return out_path
                        r2.close()
                    continue

                out_path = os.path.join(dest_dir, _filename_from_headers(resp.headers, final_u))
                total = int(resp.headers.get("Content-Length") or 0)
                got, last = len(first), -1
                with open(out_path, "wb") as out_f:
                    out_f.write(first)
                    for chunk in it:
                        if chunk:
                            out_f.write(chunk)
                            got += len(chunk)
                            if total >= (8 << 20):
                                pct = got * 100 // total
                                if pct // 10 != last:
                                    last = pct // 10
                                    log("  %d%% (%.1f / %.1f MB)" % (pct, got / 1e6, total / 1e6))
                resp.close()
                log("saved %s (%.1f MB)" % (out_path, got / 1e6))
                return out_path
            except Exception as ex:
                log("chrome-tls attempt note (%s): %s" % (cand, ex))
    return None


def _download_real_browser(url, dest_dir):
    """Fallback using real installed Chrome / Edge via Playwright to solve any JS /
    Cloudflare check and either stream with live browser cookies or let Chrome's
    native download manager save the file directly."""
    _ensure_pkg("playwright")
    from playwright.sync_api import sync_playwright

    target_urls = _mediafire_candidates(url)
    with sync_playwright() as p:
        browser = None
        for channel in ("chrome", "msedge", None):
            for headless in (False, True):
                try:
                    kw = {
                        "headless": headless,
                        "args": ["--disable-blink-features=AutomationControlled", "--no-sandbox"],
                    }
                    if channel:
                        kw["channel"] = channel
                    browser = p.chromium.launch(**kw)
                    log("launched real browser (%s, headless=%s)" % (channel or "chromium", headless))
                    break
                except Exception:
                    pass
            if browser:
                break
        if not browser:
            subprocess.check_call([sys.executable, "-m", "playwright", "install", "chromium"])
            browser = p.chromium.launch(headless=True, args=["--no-sandbox"])

        last_info = ""
        try:
            ctx = browser.new_context(accept_downloads=True, viewport={"width": 1280, "height": 800})
            ctx.add_init_script("Object.defineProperty(navigator, 'webdriver', {get: () => undefined});")
            page = ctx.new_page()

            for cand in target_urls:
                captured = []
                page.on("download", lambda dl, bucket=captured: bucket.append(dl))
                log("opening in browser: " + cand)
                try:
                    page.goto(cand, wait_until="domcontentloaded", timeout=90000)
                except Exception as nav_ex:
                    if not captured:
                        log("browser navigation note: %s" % nav_ex)

                for _ in range(20):
                    if captured:
                        break
                    try:
                        title = page.title().lower()
                    except Exception:
                        title = ""
                    if "just a moment" not in title and "attention required" not in title:
                        try:
                            if page.locator("#downloadButton, [data-scrambled-url], a.popsok").count() > 0:
                                break
                        except Exception:
                            pass
                    page.wait_for_timeout(1000)

                if not captured:
                    try:
                        html_live = page.content().encode("utf-8", "ignore")
                        direct = _extract_mediafire_direct_url(html_live)
                        if direct:
                            cookies = {c["name"]: c["value"] for c in ctx.cookies()}
                            ua = page.evaluate("() => navigator.userAgent")
                            got_path = _download_curl_cffi(direct, dest_dir, cookies=cookies, referer=page.url, ua=ua)
                            if got_path and os.path.isfile(got_path) and os.path.getsize(got_path) > 0:
                                return got_path
                    except Exception as dom_ex:
                        log("browser DOM check note: %s" % dom_ex)

                    for sel in (
                        "#downloadButton",
                        "a.popsok",
                        "[aria-label='Download file']",
                        "a[href*='download'][href*='mediafire.com']",
                        "form#download-form button, form#download-form input[type='submit']",
                        "#uc-download-link",
                        "button:has-text('Download anyway'), a:has-text('Download anyway')",
                        "a:has-text('Download'), button:has-text('Download')",
                    ):
                        if captured:
                            break
                        try:
                            loc = page.locator(sel).first
                            if loc.count() > 0 and loc.is_visible():
                                log("clicking %s in browser" % sel)
                                loc.click(timeout=10000)
                                for _ in range(30):
                                    if captured:
                                        break
                                    page.wait_for_timeout(500)
                        except Exception:
                            pass

                if captured:
                    dl = captured[0]
                    fname = _sanitize_filename(dl.suggested_filename or "download.bin")
                    out_path = os.path.join(dest_dir, fname)
                    log("browser downloading %s ..." % fname)
                    err = dl.failure()
                    if err:
                        raise RuntimeError("Browser download failed: %s" % err)
                    dl.save_as(out_path)
                    sz = os.path.getsize(out_path)
                    log("browser saved %s (%.1f MB)" % (out_path, sz / 1e6))
                    if sz > 0:
                        return out_path

                try:
                    body_txt = re.sub(r"\s+", " ", page.inner_text("body"))[:350]
                    last_info = "URL=%s | Title=%s | Page=%s" % (page.url, page.title(), body_txt)
                except Exception:
                    last_info = "URL=%s" % page.url

            raise SystemExit(
                "Could not download file from link (%s). "
                "Verify the link is still public/active and not DMCA-blocked." % last_info
            )
        finally:
            browser.close()


def download(url, dest_dir):
    os.makedirs(dest_dir, exist_ok=True)
    if any(ch.isspace() for ch in url):
        raise SystemExit("URL contains spaces or line breaks. Paste the complete URL.")

    gid = _gdrive_id(url)
    if gid:
        log("Google Drive link -> gdown (id=%s)" % gid)
        _ensure_pkg("gdown")
        import gdown
        out = gdown.download(id=gid, output=os.path.join(dest_dir, ""), quiet=False, fuzzy=True)
        if out and os.path.isfile(out):
            return out

    if "dropbox.com" in url:
        url = re.sub(r"[?&]dl=0", lambda m: m.group(0)[0] + "dl=1", url)
        if "dl=1" not in url:
            url += ("&" if "?" in url else "?") + "dl=1"

    m_pd = re.match(r"https?://(?:www\.)?pixeldrain\.com/u/([A-Za-z0-9]+)", url)
    if m_pd:
        url = "https://pixeldrain.com/api/file/%s?download" % m_pd.group(1)

    if "mega.nz" in url or "mega.co.nz" in url:
        log("MEGA link -> trying mega.py")
        _ensure_pkg("mega", "mega.py")
        from mega import Mega
        return str(Mega().login().download_url(url, dest_dir))

    got = _download_curl_cffi(url, dest_dir)
    if got and os.path.isfile(got) and os.path.getsize(got) > 0:
        return got

    log("falling back to real Chrome/Edge browser downloader...")
    return _download_real_browser(url, dest_dir)


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


def pick_container(root):
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
