#!/usr/bin/env python3
"""Publish a checked release; read credentials only from a private file.

Run without --publish to validate local release inputs without credentials.
Never pass tokens as arguments or store them in the repository.
"""

import argparse
import hashlib
import json
from pathlib import Path
import sys
import urllib.error
import urllib.request
import uuid
import zipfile

API = "https://api.modrinth.com/v2"
ROOT = Path(__file__).resolve().parent.parent
PROJECT = "Q56AIoib"
FABRIC_API = "P7dR8mSH"


def request(method, endpoint, data=None, token=None, content_type="application/json"):
    headers = {"User-Agent": "adamhrnciar/combat-persistance release-tool (GitHub)",
               "Content-Type": content_type}
    if token:
        headers["Authorization"] = token
    req = urllib.request.Request(API + endpoint, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=120) as response:
            body = response.read()
            return json.loads(body) if body else None
    except urllib.error.HTTPError as error:
        # Do not print request headers, credentials or untrusted response bodies.
        raise RuntimeError(f"Modrinth {method} {endpoint} returned HTTP {error.code}") from None


def multipart(metadata, files):
    boundary = "CombatPersistence" + uuid.uuid4().hex
    parts = []
    for field, filename, content_type, content in [
        ("data", None, "application/json", json.dumps(metadata).encode()),
        *[(field, path.name, "application/octet-stream", path.read_bytes())
          for field, path in files],
    ]:
        disposition = f'Content-Disposition: form-data; name="{field}"'
        if filename:
            disposition += f'; filename="{filename}"'
        parts.append((f"--{boundary}\r\n{disposition}\r\nContent-Type: {content_type}"
                      "\r\n\r\n").encode() + content + b"\r\n")
    return b"".join(parts) + f"--{boundary}--\r\n".encode(), "multipart/form-data; boundary=" + boundary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--sources", type=Path, required=True,
                        help="Complete source JAR including Gradle build scripts and license")
    parser.add_argument("--token-file", type=Path)
    parser.add_argument("--publish", action="store_true")
    args = parser.parse_args()
    with zipfile.ZipFile(args.jar) as jar:
        manifest = json.loads(jar.read("fabric.mod.json"))
        assert manifest["id"] == "combatpersistence", "Unexpected mod ID"
        assert manifest["environment"] == "server", "Unexpected environment"
        assert manifest["depends"]["minecraft"] == "26.3", "Unexpected Minecraft target"
        assert any(name.endswith("jbcrypt-0.4.jar") for name in jar.namelist()), "Missing bundled BCrypt"
    assert args.sources.suffix.lower() == ".jar", "Modrinth source archives require the .jar extension"
    with zipfile.ZipFile(args.sources) as source:
        required = {"LICENSE", "build.gradle", "gradle.properties", "settings.gradle",
                    "gradlew", "gradle/wrapper/gradle-wrapper.jar",
                    "src/main/java/com/pottersgame/Combatpersistence.java"}
        assert required.issubset(source.namelist()), "Source archive lacks build inputs"
    version_number = manifest["version"]
    assert version_number == "1.10.0", "Unexpected release version"
    changelog = (ROOT / "docs/release/changelog.md").read_text()
    body = (ROOT / "MODRINTH.md").read_text()
    metadata = json.loads((ROOT / "docs/release/project-metadata.json").read_text())
    metadata["body"] = body
    digest = hashlib.sha512(args.jar.read_bytes()).hexdigest()
    source_digest = hashlib.sha512(args.sources.read_bytes()).hexdigest()
    print(json.dumps({"version": version_number, "game_versions": ["26.3"],
                      "jar": str(args.jar), "sha512": digest, "sources": str(args.sources)}))
    if not args.publish:
        return
    if args.token_file is None:
        parser.error("--publish requires --token-file from the private credential prompt")
    token = args.token_file.read_text().strip()
    if not token:
        raise RuntimeError("Credential file is empty")
    versions = request("GET", f"/project/{PROJECT}/version", token=token)
    existing = [v for v in versions if v["version_number"] == version_number]
    if existing:
        if len(existing) != 1:
            raise RuntimeError("Multiple versions share this number; manual review required")
        published = existing[0]
        if not any(f["primary"] and f["hashes"]["sha512"] == digest for f in published["files"]):
            raise RuntimeError("Published version has a different artifact; refusing replacement")
        if not any(f["filename"] == args.sources.name
                   and f["hashes"]["sha512"] == source_digest
                   and f.get("file_type") == "sources-jar" for f in published["files"]):
            raise RuntimeError("Published source archive differs or is missing; manual review required")
    else:
        release = {"project_id": PROJECT, "version_number": version_number,
                   "name": f"Combat Persistence {version_number} — Minecraft 26.3",
                   "changelog": changelog, "version_type": "release",
                   "game_versions": ["26.3"], "loaders": ["fabric"],
                   "environment": "dedicated_server_only",
                   "dependencies": [{"project_id": FABRIC_API, "version_id": None,
                                     "dependency_type": "required"}],
                   "featured": False, "file_parts": ["mod", "sources"], "primary_file": "mod",
                   "file_types": {"sources": "sources-jar"}}
        payload, content_type = multipart(release, [("mod", args.jar), ("sources", args.sources)])
        published = request("POST", "/version", payload, token, content_type)
    # Updating the listing after the artifact avoids advertising an unavailable release.
    request("PATCH", f"/project/{PROJECT}", json.dumps(metadata).encode(), token)
    verified = request("GET", f'/version/{published["id"]}', token=token)
    primary = next(f for f in verified["files"] if f["primary"])
    with urllib.request.urlopen(primary["url"], timeout=120) as download:
        downloaded = hashlib.sha512(download.read()).hexdigest()
    assert downloaded == digest, "Downloaded artifact hash mismatch"
    assert verified["game_versions"] == ["26.3"] and verified["loaders"] == ["fabric"]
    assert verified["environment"] == "dedicated_server_only", "Unexpected published environment"
    assert any(d.get("project_id") == FABRIC_API and d["dependency_type"] == "required"
               for d in verified["dependencies"]), "Fabric API dependency missing"
    assert any(f["filename"] == args.sources.name and f["hashes"]["sha512"] == source_digest
               and f.get("file_type") == "sources-jar" for f in verified["files"]), "Source archive mismatch"
    project = request("GET", f"/project/{PROJECT}", token=token)
    assert project["body"] == body and project["description"] == metadata["description"]
    print(json.dumps({"published": f'https://modrinth.com/mod/combat-persistence/version/{verified["id"]}',
                      "download_sha512_verified": True, "listing_verified": True,
                      "monetization_status": project.get("monetization_status")}))


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, RuntimeError, OSError, ValueError, KeyError, StopIteration) as error:
        print(f"Release failed: {error}", file=sys.stderr)
        sys.exit(1)
