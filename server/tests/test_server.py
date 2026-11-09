import http.client
import json
from pathlib import Path
import socket
import tempfile
import threading
import unittest
import uuid
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import patch
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from server import Library, chapter_key, make_server, public_address, fetch_image

TOKEN = "test-token-" + "a" * 32
META = {"sourceId": "9223372036854775807", "mangaUrl": "/manga/a", "mangaTitle": "Żółć", "chapterUrl": "/chapter/1", "chapterName": "1"}
KEY = chapter_key(META)
PAYLOAD = {"metadata": META, "pages": [{"url": "https://example.org/image.png", "headers": {"Cookie": "secret", "Referer": "https://example.org/"}}]}


class ServerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.downloads = []

        def fake_fetch(page, destination):
            self.downloads.append(page)
            destination.write_bytes(b"image bytes")
            return 11

        self.library = Library(self.temp.name, fake_fetch)
        self.server = make_server(("127.0.0.1", 0), self.library, TOKEN)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.temp.cleanup()

    def request(self, method, path, payload=None, token=TOKEN):
        conn = http.client.HTTPConnection(*self.server.server_address, timeout=5)
        headers = {"Authorization": "Bearer " + token}
        if payload is not None:
            headers["Content-Type"] = "application/json"
        conn.request(method, path, json.dumps(payload).encode() if payload is not None else None, headers)
        response = conn.getresponse()
        result = response.status, response.read()
        conn.close()
        return result

    def test_auth_required_for_reads_writes_and_deletes(self):
        for method in ("GET", "POST", "DELETE"):
            self.assertEqual(401, self.request(method, "/v1/chapters/" + KEY, PAYLOAD, token="wrong")[0])
        self.assertEqual([], self.downloads)

    def test_server_downloads_then_serves_without_persisting_secrets(self):
        status, body = self.request("POST", "/v1/chapters/" + KEY, PAYLOAD)
        self.assertEqual(200, status)
        self.assertEqual(1, json.loads(body)["pageCount"])
        self.assertEqual(1, len(self.downloads))
        self.assertEqual((200, b"image bytes"), self.request("GET", f"/v1/chapters/{KEY}/pages/0"))
        self.assertEqual(404, self.request("GET", f"/v1/chapters/{KEY}/pages/1")[0])
        persisted = (Path(self.temp.name) / KEY / "metadata.json").read_text()
        self.assertNotIn("secret", persisted)
        self.assertNotIn("example.org", persisted)
        self.assertEqual("Żółć", json.loads(persisted)["mangaTitle"])

    def test_idempotent_download_and_durable_catalog(self):
        for _ in range(2):
            self.assertEqual(200, self.request("POST", "/v1/chapters/" + KEY, PAYLOAD)[0])
        self.assertEqual(1, len(self.downloads))
        restarted = Library(self.temp.name)
        self.assertEqual(KEY, restarted.catalog()[0]["key"])
        self.assertEqual(200, self.request("DELETE", "/v1/chapters/" + KEY)[0])
        self.assertEqual([], restarted.catalog())
        self.assertEqual(404, self.request("GET", f"/v1/chapters/{KEY}/pages/0")[0])

    def test_failure_never_publishes_partial_chapter(self):
        def fail(*_):
            raise OSError("origin unavailable")
        self.library.fetcher = fail
        self.assertEqual(502, self.request("POST", "/v1/chapters/" + KEY, PAYLOAD)[0])
        self.assertEqual([], self.library.catalog())
        self.assertEqual([], list(Path(self.temp.name).iterdir()))

    def test_malformed_manifests_and_paths(self):
        cases = [[], {}, {"metadata": META, "pages": []},
                 {"metadata": META, "pages": [{"url": "https://x", "headers": {"Cookie": "a\r\nb"}}]}]
        for payload in cases:
            self.assertEqual(400, self.request("POST", "/v1/chapters/" + KEY, payload)[0])
        self.assertEqual(400, self.request("POST", "/v1/chapters/" + "0" * 64, PAYLOAD)[0])
        self.assertEqual(400, self.request("DELETE", "/v1/chapters/..")[0])
        self.assertEqual(400, self.request("GET", "/v1/chapters/..")[0])
        self.assertEqual([], self.downloads)

    def history_event(self, **changes):
        return {**META, "eventId": str(uuid.uuid4()), "lastRead": 1000,
                "readDuration": 300, "lastPageRead": 5, "read": False, **changes}

    def test_history_without_download_is_durable_and_idempotent(self):
        event = self.history_event()
        for _ in range(2):  # Retry after the server committed but its acknowledgement was lost.
            status, body = self.request("POST", "/v1/history", event)
            self.assertEqual(200, status)
            self.assertEqual(event["eventId"], json.loads(body)["eventId"])
        result = Library(self.temp.name).history()
        self.assertEqual(1, len(result))
        self.assertEqual(300, result[0]["readDuration"])
        self.assertEqual(KEY, result[0]["key"])
        self.assertEqual("Żółć", result[0]["mangaTitle"])
        self.assertEqual([], self.library.catalog())
        self.assertEqual([], self.downloads)

    def test_delayed_offline_event_does_not_rewind_progress(self):
        newest = self.history_event(lastRead=2000, lastPageRead=2, read=True)
        oldest = self.history_event(lastRead=1000, lastPageRead=7, read=False, readDuration=200)
        for event in (newest, oldest, oldest):
            self.assertEqual(200, self.request("POST", "/v1/history", event)[0])
        status, body = self.request("GET", "/v1/history")
        self.assertEqual(200, status)
        row, = json.loads(body)
        self.assertEqual(2, row["lastPageRead"])  # Rereading may legitimately move backwards.
        self.assertTrue(row["read"])
        self.assertEqual(500, row["readDuration"])
        self.assertEqual(2000, row["lastRead"])

    def test_history_auth_and_validation(self):
        for method in ("GET", "POST", "DELETE"):
            self.assertEqual(401, self.request(method, "/v1/history", self.history_event(), token="wrong")[0])
        invalid = [{}, [], self.history_event(lastRead=-1), self.history_event(readDuration=True),
                   self.history_event(lastPageRead=-1), self.history_event(read="true"),
                   self.history_event(eventId="../unsafe"), self.history_event(sourceId=42),
                   self.history_event(mangaTitle="x" * 8193), self.history_event(lastRead=2**63)]
        for event in invalid:
            self.assertEqual(400, self.request("POST", "/v1/history", event)[0])
        self.assertEqual([], self.library.history())

    def test_history_event_collision_rejected(self):
        event = self.history_event()
        self.assertEqual(200, self.request("POST", "/v1/history", event)[0])
        self.assertEqual(400, self.request("POST", "/v1/history", {**event, "readDuration": 900})[0])
        self.assertEqual(300, self.library.history()[0]["readDuration"])

    def test_concurrent_history_retries_count_once(self):
        event = self.history_event()
        with ThreadPoolExecutor(max_workers=8) as pool:
            statuses = list(pool.map(lambda _: self.request("POST", "/v1/history", event)[0], range(16)))
        self.assertEqual([200] * 16, statuses)
        self.assertEqual(300, self.library.history()[0]["readDuration"])

    def test_history_deletion_is_separate_from_images(self):
        first = self.history_event()
        second = self.history_event(chapterUrl="/chapter/2", lastRead=2000)
        for event in (first, second):
            self.request("POST", "/v1/history", event)
        self.assertEqual(200, self.request("DELETE", "/v1/chapters/" + KEY)[0])
        self.assertEqual(2, len(self.library.history()))
        self.assertEqual(200, self.request("DELETE", "/v1/history/" + KEY)[0])
        self.assertEqual(chapter_key(second), self.library.history()[0]["key"])
        self.assertEqual(200, self.request("DELETE", "/v1/history")[0])
        self.assertEqual([], Library(self.temp.name).history())
        self.assertEqual(400, self.request("DELETE", "/v1/history/..")[0])

    def test_weak_token_refused(self):
        with self.assertRaises(ValueError):
            make_server(("127.0.0.1", 0), self.library, "short")


