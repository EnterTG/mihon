"""Yomira remote library. Python 3.12+, standard library only."""
import hashlib
import hmac
import http.client
import ipaddress
import json
import os
from pathlib import Path
import re
import shutil
import socket
import sqlite3
import ssl
import tempfile
import threading
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urljoin, urlsplit
from contextlib import closing

MAX_PAGE = 64 * 1024 * 1024
MAX_CHAPTER = 2 * 1024 * 1024 * 1024
MAX_MANIFEST = 4 * 1024 * 1024
MAX_IMPORT = MAX_CHAPTER + MAX_MANIFEST
KEY = re.compile(r"[a-f0-9]{64}")
BLOCKED_HEADERS = {"host", "connection", "content-length", "transfer-encoding", "proxy-authorization", "accept-encoding"}


def chapter_key(metadata):
    return hashlib.sha256("\n".join(metadata[k] for k in ("sourceId", "mangaUrl", "chapterUrl")).encode()).hexdigest()


def sync_directory(path):
    descriptor = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def public_address(host, port):
    addresses = socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)
    if not addresses or any(not ipaddress.ip_address(a[4][0]).is_global for a in addresses):
        raise ValueError("Image host must resolve exclusively to public IP addresses")
    return addresses[0][4][0]


class PinnedHTTPSConnection(http.client.HTTPSConnection):
    def __init__(self, host, port, address):
        super().__init__(host, port, timeout=60, context=ssl.create_default_context())
        self.address = address

    def connect(self):
        sock = socket.create_connection((self.address, self.port), self.timeout)
        try:
            self.sock = self._context.wrap_socket(sock, server_hostname=self.host)
        except BaseException:
            sock.close()
            raise


def fetch_image(page, destination):
    """Pin validated public DNS results, including every redirect, to prevent SSRF/rebinding."""
    url = page["url"]
    headers = {k: v for k, v in page["headers"].items() if k.lower() not in BLOCKED_HEADERS}
    headers["Accept-Encoding"] = "identity"
    for _ in range(6):
        parsed = urlsplit(url)
        if parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.username or parsed.password:
            raise ValueError("Only HTTP(S) image URLs without credentials are supported")
        port = parsed.port or (443 if parsed.scheme == "https" else 80)
        if port not in (80, 443):
            raise ValueError("Image origins must use port 80 or 443")
        address = public_address(parsed.hostname, port)
        connection = (PinnedHTTPSConnection(parsed.hostname, port, address) if parsed.scheme == "https"
                      else http.client.HTTPConnection(address, port, timeout=60))
        request_headers = {**headers, "Host": parsed.netloc}
        try:
            connection.request("GET", (parsed.path or "/") + ("?" + parsed.query if parsed.query else ""), headers=request_headers)
            response = connection.getresponse()
            if response.status in (301, 302, 303, 307, 308):
                target = urljoin(url, response.getheader("Location", ""))
                target_parts = urlsplit(target)
                if parsed.scheme == "https" and target_parts.scheme != "https":
                    raise ValueError("HTTPS downgrade rejected")
                if (parsed.scheme, parsed.netloc) != (target_parts.scheme, target_parts.netloc):
                    headers = {k: v for k, v in headers.items() if k.lower() not in ("cookie", "authorization")}
                url = target
                continue
            if response.status != 200:
                raise ValueError("Image origin returned HTTP %s" % response.status)
            content_type = response.getheader("Content-Type", "").split(";")[0].lower()
            if not content_type.startswith("image/") and content_type != "application/octet-stream":
                raise ValueError("Image origin returned non-image content")
            size = 0
            with destination.open("wb") as output:
                while chunk := response.read(64 * 1024):
                    size += len(chunk)
                    if size > MAX_PAGE:
                        raise ValueError("Image exceeds size limit")
                    output.write(chunk)
            if not size:
                raise ValueError("Empty image")
            return size
        finally:
            connection.close()
    raise ValueError("Too many redirects")


