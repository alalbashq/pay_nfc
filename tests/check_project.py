"""Validate the Android XML and the shared protocol wiring without the SDK."""
from pathlib import Path
import re
import sqlite3
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
APPS = ROOT / "apps"
ANDROID = "{http://schemas.android.com/apk/res/android}"
CORE = ROOT / "packages/payment-core/src/main/java/com/ghallah/core"


class ProjectChecks(unittest.TestCase):
    def test_all_android_xml_is_well_formed(self):
        resources = list(ROOT.rglob("src/main/**/*.xml"))
        self.assertGreaterEqual(len(resources), 10)
        for resource in resources:
            with self.subTest(resource=resource.name):
                ET.parse(resource)

    def test_app_ids_and_no_network_permissions(self):
        for role in ("pos", "pay"):
            app = APPS / f"ghallah-{role}/android"
            manifest = ET.parse(app / "src/main/AndroidManifest.xml")
            build = (app / "build.gradle.kts").read_text()
            self.assertIn(f'applicationId = "com.ghallah.{role}"', build)
            self.assertEqual(
                {node.attrib[ANDROID + "name"] for node in manifest.findall("uses-permission")},
                {"android.permission.NFC"},
            )

    def test_sqlite_schema_rejects_duplicate_ids_and_nonces(self):
        source = (CORE / "Ledger.kt").read_text()
        schemas = re.findall(r'db.execSQL\("""(CREATE TABLE .*?)"""\)', source, re.S)
        self.assertEqual(len(schemas), 2)
        with sqlite3.connect(":memory:") as db:
            for schema in schemas:
                db.execute(schema)
            insert = (
                "INSERT INTO transactions(id, nonce, request_payload, wire, request_hash, "
                "status, peer, delivery_status, updated_at) VALUES (?,?,?,?,?,?,?,?,?)"
            )
            rest = ("{}", b"wire", "hash", "PENDING_APPROVAL", "peer", "NOT_AVAILABLE", 0)
            db.execute(insert, ("id", "nonce", *rest))
            with self.assertRaises(sqlite3.IntegrityError):
                db.execute(insert, ("id", "other-nonce", *rest))
            with self.assertRaises(sqlite3.IntegrityError):
                db.execute(insert, ("other-id", "nonce", *rest))

    def test_aid_and_hce_service_registration(self):
        main = APPS / "ghallah-pay/android/src/main"
        config = ET.parse(main / "res/xml/gnpp_service.xml")
        group = config.find("aid-group")
        self.assertEqual(group.attrib[ANDROID + "category"], "other")
        kotlin = (CORE / "Protocol.kt").read_text()
        values = re.search(r"val aid = byteArrayOf\((.*)\)", kotlin).group(1)
        aid = "".join(value[2:].upper() for value in re.findall(r"0x[\da-f]{2}", values))
        self.assertEqual(aid, group.find("aid-filter").attrib[ANDROID + "name"])
        service = ET.parse(main / "AndroidManifest.xml").find("application/service")
        self.assertEqual(service.attrib[ANDROID + "permission"], "android.permission.BIND_NFC_SERVICE")


if __name__ == "__main__":
    unittest.main(verbosity=2)
