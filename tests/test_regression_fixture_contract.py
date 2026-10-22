from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class RegressionFixtureContractTests(unittest.TestCase):
    def test_catalog_seed_types_are_not_qualified_through_fixture_app(self):
        paths = (
            "ControllerChildModelsTest.kt",
            "ControllerChildRetentionTest.kt",
            "model/usage/ControllerUsageAttributionTest.kt",
        )
        for path in paths:
            with self.subTest(path=path):
                source = (ROOT / "app/src/androidTest/kotlin/app/daiyu/android" / path).read_text()
                for name in ("SavedCatalogModel", "CatalogModelParameters", "CatalogCommitResult"):
                    qualified = "app.daiyu.android.model.catalog." + name
                    self.assertIn("import " + qualified + "\n", source)
                    body = "\n".join(line for line in source.splitlines() if not line.startswith("import "))
                    self.assertNotIn(qualified, body)
                self.assertIn("CatalogModelParameters(localIdentity = child.id)", source)
                self.assertIn("== CatalogCommitResult.COMMITTED", source)

    def test_consuming_pty_poll_returns_on_first_success(self):
        source = (ROOT / "app/src/test/kotlin/app/daiyu/android/terminal/PtySessionHostTest.kt").read_text()
        helper = source.split("fun await(condition: () -> Boolean)", 1)[1].split("        try {", 1)[0]
        self.assertIn("2_000_000_000L", helper)
        self.assertIn("if (condition()) return", helper)
        self.assertNotIn("while (!condition()", helper)
        self.assertIn("assertTrue(condition())", helper)
        self.assertIn("contentEquals(\"tail\".toByteArray())", source)
        self.assertIn("assertEquals(\"\", session.readAvailable().getString(\"stdout_base64\"))", source)