class Library:
    def __init__(self, root, fetcher=fetch_image):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)
        self.fetcher = fetcher
        # Serialize commits/deletions and bound disk/network work. HTTP reads remain concurrent.
        self.lock = threading.Lock()
        for entry in self.root.glob(".pending-*"):
            shutil.rmtree(entry)

    def metadata(self, key):
        if not KEY.fullmatch(key):
            raise ValueError("Invalid chapter key")
        return json.loads((self.root / key / "metadata.json").read_text())

    def catalog(self):
        # Published chapter directories are immutable; downloads stay hidden until rename.
        result = []
        for entry in sorted(self.root.iterdir()):
            if KEY.fullmatch(entry.name):
                try:
                    result.append(self.metadata(entry.name))
                except FileNotFoundError:
                    pass  # Concurrent explicit deletion.
        return result

    def store(self, key, payload):
        metadata = payload.get("metadata")
        pages = payload.get("pages")
        fields = ("sourceId", "mangaUrl", "mangaTitle", "chapterUrl", "chapterName")
        if not isinstance(metadata, dict) or any(not isinstance(metadata.get(k), str) or not metadata[k] for k in fields):
            raise ValueError("Invalid metadata")
        if not KEY.fullmatch(key) or chapter_key(metadata) != key:
            raise ValueError("Chapter key does not match metadata")
        if not isinstance(pages, list) or not 1 <= len(pages) <= 2000:
            raise ValueError("Expected 1 to 2000 pages")
        for page in pages:
            if not isinstance(page, dict) or not isinstance(page.get("url"), str) or not isinstance(page.get("headers"), dict):
                raise ValueError("Invalid page")
            for name, value in page["headers"].items():
                if not re.fullmatch(r"[!#$%&'*+.^_`|~0-9A-Za-z-]+", name) or not isinstance(value, str) or any(c in value for c in "\r\n\x00"):
                    raise ValueError("Invalid image headers")
        with self.lock:
            if (self.root / key).exists():
                return self.metadata(key)
            pending = Path(tempfile.mkdtemp(prefix=".pending-", dir=self.root))
            try:
                size = 0
                for index, page in enumerate(pages):
                    size += self.fetcher(page, pending / str(index))
                    if size > MAX_CHAPTER:
                        raise ValueError("Chapter exceeds size limit")
                # Never persist origin URLs, cookies, or request headers.
                result = {k: metadata[k] for k in fields}
                result.update(key=key, pageCount=len(pages))
                (pending / "metadata.json").write_text(json.dumps(result, ensure_ascii=False), encoding="utf-8")
                pending.rename(self.root / key)
                return result
            finally:
                if pending.exists():
                    shutil.rmtree(pending)

    def import_chapter(self, key, stream, size):
        """Publish the verified device copy without changing an existing, different edition."""
        if not KEY.fullmatch(key):
            raise ValueError("Invalid chapter key")
        with tempfile.TemporaryDirectory(prefix=".pending-", dir=self.root) as work:
            work = Path(work)
            archive_path = work / "upload.zip"
            with archive_path.open("wb") as output:
                while size:
                    chunk = stream.read(min(size, 64 * 1024))
                    if not chunk:
                        raise ValueError("Incomplete upload")
                    output.write(chunk)
                    size -= len(chunk)
            pending = work / "chapter"
            pending.mkdir()
            with zipfile.ZipFile(archive_path) as archive:
                manifest_info = archive.getinfo("manifest.json")
                if not 0 < manifest_info.file_size <= MAX_MANIFEST:
                    raise ValueError("Invalid manifest size")
                manifest = json.loads(archive.read(manifest_info))
                metadata = manifest["metadata"]
                fields = ("sourceId", "mangaUrl", "mangaTitle", "chapterUrl", "chapterName")
                if any(not isinstance(metadata[k], str) or not 0 < len(metadata[k]) <= 8192 for k in fields):
                    raise ValueError("Invalid metadata")
                if chapter_key(metadata) != key:
                    raise ValueError("Chapter key does not match metadata")
                pages = manifest["pages"]
                if not isinstance(pages, list) or not 1 <= len(pages) <= 2000:
                    raise ValueError("Invalid page count")
                names = [page["entry"] for page in pages]
                with_metadata = "ComicInfo.xml" in archive.namelist()
                expected_names = ["manifest.json", *names, *(["ComicInfo.xml"] if with_metadata else [])]
                if len(set(expected_names)) != len(expected_names) or sorted(archive.namelist()) != sorted(expected_names):
                    raise ValueError("Invalid archive entries")
                hashes = []
                total = 0
                for index, page in enumerate(pages):
                    if not re.fullmatch(r"page-[0-9]{1,4}", page["entry"]) or not KEY.fullmatch(page["sha256"]):
                        raise ValueError("Invalid page")
                    info = archive.getinfo(page["entry"])
                    total += info.file_size
                    if not 0 < info.file_size <= MAX_PAGE or total > MAX_CHAPTER:
                        raise ValueError("Import exceeds size limit")
                    path = pending / str(index)
                    with archive.open(info) as source, path.open("wb") as output:
                        shutil.copyfileobj(source, output, 64 * 1024)
                        output.flush()
                        os.fsync(output.fileno())
                    with path.open("rb") as stored:
                        actual = hashlib.file_digest(stored, "sha256").hexdigest()
                    if actual != page["sha256"]:
                        raise ValueError("Imported page differs")
                    hashes.append(actual)

                comic_info_hash = manifest.get("comicInfoSha256")
                if with_metadata != (comic_info_hash is not None):
                    raise ValueError("Invalid ComicInfo metadata")
                if with_metadata:
                    info = archive.getinfo("ComicInfo.xml")
                    if not 0 < info.file_size <= MAX_MANIFEST:
                        raise ValueError("Invalid ComicInfo size")
                    data = archive.read(info)
                    if hashlib.sha256(data).hexdigest() != comic_info_hash:
                        raise ValueError("Imported ComicInfo differs")
                    with (pending / "ComicInfo.xml").open("wb") as output:
                        output.write(data)
                        output.flush()
                        os.fsync(output.fileno())

            metadata = {k: metadata[k] for k in fields}
            with self.lock:
                canonical = self.root / key
                if canonical.exists() and self.import_matches(key, hashes, None):
                    sidecar = canonical / "ComicInfo.xml"
                    if comic_info_hash is not None and not sidecar.exists():
                        (pending / "ComicInfo.xml").rename(sidecar)
                        sync_directory(canonical)
                if canonical.exists() and not self.import_matches(key, hashes, comic_info_hash):
                    edition = hashlib.sha256("".join(hashes + [comic_info_hash or ""]).encode()).hexdigest()
                    metadata["mangaUrl"] += "#yomira-local-import:" + edition
                stored_key = chapter_key(metadata)
                target = self.root / stored_key
                if not target.exists():
                    metadata.update(key=stored_key, pageCount=len(hashes))
                    with (pending / "metadata.json").open("w", encoding="utf-8") as output:
                        json.dump(metadata, output, ensure_ascii=False)
                        output.flush()
                        os.fsync(output.fileno())
                    sync_directory(pending)
                    pending.rename(target)
                elif not self.import_matches(stored_key, hashes, comic_info_hash):
                    raise ValueError("Existing import differs")
                sync_directory(self.root)
                return {"chapter": self.metadata(stored_key), "pageHashes": hashes, "comicInfoSha256": comic_info_hash}

    def import_matches(self, key, hashes, comic_info_hash):
        if self.metadata(key)["pageCount"] != len(hashes):
            return False
        try:
            for index, expected in enumerate(hashes):
                with (self.root / key / str(index)).open("rb") as page:
                    if hashlib.file_digest(page, "sha256").hexdigest() != expected:
                        return False
            if comic_info_hash is not None:
                with (self.root / key / "ComicInfo.xml").open("rb") as info:
                    return hashlib.file_digest(info, "sha256").hexdigest() == comic_info_hash
            return True
        except FileNotFoundError:
            return False

    def delete(self, key):
        if not KEY.fullmatch(key):
            raise ValueError("Invalid chapter key")
        with self.lock:
            shutil.rmtree(self.root / key, ignore_errors=True)

    def history_database(self):
        # Separate from image downloads: reading must not wait for a slow origin.
        db = sqlite3.connect(self.root / "history.sqlite3", timeout=30)
        db.execute("""CREATE TABLE IF NOT EXISTS history_events (
            event_id TEXT PRIMARY KEY, chapter_key TEXT NOT NULL,
            last_read INTEGER NOT NULL, duration INTEGER NOT NULL, payload TEXT NOT NULL
        )""")
        db.execute("CREATE INDEX IF NOT EXISTS history_chapter ON history_events(chapter_key, last_read)")
        return db

    def store_history(self, payload):
        fields = ("sourceId", "mangaUrl", "mangaTitle", "chapterUrl", "chapterName")
        if any(not isinstance(payload.get(k), str) or not 0 < len(payload[k]) <= 8192 for k in fields):
            raise ValueError("Invalid history metadata")
        if not re.fullmatch(r"[a-f0-9-]{36}", payload.get("eventId", "")):
            raise ValueError("Invalid history event ID")
        for name, limit in (("lastRead", 2**53 - 1), ("readDuration", 2**53 - 1), ("lastPageRead", 2**31 - 1)):
            if type(payload.get(name)) is not int or not 0 <= payload[name] <= limit:
                raise ValueError("Invalid history number")
        if type(payload.get("read")) is not bool:
            raise ValueError("Invalid read flag")
        key = chapter_key(payload)
        value = {k: payload[k] for k in (*fields, "eventId", "lastRead", "readDuration", "lastPageRead", "read")}
        value["key"] = key
        with closing(self.history_database()) as db, db:
            db.execute("INSERT OR IGNORE INTO history_events VALUES (?, ?, ?, ?, ?)",
                       (value["eventId"], key, value["lastRead"], value["readDuration"], json.dumps(value, ensure_ascii=False)))
            existing = db.execute("SELECT payload FROM history_events WHERE event_id = ?", (value["eventId"],)).fetchone()
            if json.loads(existing[0]) != value:
                raise ValueError("History event ID already used")
        return {"eventId": value["eventId"]}

    def history(self):
        with closing(self.history_database()) as db, db:
            rows = db.execute("""SELECT payload, total FROM (
                SELECT payload, SUM(duration) OVER (PARTITION BY chapter_key) AS total,
                    ROW_NUMBER() OVER (PARTITION BY chapter_key ORDER BY last_read DESC, event_id DESC) AS position
                FROM history_events
            ) WHERE position = 1 ORDER BY json_extract(payload, '$.lastRead') DESC""").fetchall()
        return [{**json.loads(payload), "readDuration": total} for payload, total in rows]

    def delete_history(self, key=None):
        with closing(self.history_database()) as db, db:
            if key is None:
                db.execute("DELETE FROM history_events")
            else:
                db.execute("DELETE FROM history_events WHERE chapter_key = ?", (key,))


