"""Offline source-inventory contracts; no Gradle, adb, emulator or network."""
import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[2]
HELPER = ROOT / ".github/test-evidence/source_methods.py"
spec = importlib.util.spec_from_file_location("source_methods_under_test", HELPER)
source_methods = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = source_methods
spec.loader.exec_module(source_methods)


class SourceMethodsContract(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.sources = self.root / "app/src/androidTest/java"
        self.sources.mkdir(parents=True)

    def source(self, name, text):
        path = self.sources / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(textwrap.dedent(text), encoding="utf-8")
        return path

    def inventory(self, *classes):
        return source_methods.inventory(self.root, classes)

    def test_secondary_class_direct_methods_and_backticks_have_exact_receipts(self):
        path = self.source("OddFile.kt", '''
            package sample
            import org.junit.Test
            class First {
                @Test fun first() { }
                private class Nested { @Test fun excluded() {} }
                fun helper() { class Local { @Test fun excludedToo() {} } }
            }
            @RunWith(AndroidJUnit4::class)
            class ActualSelected {
                @Test
                @androidx.test.filters.SdkSuppress(minSdkVersion = 33)
                fun `preserve source spaces`() = firstOperation()
                @org.junit.Test(expected = IllegalArgumentException::class)
                fun second(): Unit { throw IllegalArgumentException() }
            }
        ''')
        result = self.inventory("sample.ActualSelected", "sample.First")
        self.assertEqual(result["selectors"], ["sample.ActualSelected", "sample.First"])
        self.assertEqual(result["methods"], ["sample.ActualSelected#`preserve source spaces`",
                                            "sample.ActualSelected#second", "sample.First#first"])
        self.assertIn("sample.ActualSelected#preserve source spaces", result["runtime_methods"])
        self.assertFalse(result["runtime_discovery_proven"])
        for receipt in result["classes"]:
            self.assertEqual(receipt["source"], path.relative_to(self.root).as_posix())
            self.assertEqual(receipt["sha256"], hashlib.sha256(path.read_bytes()).hexdigest())
            self.assertTrue(all(row["line"] > 0 for row in receipt["declarations"]))

    def test_comments_nested_comments_strings_and_multiline_literals_cannot_invent_tests(self):
        self.source("Strings.kt", '''
            package sample
            import org.junit.Test
            // class Fake { @Test fun ghost() {} }
            /* outer /* class Hidden { @Test fun ghost() {} } */ still comment */
            class Real {
                val text = "class Fake { @Test fun ghost() {} }"
                val multiline = """ @Test fun ghost() {} class Shadow {} """
                val escaped = "quote \\" @Test fun ghost() {}"
                val char = '{'
                @Test fun real() { val body = "@Test fun ghost() {}" }
            }
        ''')
        self.assertEqual(self.inventory("sample.Real")["methods"], ["sample.Real#real"])
        with self.assertRaises(source_methods.InventoryError):
            self.inventory("sample.Fake")

    def test_java_methods_annotations_throws_and_nested_helpers(self):
        self.source("Different.java", '''
            package sample;
            import org.junit.Test;
            public class JavaCase {
                @Test public void alpha() throws java.io.IOException { }
                @org.junit.Test(timeout = 50) @Deprecated public final void beta() { }
                class Helper { @Test public void nested() { } }
                String noise = "@Test public void ghost() {}";
            }
            class Secondary { @Test public void found() { } }
        ''')
        self.assertEqual(self.inventory("sample.JavaCase", "sample.Secondary")["methods"],
                         ["sample.JavaCase#alpha", "sample.JavaCase#beta", "sample.Secondary#found"])

    def test_kotlin_import_alias_resolves_test_instead_of_guessing_annotation_name(self):
        self.source("Alias.kt", '''
            package sample
            import org.junit.Test as Check
            class Alias { @Check fun works() {} }
        ''')
        self.assertEqual(self.inventory("sample.Alias")["methods"], ["sample.Alias#works"])

    def test_nested_multiline_templates_hide_declaration_looking_expression_text(self):
        self.source("Templates.kt", '''
            package sample
            import org.junit.Test
            class Real {
                val message = "before ${values.map { entry ->
                    "class Fake { @Test fun ghost() {} } ${entry}"
                }} after"
                val raw = """ ${"@Test fun ghost() {}"} class Hidden {} """
                @Test fun actual() { }
            }
        ''')
        self.assertEqual(self.inventory("sample.Real")["methods"], ["sample.Real#actual"])
        with self.assertRaises(source_methods.InventoryError):
            self.inventory("sample.Fake")

    def test_missing_empty_and_repeated_selected_classes_are_rejected(self):
        self.source("Empty.kt", "package sample\nclass Empty { fun helper() {} }")
        for classes in [("sample.Missing",), ("sample.Empty",), ("sample.Empty", "sample.Empty"),
                        (), ("",), ("sample.Empty#method",)]:
            with self.subTest(classes=classes), self.assertRaises(source_methods.InventoryError):
                self.inventory(*classes)

    def test_duplicate_class_declarations_across_files_are_rejected(self):
        for name in ("One.kt", "Two.kt"):
            self.source(name, "package sample\nimport org.junit.Test\nclass Same { @Test fun x() {} }")
        with self.assertRaisesRegex(source_methods.InventoryError, "exactly one"):
            self.inventory("sample.Same")

    def test_duplicate_source_and_normalized_runtime_method_ids_are_rejected(self):
        for second in ("same", "`same`"):
            with self.subTest(second=second):
                self.source("Duplicate.kt", "package sample\nimport org.junit.Test\nclass Duplicate { "
                            "@Test fun same() {} @Test fun " + second + "() {} }")
                with self.assertRaisesRegex(source_methods.InventoryError, "Duplicate"):
                    self.inventory("sample.Duplicate")

    def test_unparsed_test_declarations_fail_instead_of_omitting_coverage(self):
        declarations = ["@Test val value = 1", "@Test fun <T> generic() {}", "@Test fun String.extension() {}",
                        "@Test fun parameter(value: Int) {}", "@Test fun missing()", "@Test fun missing() = }",
                        "@Test suspend fun suspended() {}", "@Test private fun hidden() {}",
                        "private @Test fun hidden() {}", "@`Test` fun hidden() {}",
                        "@Test @Test fun repeatedAnnotation() {}", "@get:Test val value = 1",
                        "@other.Test fun custom() {}", "@Test fun returns(): Int = 1"]
        for declaration in declarations:
            with self.subTest(declaration=declaration):
                self.source("Bad.kt", "package sample\nimport org.junit.Test\nclass Bad { " + declaration + " }")
                with self.assertRaises(source_methods.InventoryError):
                    self.inventory("sample.Bad")

    def test_ambiguous_test_imports_and_inherited_dynamic_classes_fail_closed(self):
        snippets = [
            "import other.Test\nclass Bad { @Test fun x() {} }",
            "import org.junit.Test\nimport other.Test\nclass Bad { @Test fun x() {} }",
            "import org.junit.Test\nclass Bad : Parent() { @Test fun x() {} }",
            "import org.junit.Test\nabstract class Bad { @Test fun x() {} }",
            "import org.junit.Test\n@RunWith(Parameterized::class)\nclass Bad { @Test fun x() {} }",
            "import org.junit.Test\nclass Bad<T> { @Test fun x() {} }",
            "import org.junit.*\nimport other.*\nclass Bad { @Test fun x() {} }",
            "import org.junit.Test as Check\nimport other.Check\nclass Bad { @Check fun x() {} }",
        ]
        for snippet in snippets:
            with self.subTest(snippet=snippet):
                self.source("Bad.kt", "package sample\n" + snippet)
                with self.assertRaises(source_methods.InventoryError):
                    self.inventory("sample.Bad")

    def test_java_nonvoid_nonpublic_static_parameter_and_bodyless_methods_fail(self):
        for method in ["@Test public int x() { return 1; }", "@Test void x() {}",
                       "@Test public static void x() {}", "@Test public void x(int arg) {}",
                       "@Test public abstract void x();"]:
            with self.subTest(method=method):
                self.source("Bad.java", "package sample; import org.junit.Test; public class Bad { " + method + " }")
                with self.assertRaises(source_methods.InventoryError):
                    self.inventory("sample.Bad")

    def test_malformed_lexical_input_is_rejected_not_partially_inventoried(self):
        snippets = ["class Bad { @Test fun x() {}", "/* unfinished", 'class Bad { val s = "unfinished }',
                    "class Bad { @Test fun `bad() {} }", "class Bad { @Test fun x(] {} }"]
        for snippet in snippets:
            with self.subTest(snippet=snippet):
                self.source("Bad.kt", "package sample\nimport org.junit.Test\n" + snippet)
                with self.assertRaises(source_methods.InventoryError):
                    self.inventory("sample.Bad")

    def test_java_unicode_preprocessing_and_source_symlinks_are_not_misparsed(self):
        path = self.source("Bad.java", r"package sample; // \u000a class Bad {}")
        with self.assertRaises(source_methods.InventoryError):
            self.inventory("sample.Bad")
        path.unlink()
        target = self.root / "outside.kt"
        target.write_text("package sample\nclass Bad {}")
        path = self.sources / "Escape.kt"
        path.symlink_to(target)
        with self.assertRaises(source_methods.InventoryError):
            self.inventory("sample.Bad")

    def test_cli_exclusive_output_default_root_and_no_receipt_on_invalid_source(self):
        self.source("Good.kt", "package sample\nimport org.junit.Test\nclass Good { @Test fun actual() {} }")
        output = self.root / "expected.json"
        env = dict(os.environ, PYTHONDONTWRITEBYTECODE="1")
        command = [sys.executable, str(HELPER), "--classes", "sample.Good", "--output", str(output)]
        first = subprocess.run(command, cwd=self.root, env=env, text=True, capture_output=True)
        self.assertEqual(first.returncode, 0, first.stderr)
        self.assertEqual(json.loads(output.read_text())["methods"], ["sample.Good#actual"])
        original = output.read_bytes()
        again = subprocess.run(command, cwd=self.root, env=env, text=True, capture_output=True)
        self.assertEqual(again.returncode, 2)
        self.assertEqual(output.read_bytes(), original)
        invalid = self.root / "invalid.json"
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(source_methods.main(["--root", str(self.root), "--classes", "sample.Missing",
                                                 "--output", str(invalid)]), 2)
        self.assertFalse(invalid.exists())

    def test_real_repository_selected_classes_match_direct_methods_and_secondary_class(self):
        # Reads the real selected source snippets instead of relying only on parser-shaped fixtures.
        selectors = ["io.github.anastaci1a.localica.ListSearchTest",
                     "io.github.anastaci1a.localica.FrostedSurfaceTest",
                     "io.github.anastaci1a.localica.FloatingControlSystemMotionTest"]
        result = source_methods.inventory(ROOT, selectors)
        self.assertEqual(result["method_count"], 7)
        self.assertEqual(result["methods"], sorted([
            selectors[0] + "#visibleSearchVacancyRestoresWithoutCumulativeListMovement",
            selectors[0] + "#popupKeepsScopeAndBothListPositionsWhenOpeningAResult",
            selectors[1] + "#neutralFrostFollowsConfiguredContrastInBothInkPolarities",
            selectors[1] + "#listBackdropCannotPaintOverSiblingHeaderControls",
            selectors[1] + "#queueDrawerKeepsItsMaterialFromShelfThroughHeldExpansion",
            selectors[1] + "#glassTransmitsTheActualSceneWithSoftEdgesAndCrispControls",
            selectors[2] + "#liveSystemDisableSnapsAndCannotOverrideExplicitAppReduction",
        ]))
        self.assertTrue(result["classes"][2]["source"].endswith("FloatingControlMotionTest.kt"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
