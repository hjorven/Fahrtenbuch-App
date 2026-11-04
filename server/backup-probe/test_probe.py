import os
import sqlite3
import tempfile
import time
import unittest
import zipfile

import probe


def db(pfad, n_fahrten):
    con = sqlite3.connect(pfad)
    for t in probe.TABELLEN:
        con.execute(f"CREATE TABLE {t} (id TEXT)")
    con.executemany("INSERT INTO fahrt VALUES (?)", [(str(i),) for i in range(n_fahrten)])
    con.execute("INSERT INTO users VALUES ('a')")
    con.commit()
    con.close()


class ProbeTest(unittest.TestCase):
    def setUp(self):
        self.t = tempfile.TemporaryDirectory()
        self.pb = self.t.name
        os.makedirs(os.path.join(self.pb, "backups"))
        db(os.path.join(self.pb, "data.db"), 5)

    def tearDown(self):
        self.t.cleanup()

    def backup(self, name, n_fahrten, alter_h=1, kaputt=False):
        z = os.path.join(self.pb, "backups", name)
        if kaputt:
            open(z, "wb").write(b"kein zip")
        else:
            d = os.path.join(self.pb, "b.db")
            db(d, n_fahrten)
            with zipfile.ZipFile(z, "w") as f:
                f.write(d, "data.db")
            os.remove(d)
        t = time.time() - alter_h * 3600
        os.utime(z, (t, t))

    def test_gut(self):
        self.backup("a.zip", 4)
        e = probe.probe(self.pb)
        self.assertTrue(e["ok"], e)
        self.assertEqual(e["im_backup"]["fahrt"], 4)

    def test_kein_backup(self):
        self.assertFalse(probe.probe(self.pb)["ok"])

    def test_zu_alt(self):
        self.backup("a.zip", 4, alter_h=72)
        self.assertIn("72 h", probe.probe(self.pb)["text"])

    def test_kaputt(self):
        self.backup("a.zip", 4, kaputt=True)
        self.assertEqual(probe.probe(self.pb)["text"], "Backup-Datei kaputt")

    def test_mehr_im_backup_als_live(self):
        self.backup("a.zip", 9)
        self.assertIn("passt nicht", probe.probe(self.pb)["text"])

    def test_neuestes_zaehlt(self):
        self.backup("alt.zip", 4, alter_h=100)
        self.backup("neu.zip", 5, alter_h=2)
        self.assertEqual(probe.probe(self.pb)["datei"], "neu.zip")


if __name__ == "__main__":
    unittest.main()
