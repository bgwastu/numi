import re
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
FORBIDDEN = {chr(code) for code in (0x00B7, 0x2022, 0x2026, 0x2027, 0x22C5)}
ESCAPED = re.compile(r"\\u(?:00b7|2022|2026|2027|22c5)", re.IGNORECASE)


class AndroidCopyTest(unittest.TestCase):
    def test_authored_android_and_telegram_copy_has_no_unicode_dots(self):
        violations = []
        # Message bodies, contact data, training assets, and test fixtures are
        # deliberately excluded: a sender's content must remain unchanged.
        for module in ("app", "messages", "phone"):
            for path in (ROOT / "android" / module / "src" / "main").rglob("*"):
                if not path.is_file() or path.suffix not in (".kt", ".java", ".xml"):
                    continue
                text = path.read_text()
                for number, line in enumerate(text.splitlines(), 1):
                    if FORBIDDEN.intersection(line) or ESCAPED.search(line):
                        violations.append(f"{path.relative_to(ROOT)}:{number}")
                if path.suffix == ".xml":
                    # Catch numeric/XML character references as well as literals.
                    for node in ET.fromstring(text).iter():
                        value = (node.text or "") + "".join(node.attrib.values())
                        if FORBIDDEN.intersection(value):
                            violations.append(f"{path.relative_to(ROOT)} (decoded XML)")
        self.assertEqual([], violations, "Unicode dots in authored copy:\n" + "\n".join(violations))


if __name__ == "__main__":
    unittest.main()