class OriginSecurityTest(unittest.TestCase):
    def test_rejects_nonpublic_and_mixed_dns(self):
        for addresses in (["127.0.0.1"], ["192.168.1.10"], ["169.254.169.254"], ["::1"], ["8.8.8.8", "10.0.0.1"]):
            records = [(socket.AF_INET, socket.SOCK_STREAM, 6, "", (a, 443)) for a in addresses]
            with patch("server.socket.getaddrinfo", return_value=records), self.assertRaises(ValueError):
                public_address("example.org", 443)

    def test_accepts_public_dns_and_pins_address(self):
        with patch("server.socket.getaddrinfo", return_value=[(socket.AF_INET, socket.SOCK_STREAM, 6, "", ("8.8.8.8", 443))]):
            self.assertEqual("8.8.8.8", public_address("example.org", 443))

    def test_rejects_unsafe_urls_before_connecting(self):
        for url in ("file:///etc/passwd", "ftp://example.org/x", "http://u:p@example.org/x", "http://example.org:22/x"):
            with self.subTest(url=url), self.assertRaises(ValueError):
                fetch_image({"url": url, "headers": {}}, Path("unused"))

    def test_redirect_cannot_reach_private_network(self):
        conn = unittest.mock.Mock()
        response = conn.getresponse.return_value
        response.status = 302
        response.getheader.return_value = "http://192.168.1.1/image"
        with patch("server.public_address", side_effect=["8.8.8.8", ValueError("private")]), patch("server.http.client.HTTPConnection", return_value=conn):
            with self.assertRaises(ValueError):
                fetch_image({"url": "http://example.org/image", "headers": {}}, Path("unused"))
        self.assertEqual(1, conn.request.call_count)

    def test_cross_origin_redirect_strips_credentials(self):
        first = unittest.mock.Mock(status=302)
        first.getheader.return_value = "https://other.example/image"
        second = unittest.mock.Mock(status=200)
        second.getheader.return_value = "image/png"
        second.read.side_effect = [b"png", b""]
        conn = unittest.mock.Mock()
        conn.getresponse.side_effect = [first, second]
        with tempfile.TemporaryDirectory() as tmp, patch("server.public_address", return_value="8.8.8.8"), patch("server.PinnedHTTPSConnection", return_value=conn):
            fetch_image({"url": "https://example.org/image", "headers": {"Cookie": "secret", "Authorization": "secret"}}, Path(tmp) / "page")
        headers = conn.request.call_args_list[1].kwargs["headers"]
        self.assertNotIn("Cookie", headers)
        self.assertNotIn("Authorization", headers)


if __name__ == "__main__":
    unittest.main()