def make_server(address, library, token):
    if len(token) < 32:
        raise ValueError("MIHON_TOKEN must contain at least 32 characters")

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def setup(self):
            super().setup()
            self.connection.settimeout(120)

        def log_message(self, *_):
            pass  # Do not log credentials or source URLs.

        def reply(self, status, value):
            body = json.dumps(value, ensure_ascii=False).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def authorized(self):
            if not hmac.compare_digest(self.headers.get("Authorization", "").encode(), ("Bearer " + token).encode()):
                self.close_connection = True
                self.reply(401, {"error": "Unauthorized"})
                return False
            return True

        def do_GET(self):
            if not self.authorized():
                return
            try:
                parts = self.path.split("/")
                if self.path == "/v1/chapters":
                    self.reply(200, library.catalog())
                elif self.path == "/v1/history":
                    self.reply(200, library.history())
                elif self.path == "/v1/imports":
                    self.reply(200, {"version": 1})
                elif len(parts) == 4 and parts[1:3] == ["v1", "chapters"]:
                    self.reply(200, library.metadata(parts[3]))
                elif len(parts) == 6 and parts[1:3] == ["v1", "chapters"] and parts[4] == "pages":
                    metadata = library.metadata(parts[3])
                    index = int(parts[5])
                    if not 0 <= index < metadata["pageCount"]:
                        raise FileNotFoundError()
                    with (library.root / parts[3] / str(index)).open("rb") as image:
                        self.send_response(200)
                        self.send_header("Content-Type", "application/octet-stream")
                        self.send_header("Content-Length", str(os.fstat(image.fileno()).st_size))
                        self.send_header("Cache-Control", "no-store")
                        self.end_headers()
                        shutil.copyfileobj(image, self.wfile)
                else:
                    self.reply(404, {"error": "Not found"})
            except FileNotFoundError:
                self.reply(404, {"error": "Chapter or page not found"})
            except (ValueError, KeyError):
                self.reply(400, {"error": "Invalid request"})
            except sqlite3.Error:
                self.reply(503, {"error": "History storage unavailable; retry later"})

        def do_POST(self):
            if not self.authorized():
                return
            try:
                parts = self.path.split("/")
                if self.path != "/v1/history" and (len(parts) != 4 or parts[1:3] != ["v1", "chapters"]):
                    self.reply(404, {"error": "Not found"})
                    return
                size = int(self.headers.get("Content-Length", "0"))
                if self.headers.get("Transfer-Encoding") or not 0 < size <= MAX_MANIFEST:
                    self.close_connection = True
                    self.reply(413, {"error": "Manifest too large or missing Content-Length"})
                    return
                payload = json.loads(self.rfile.read(size))
                if not isinstance(payload, dict):
                    raise ValueError()
                if self.path == "/v1/history":
                    self.reply(200, library.store_history(payload))
                else:
                    self.reply(200, library.store(parts[3], payload))
            except (ValueError, KeyError, TypeError):
                self.reply(400, {"error": "Invalid manifest or unsupported image origin"})
            except (OSError, http.client.HTTPException):
                self.reply(502, {"error": "Server could not download the chapter; retry later"})
            except sqlite3.Error:
                self.reply(503, {"error": "History storage unavailable; retry later"})

        def do_PUT(self):
            if not self.authorized():
                return
            # Always close this connection so early validation errors cannot leave unread body bytes.
            self.close_connection = True
            parts = self.path.split("/")
            if len(parts) != 5 or parts[1:3] != ["v1", "chapters"] or parts[4] != "import":
                self.reply(404, {"error": "Not found"})
                return
            try:
                size = int(self.headers.get("Content-Length", "0"))
                if self.headers.get("Transfer-Encoding") or not 0 < size <= MAX_IMPORT:
                    self.reply(413, {"error": "Import too large or missing Content-Length"})
                    return
                self.reply(200, library.import_chapter(parts[3], self.rfile, size))
            except (ValueError, KeyError, TypeError, zipfile.BadZipFile):
                self.reply(400, {"error": "Invalid chapter import"})
            except (OSError, RuntimeError, NotImplementedError):
                self.reply(503, {"error": "Server could not store the chapter"})

        def do_DELETE(self):
            if not self.authorized():
                return
            parts = self.path.split("/")
            if self.path == "/v1/history":
                library.delete_history()
                self.reply(200, {})
            elif len(parts) == 4 and parts[1:3] == ["v1", "history"] and KEY.fullmatch(parts[3]):
                library.delete_history(parts[3])
                self.reply(200, {})
            elif len(parts) == 4 and parts[1:3] == ["v1", "chapters"] and KEY.fullmatch(parts[3]):
                library.delete(parts[3])
                self.reply(200, {})
            else:
                self.reply(400, {"error": "Invalid chapter key"})

    return ThreadingHTTPServer(address, Handler)


if __name__ == "__main__":
    make_server(("0.0.0.0", 8080), Library(os.environ.get("MIHON_DATA", "/data")), os.environ.get("MIHON_TOKEN", "")).serve_forever()
