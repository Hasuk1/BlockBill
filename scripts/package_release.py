#!/usr/bin/env python3
"""Package an already verified JAR, check local HTML links and every archived checksum."""
from hashlib import sha256
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import unquote, urlsplit
import json
import re
import shutil
import zipfile

ROOT = Path(__file__).resolve().parents[1]


class Links(HTMLParser):
    def __init__(self):
        super().__init__()
        self.links = []
        self.ids = set()

    def handle_starttag(self, tag, attrs):
        values = dict(attrs)
        if "href" in values:
            self.links.append(values["href"])
        if "id" in values:
            self.ids.add(values["id"])


def package():
    version = re.search(r"^version=(\S+)$", (ROOT / "gradle.properties").read_text(), re.M).group(1)
    if not re.fullmatch(r"\d+\.\d+\.\d+(?:-[\w.-]+)?", version):
        raise ValueError("Unexpected release version")
    jar_name = f"blockbill-{version}.jar"
    jar = ROOT / "build/libs" / jar_name
    with zipfile.ZipFile(jar) as archive:
        descriptor = json.loads(archive.read("fabric.mod.json"))
        assert descriptor["version"] == version and descriptor["id"] == "blockbill"
        assert descriptor["license"] == "CC0-1.0"
        assert not any("GameTest" in name or "RestartSmoke" in name for name in archive.namelist())
        for entry in ("dev/blockbill/BlockBillMod.class", "dev/blockbill/client/BlockBillClient.class"):
            assert entry in archive.namelist(), entry
        en = json.loads(archive.read("assets/blockbill/lang/en_us.json"))
        ru = json.loads(archive.read("assets/blockbill/lang/ru_ru.json"))
        assert en.keys() == ru.keys()
        for key in en:
            assert re.findall(r"%(?:\d+\$)?[sd]", en[key]) == re.findall(r"%(?:\d+\$)?[sd]", ru[key]), key

    destination = ROOT / "build/distributions" / f"BlockBill-{version}"
    if destination.exists():
        shutil.rmtree(destination)  # Only this generated version's directory, never source or worlds.
    destination.mkdir(parents=True)
    shutil.copy2(jar, destination / jar_name)
    shutil.copy2(ROOT / "LICENSE", destination)
    shutil.copytree(ROOT / "docs", destination / "docs", ignore=shutil.ignore_patterns("*.html"))
    html = (ROOT / "docs/install.html").read_text()
    html = html.replace(f"../build/libs/{jar_name}", jar_name)
    html = html.replace(f"https://github.com/Hasuk1/BlockBill/releases/download/v{version}/{jar_name}", jar_name)
    html = html.replace('href="PROGRESS.md"', 'href="docs/PROGRESS.md"')
    html = html.replace('href="../README.md"', 'href="https://github.com/Hasuk1/BlockBill#readme"')
    assert "<script" not in html.lower()
    (destination / "install.html").write_text(html)
    links = Links()
    links.feed(html)
    for link in links.links:
        url = urlsplit(link)
        if url.scheme:
            continue
        if url.path:
            assert (destination / unquote(url.path)).is_file(), link
        elif url.fragment:
            assert url.fragment in links.ids, link

    checksums = "".join(
        f"{sha256(path.read_bytes()).hexdigest()}  {path.relative_to(destination).as_posix()}\n"
        for path in sorted(destination.rglob("*")) if path.is_file()
    )
    (destination / "SHA256SUMS").write_text(checksums)
    zip_path = destination.parent / (destination.name + ".zip")
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(destination.rglob("*")):
            if path.is_file():
                # Stable timestamps and permissions make the same inputs byte-reproducible.
                info = zipfile.ZipInfo(path.relative_to(destination.parent).as_posix(), (1980, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o100644 << 16
                archive.writestr(info, path.read_bytes())
    with zipfile.ZipFile(zip_path) as archive:
        assert archive.testzip() is None
        for line in checksums.splitlines():
            expected, name = line.split("  ", 1)
            assert sha256(archive.read(f"{destination.name}/{name}")).hexdigest() == expected
    # Convenience standalone assets for GitHub Releases.
    standalone = (ROOT / "docs/install.html").read_text()
    standalone = standalone.replace('href="PROGRESS.md"',
                                    'href="https://github.com/Hasuk1/BlockBill/blob/main/docs/PROGRESS.md"')
    standalone = standalone.replace('href="../README.md"', 'href="https://github.com/Hasuk1/BlockBill#readme"')
    (destination.parent / "install.html").write_text(standalone)
    shutil.copy2(jar, destination.parent / jar_name)
    assets = [destination.parent / jar_name, zip_path, destination.parent / "install.html"]
    (destination.parent / "SHA256SUMS").write_text("".join(
        f"{sha256(path.read_bytes()).hexdigest()}  {path.name}\n" for path in assets
    ))
    print(json.dumps({"version": version, "assets": [
        {"file": str(path.relative_to(ROOT)), "bytes": path.stat().st_size,
         "sha256": sha256(path.read_bytes()).hexdigest()} for path in assets
    ]}, indent=2))


if __name__ == "__main__":
    package()
