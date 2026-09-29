"""Read-only local renderer/archive validation; run after native-render-smoke.js."""
import hashlib
import json
import pathlib
import tarfile
from PIL import Image

root = pathlib.Path(__file__).resolve().parents[1]
out = root / "test-output"
sources = {"hub_house": "House.prefab.json", "potion_shelf": "Potion Shelf.prefab.json", "aqua_lamp": "Aqua Lamp.prefab.json"}
with tarfile.open(out / "eternia-native-catalog.tar.gz", "r:gz") as archive:
    members = archive.getmembers()
    assert all(member.isfile() and member.mtime == 0 for member in members)
    files = {member.name: archive.extractfile(member).read() for member in members}
    assert len(files) == len(members) == 10
    manifest = json.loads(files["eternia-export.json"])
    assert manifest["format"] == "eternia-native-catalog-1"
    for directory in ["Buildings", "Props"]:
        prefix = f"Server/EterniaMod/{directory}/"
        entries = files[prefix + "catalog.index"].decode().splitlines()
        assert entries == sorted(set(entries))
        for filename in entries:
            definition = json.loads(files[prefix + filename])
            prefab_bytes = files["Server/Prefabs/" + definition["prefabPath"]]
            source = root.parent / "src/main/resources/Server/Prefabs" / sources[definition["id"]]
            assert json.loads(prefab_bytes) == json.loads(source.read_text(encoding="utf-8-sig"))
            revision = next(item for item in manifest["revisions"] if item["nativeId"] == definition["id"])
            assert revision["prefabSha256"] == hashlib.sha256(prefab_bytes).hexdigest()
            assert revision["prefabPath"] == definition["prefabPath"]
            if directory == "Buildings":
                assert definition["managementBlockLocalPos"] == [1, 2, 2]
                assert definition["spawnLocalPos"] == [0, 1, 0]
    assert next(item for item in manifest["revisions"] if item["nativeId"] == "potion_shelf")["requiresNativeEntityAdapter"]
for native_id in sources:
    for mode in ["screenshot", "icon"]:
        image = Image.open(out / f"native-{native_id}-{mode}.png")
        assert image.size == ((512, 512) if mode == "icon" else (1280, 800))
        if mode == "icon":
            assert image.mode == "RGBA"
            alpha = image.getchannel("A")
            assert alpha.getextrema() == (0, 255)
            assert alpha.getpixel((0, 0)) == 0
            assert alpha.getbbox() is not None
print("Verified 10 deterministic archive members; both native indexes resolve; all three prefab JSON payloads match game sources; SHA-256 manifest valid; all six PNG dimensions and transparent icon alpha correct.")
