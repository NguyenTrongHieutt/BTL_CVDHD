"""Probe a TH-02 origin using credential-free GETs. Requires Python 3."""
import argparse
import http.client
import json
import re
import sys
from urllib.parse import urlsplit


def check(origin):
    url = urlsplit(origin)
    if (url.scheme not in ("http", "https") or not url.hostname or url.username is not None
            or url.path not in ("", "/") or url.query or url.fragment):
        raise ValueError("expected an HTTP(S) origin without credentials, path, query or fragment")
    client = http.client.HTTPSConnection if url.scheme == "https" else http.client.HTTPConnection

    def get(path, status, media=None):
        conn = client(url.hostname, url.port, timeout=10)
        try:
            conn.request("GET", path)
            res = conn.getresponse()
            if res.status != status:
                raise ValueError(f"{path}: HTTP {res.status}, expected {status}")
            mime = res.getheader("Content-Type", "").split(";")[0].strip().lower()
            if media and mime not in media:
                raise ValueError(f"{path}: unexpected content type")
            return res.read(65536)
        finally:
            conn.close()

    page = get("/", 200, {"text/html"})
    bundle = re.search(rb'src="(/assets/[A-Za-z0-9_.-]+\.js)"', page)
    if b'id="root"' not in page or not bundle:
        raise ValueError("/: missing SPA root or built JavaScript entry")
    if not get(bundle[1].decode("ascii"), 200, {"text/javascript", "application/javascript"}):
        raise ValueError("empty SPA bundle")
    print("PASS SPA and JavaScript entry")

    problem = json.loads(get("/api/v1/platform/me", 401, {"application/problem+json"}))
    if not isinstance(problem, dict) or problem.get("type") != "/errors/common/unauthorized" or problem.get("status") != 401:
        raise ValueError("/api/v1/platform/me: expected backend authentication problem")
    print("PASS backend authentication boundary")

    health = json.loads(get("/ai/health", 200, {"application/json"}))
    if not isinstance(health, dict) or health.get("status") != "ok" or not health.get("version"):
        raise ValueError("/ai/health: expected healthy AI service and version")
    print("PASS AI health")

    get("/internal/catalog/product-changes", 404)
    print("PASS internal feed blocked")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("origin")
    try:
        check(parser.parse_args().origin)
    except (ValueError, OSError, http.client.HTTPException) as exc:
        sys.exit(f"FAIL {exc}")
