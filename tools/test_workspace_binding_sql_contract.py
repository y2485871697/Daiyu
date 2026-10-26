"""Minimal executable SQLite contract, not a substitute for Android regression tests.
Run locally with: python3 tools/test_workspace_binding_sql_contract.py
"""
import sqlite3
import unittest


def install(db):
    for table in ("conversations", "composer"):
        if "workspace_binding" not in {r[1] for r in db.execute(f"PRAGMA table_info({table})")}:
            db.execute(f"ALTER TABLE {table} ADD COLUMN workspace_binding TEXT")


def cas(db, table, key, expected, next_value):
    if next_value is not None and (not next_value.strip() or len(next_value.encode("utf-8")) > 4096):
        raise ValueError("invalid binding")
    db.execute("BEGIN IMMEDIATE")
    try:
        cursor = db.execute(f"UPDATE {table} SET workspace_binding=? WHERE id=? AND workspace_binding IS ?",
                            (next_value, key, expected))
        if cursor.rowcount != 1:
            db.rollback()
            return False
        actual = db.execute(f"SELECT workspace_binding FROM {table} WHERE id=?", (key,)).fetchone()
        if actual != (next_value,):
            raise ValueError("readback mismatch")
        db.commit()
        return True
    except BaseException:
        db.rollback()
        raise


class WorkspaceBindingSQLContract(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:", isolation_level=None)
        self.db.executescript("CREATE TABLE conversations(id TEXT PRIMARY KEY, draft TEXT NOT NULL);"
                              "CREATE TABLE composer(id INTEGER PRIMARY KEY CHECK(id=1),draft TEXT NOT NULL);"
                              "INSERT INTO composer VALUES(1,'draft');"
                              "INSERT INTO conversations VALUES('a','');"
                              "INSERT INTO conversations VALUES('b','');")
        install(self.db)

    def tearDown(self):
        self.db.close()

    def test_migration_idempotent_and_nullable(self):
        install(self.db)
        self.assertEqual([(None,), (None,)], self.db.execute(
            "SELECT workspace_binding FROM conversations ORDER BY id").fetchall())
        self.assertEqual(('draft', None), self.db.execute("SELECT draft,workspace_binding FROM composer").fetchone())

    def test_isolation_and_stale_cas(self):
        self.assertTrue(cas(self.db, "conversations", 'a', None, 'A'))
        self.assertTrue(cas(self.db, "conversations", 'b', None, 'B'))
        self.assertTrue(cas(self.db, "composer", 1, None, 'C'))
        self.assertFalse(cas(self.db, "conversations", 'a', None, 'B'))
        self.assertFalse(cas(self.db, "conversations", 'missing', None, 'B'))
        self.assertEqual([('A',), ('B',)], self.db.execute(
            "SELECT workspace_binding FROM conversations ORDER BY id").fetchall())
        self.assertEqual(('C',), self.db.execute("SELECT workspace_binding FROM composer").fetchone())

    def test_bytes_and_readback_rollback(self):
        with self.assertRaises(ValueError):
            cas(self.db, "composer", 1, None, '汉' * 1366)
        self.assertTrue(cas(self.db, "composer", 1, None, 'x' * 4096))
        self.db.executescript("CREATE TRIGGER corrupt AFTER UPDATE OF workspace_binding ON conversations "
                             "BEGIN UPDATE conversations SET workspace_binding='bad' WHERE id=NEW.id; END;")
        with self.assertRaises(ValueError):
            cas(self.db, "conversations", 'a', None, 'A')
        self.assertEqual((None,), self.db.execute("SELECT workspace_binding FROM conversations WHERE id='a'").fetchone())

    def test_composer_transfer_failure_rolls_back(self):
        self.assertTrue(cas(self.db, "composer", 1, None, 'C'))
        self.db.execute("BEGIN IMMEDIATE")
        self.db.execute("INSERT INTO conversations(id,draft,workspace_binding) SELECT 'new','',workspace_binding FROM composer")
        self.db.execute("UPDATE composer SET draft='',workspace_binding=NULL WHERE id=1")
        self.db.rollback()
        self.assertEqual(('draft', 'C'), self.db.execute("SELECT draft,workspace_binding FROM composer").fetchone())
        self.assertIsNone(self.db.execute("SELECT 1 FROM conversations WHERE id='new'").fetchone())


if __name__ == '__main__':
    unittest.main()
