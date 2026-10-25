"""Fail-closed static JUnit4 method inventory for selected Android test source classes.

This is deliberately a small supported syntax parser, not a Kotlin/Java compiler or a
runtime discovery oracle. Only direct @org.junit.Test (or imported Test/alias) methods
of selected top-level concrete classes count. Inherited/generated/parameterized tests,
SDK filters and runner rewrites are not inferred. Reconciliation must reject every
missing/extra/duplicate runtime ID; never treat an SDK omission as an automatic pass.
Kotlin backtick names retain their source spelling; runtime_methods strips delimiters.

CLI: source_methods.py --root REPO --classes FQCN,FQCN --output FRESH_JSON
No Git, network, runtime tests or app files are modified. Output uses exclusive create.
"""
import argparse
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import re
import sys


class InventoryError(ValueError):
    """Source cannot establish an unambiguous exact method set."""


@dataclass(frozen=True)
class Token:
    text: str
    kind: str
    line: int


@dataclass
class Source:
    path: Path
    data: bytes
    tokens: list
    pairs: dict
    depths: list
    package: str
    tests: set
    conflicts: set
    classes: list


IDENT = re.compile(r"[A-Za-z_$][A-Za-z0-9_$]*")
FQCN = re.compile(r"[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+")


def fail(path, line, message):
    raise InventoryError(f"{path}:{line}: {message}")


def block_comment_end(text, start, path):
    i, nesting = start + 2, 1
    while nesting and i < len(text):
        if text.startswith("/*", i):
            nesting += 1
            i += 2
        elif text.startswith("*/", i):
            nesting -= 1
            i += 2
        else:
            i += 1
    if nesting:
        fail(path, text.count("\n", 0, start) + 1, "Unterminated block comment")
    return i


def template_end(text, start, path):
    i, depth = start, 1
    while i < len(text):
        if text.startswith("//", i):
            newline = text.find("\n", i)
            i = len(text) if newline < 0 else newline
        elif text.startswith("/*", i):
            i = block_comment_end(text, i, path)
        elif text[i] in ('"', "'"):
            i = literal_end(text, i, path)
        elif text[i] == "`":
            end = text.find("`", i + 1)
            if end < 0:
                fail(path, text.count("\n", 0, i) + 1, "Unclosed template identifier")
            i = end + 1
        else:
            if text[i] == "{":
                depth += 1
            elif text[i] == "}":
                depth -= 1
                if depth == 0:
                    return i + 1
            i += 1
    fail(path, text.count("\n", 0, start) + 1, "Unterminated string template")


def literal_end(text, start, path):
    quote = '"""' if text.startswith('"""', start) else text[start]
    i = start + len(quote)
    while i < len(text):
        if text.startswith(quote, i):
            return i + len(quote)
        if len(quote) == 1 and text[i] == "\\":
            i += 2
        elif path.suffix == ".kt" and quote != "'" and text.startswith("${", i):
            i = template_end(text, i + 2, path)
        elif text[i] == "\n" and len(quote) == 1:
            fail(path, text.count("\n", 0, start) + 1, "Unterminated single-line literal")
        else:
            i += 1
    fail(path, text.count("\n", 0, start) + 1, "Unterminated literal")


def tokenize(text, path):
    tokens, i, line = [], 0, 1
    while i < len(text):
        c = text[i]
        if c.isspace():
            line += c == "\n"
            i += 1
        elif text.startswith("//", i):
            end = text.find("\n", i)
            i = len(text) if end < 0 else end
        elif text.startswith("/*", i):
            end = block_comment_end(text, i, path)
            line += text[i:end].count("\n")
            i = end
        elif c in ('"', "'"):
            end = literal_end(text, i, path)
            tokens.append(Token("<literal>", "literal", line))
            line += text[i:end].count("\n")
            i = end
        elif c == "`":
            end = text.find("`", i + 1)
            if end < 0 or not text[i + 1:end].strip() or "\n" in text[i:end]:
                fail(path, line, "Malformed backtick identifier")
            tokens.append(Token(text[i:end + 1], "name", line))
            i = end + 1
        else:
            match = IDENT.match(text, i)
            if match:
                tokens.append(Token(match.group(), "name", line))
                i = match.end()
            elif c.isascii():
                tokens.append(Token(c, "symbol", line))
                i += 1
            else:
                fail(path, line, "Unsupported non-ASCII source token")
    return tokens


def delimiters(tokens, path):
    pairs, stack, depths = {}, [], []
    for i, token in enumerate(tokens):
        depths.append(tuple(value for value, _ in stack))
        if token.text in ("{", "(", "["):
            stack.append((token.text, i))
        elif token.text in ("}", ")", "]"):
            if not stack or stack[-1][0] != {"}": "{", ")": "(", "]": "["}[token.text]:
                fail(path, token.line, "Unbalanced delimiter")
            _, start = stack.pop()
            pairs[start] = i
            pairs[i] = start
    if stack:
        fail(path, tokens[stack[-1][1]].line, "Unclosed delimiter")
    return pairs, depths


def dotted(tokens, i):
    if i >= len(tokens) or tokens[i].kind != "name":
        return "", i
    parts = [tokens[i].text]
    i += 1
    while i + 1 < len(tokens) and tokens[i].text == "." and tokens[i + 1].kind == "name":
        parts.append(tokens[i + 1].text)
        i += 2
    return ".".join(parts), i


def read_source(path):
    data = path.read_bytes()
    text = data.decode("utf-8")
    # Java performs Unicode-escape translation before lexing, including inside comments.
    if path.suffix == ".java" and re.search(r"\\u+[0-9a-fA-F]{4}", text):
        fail(path, 1, "Java Unicode escapes require compiler parsing")
    tokens = tokenize(text, path)
    pairs, depths = delimiters(tokens, path)
    packages, imports = [], []
    for i, token in enumerate(tokens):
        if depths[i]:
            continue
        if token.text == "package":
            name, _ = dotted(tokens, i + 1)
            if not name:
                fail(path, token.line, "Unparsed package")
            packages.append(name)
        elif token.text == "import":
            name, end = dotted(tokens, i + 1)
            if name == "static":
                continue
            if end + 1 < len(tokens) and tokens[end].text == "." and tokens[end + 1].text == "*":
                name += ".*"
                end += 2
            alias = name.rsplit(".", 1)[-1]
            if end + 1 < len(tokens) and tokens[end].text == "as":
                alias = tokens[end + 1].text
            imports.append((name, alias))
    if len(packages) != 1:
        fail(path, 1, "Require exactly one explicit package")
    tests = {"org.junit.Test"}
    conflicts = set()
    for name, alias in imports:
        if name == "org.junit.Test":
            tests.add(alias)
        elif name == "org.junit.*":
            tests.add("Test")
        elif alias == "Test":
            conflicts.add(alias)
    conflicts.update(alias for name, alias in imports if alias in tests and name != "org.junit.Test")
    # A JUnit wildcard plus another wildcard cannot resolve the simple Test name here.
    if ("org.junit.*", "*") in imports and any(name.endswith(".*") and name != "org.junit.*" for name, _ in imports):
        if ("org.junit.Test", "Test") not in imports:
            conflicts.add("Test")
    classes = []
    for i, token in enumerate(tokens):
        if token.text != "class" or depths[i] or (i and tokens[i - 1].text in (":", ".")):
            continue
        if i + 1 >= len(tokens) or tokens[i + 1].kind != "name":
            fail(path, token.line, "Unparsed top-level class")
        name = tokens[i + 1].text
        body = None
        for j in range(i + 2, len(tokens)):
            if depths[j]:
                continue
            value = tokens[j].text
            if value == "{":
                body = j
                break
            if value in ("class", "interface", "object", "fun", "val", "var", ";"):
                break
        # Class annotations/modifiers are checked when this class is selected.
        classes.append((packages[0] + "." + name, i, body))
    return Source(path, data, tokens, pairs, depths, packages[0], tests, conflicts, classes)


def annotation(source, i):
    tokens = source.tokens
    name, end = dotted(tokens, i + 1)
    if not name:
        fail(source.path, tokens[i].line, "Unparsed annotation")
    if end < len(tokens) and tokens[end].text == ":":
        # Use-site annotations cannot be silently mistaken for method annotations.
        name, end = dotted(tokens, end + 1)
        if name in source.tests or name.endswith(".Test") or name == "Test":
            fail(source.path, tokens[i].line, "Test use-site annotation unsupported")
    if end < len(tokens) and tokens[end].text == "(":
        end = source.pairs[end] + 1
    return name, end


def class_prefix(source, start):
    """Walk only this declaration's contiguous annotations and class modifiers.

    A previous Kotlin declaration need not have a body/semicolon. Its constructor's
    closing parenthesis is not an annotation unless the complete preceding name has
    an @ owner and annotation parsing ends exactly at our current boundary.
    """
    tokens, pairs, depths = source.tokens, source.pairs, source.depths
    modifiers = {"public", "internal", "private", "protected", "final", "open", "abstract",
                 "sealed", "expect", "actual", "data", "enum", "annotation", "value", "inline"}
    cursor = start
    own_modifiers = []
    while cursor > 0:
        previous = cursor - 1
        if depths[previous] == depths[start] and tokens[previous].text in modifiers:
            own_modifiers.append(tokens[previous])
            cursor = previous
            continue
        name_end = pairs[previous] if tokens[previous].text == ")" else cursor
        name_start = name_end - 1
        if name_start < 0 or tokens[name_start].kind != "name":
            break
        while name_start >= 2 and tokens[name_start - 1].text == "." and tokens[name_start - 2].kind == "name":
            name_start -= 2
        at = name_start - 1
        if at < 0 or tokens[at].text != "@" or depths[at] != depths[start]:
            break
        _, end = annotation(source, at)
        if end != cursor:
            fail(source.path, tokens[at].line, "Ambiguous selected class annotation ownership")
        cursor = at
    return tokens[cursor:start], own_modifiers


def selected_methods(source, entry):
    cls, start, body = entry
    tokens, pairs, depths = source.tokens, source.pairs, source.depths
    if body is None:
        fail(source.path, tokens[start].line, "Selected class has no parsed body")
    # Do not silently claim inherited tests or parameterized/abstract discovery.
    prefix, own_modifiers = class_prefix(source, start)
    if any(t.text in ("abstract", "expect", "annotation", "enum", "data", "private", "protected") for t in own_modifiers):
        fail(source.path, tokens[start].line, "Selected class kind/modifiers unsupported")
    header = tokens[start + 2:body]
    if source.path.suffix == ".kt":
        if any(t.text in (":", "<", "(") and not depths[start + 2 + k] for k, t in enumerate(header)):
            fail(source.path, tokens[start].line, "Inherited/generic selected class unsupported")
    elif any(t.text in ("extends", "implements", "<") for t in header):
        fail(source.path, tokens[start].line, "Inherited/generic selected class unsupported")
    if any(t.text in ("Parameterized", "TestFactory", "TestTemplate") for t in prefix):
        fail(source.path, tokens[start].line, "Dynamic/parameterized selected class unsupported")
    direct_depth = depths[body] + ("{",)
    names, declarations = [], []
    for i in range(body + 1, pairs[body]):
        if tokens[i].text != "@" or depths[i] != direct_depth:
            continue
        name, cursor = annotation(source, i)
        if name not in source.tests:
            if name.strip("`") == "Test" or name.endswith(".Test"):
                fail(source.path, tokens[i].line, "Unresolved/ambiguous Test annotation")
            continue
        if name in source.conflicts:
            fail(source.path, tokens[i].line, "Conflicting Test imports")
        # Kotlin/Java permit modifiers before the annotation as well as after it.
        modifier_names = {"public", "internal", "final", "open", "override", "private",
                          "protected", "static", "abstract", "suspend", "external", "inline"}
        modifiers = []
        before = i - 1
        while before > body and tokens[before].text in modifier_names:
            modifiers.append(tokens[before].text)
            before -= 1
        # Skip additional annotations and ordinary method visibility/modifiers.
        while cursor < len(tokens):
            value = tokens[cursor].text
            if value == "@":
                extra, cursor = annotation(source, cursor)
                if extra in source.tests:
                    fail(source.path, tokens[i].line, "Duplicate Test annotation")
            elif value in ("public", "internal", "final", "open", "override", "private", "protected", "static", "abstract", "suspend", "external", "inline"):
                modifiers.append(value)
                cursor += 1
            else:
                break
        if any(m in modifiers for m in ("private", "protected", "static", "abstract", "suspend", "external", "inline")):
            fail(source.path, tokens[i].line, "Unsupported Test method modifiers")
        expected = "fun" if source.path.suffix == ".kt" else "void"
        if cursor + 2 >= len(tokens) or tokens[cursor].text != expected or tokens[cursor + 1].kind != "name" or tokens[cursor + 2].text != "(":
            fail(source.path, tokens[i].line, "Unparsed Test method declaration")
        method = tokens[cursor + 1].text
        if source.path.suffix == ".java" and (method.startswith("`") or "public" not in modifiers):
            fail(source.path, tokens[i].line, "Require public Java void Test method")
        args = cursor + 2
        if pairs[args] != args + 1:
            fail(source.path, tokens[i].line, "Test parameters unsupported")
        end = pairs[args] + 1
        if source.path.suffix == ".kt" and end + 1 < len(tokens) and tokens[end].text == ":":
            if tokens[end + 1].text != "Unit":
                fail(source.path, tokens[i].line, "Test return type unsupported")
            end += 2
        if source.path.suffix == ".java" and end < len(tokens) and tokens[end].text == "throws":
            end += 1
            while end < len(tokens) and (tokens[end].text in (".", ",") or tokens[end].kind == "name"):
                end += 1
        if end >= len(tokens) or tokens[end].text not in ("{", "="):
            fail(source.path, tokens[i].line, "Test body missing/unparsed")
        if tokens[end].text == "=" and (source.path.suffix != ".kt" or end + 1 >= len(tokens) or tokens[end + 1].text in ("}", "@", "fun", ";")):
            fail(source.path, tokens[i].line, "Test expression body missing/unparsed")
        if method in names:
            fail(source.path, tokens[i].line, "Duplicate method identity " + cls + "#" + method)
        names.append(method)
        declarations.append({"id": cls + "#" + method, "line": tokens[i].line})
    if not names:
        fail(source.path, tokens[start].line, "Selected class has no supported direct Test methods")
    runtime = [cls + "#" + name[1:-1] if name.startswith("`") else cls + "#" + name for name in names]
    if len(runtime) != len(set(runtime)):
        fail(source.path, tokens[start].line, "Duplicate normalized runtime method identity")
    return declarations, runtime


def inventory(root, selectors):
    root = Path(root).resolve()
    selected = list(selectors)
    if not selected or any(not FQCN.fullmatch(c) for c in selected) or len(set(selected)) != len(selected):
        raise InventoryError("Require nonempty, unique comma-separated FQCN class selectors")
    folder = root / "app/src/androidTest/java"
    if not folder.is_dir():
        raise InventoryError("Missing Android test source directory")
    candidates = {cls: [] for cls in selected}
    for path in sorted(folder.rglob("*")):
        if path.suffix not in (".kt", ".java") or not path.is_file():
            continue
        if path.is_symlink() or not path.resolve().is_relative_to(folder.resolve()):
            raise InventoryError("Source symlink/escape refused: " + str(path))
        source = read_source(path)
        for entry in source.classes:
            if entry[0] in candidates:
                candidates[entry[0]].append((source, entry))
    classes, methods, runtime = [], [], []
    for cls in selected:
        matches = candidates[cls]
        if len(matches) != 1:
            raise InventoryError(f"Require exactly one source declaration for {cls}; found {len(matches)}")
        source, entry = matches[0]
        declarations, runtime_ids = selected_methods(source, entry)
        values = [item["id"] for item in declarations]
        methods.extend(values)
        runtime.extend(runtime_ids)
        classes.append({"class": cls, "source": source.path.relative_to(root).as_posix(),
                        "sha256": hashlib.sha256(source.data).hexdigest(), "methods": values,
                        "declarations": declarations})
    if len(methods) != len(set(methods)) or len(runtime) != len(set(runtime)):
        raise InventoryError("Duplicate selected method identities")
    return {"schema_version": 1, "selectors": selected, "methods": sorted(methods),
            "runtime_methods": sorted(runtime), "classes": classes,
            "method_count": len(methods), "runtime_discovery_proven": False,
            "limits": "Static direct JUnit4 declarations only. Runtime missing/extra/duplicate IDs must fail closed; inherited/generated/parameterized/SDK-filter discovery is not inferred."}


def main(argv=None):
    os.umask(0o002)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".")
    parser.add_argument("--classes", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args(argv)
    try:
        result = inventory(args.root, args.classes.split(","))
        # Compute/validate everything before creating any output; never overwrite receipts.
        with Path(args.output).open("x", encoding="utf-8") as stream:
            stream.write(json.dumps(result, indent=2) + "\n")
    except (OSError, ValueError) as error:
        print("Source method inventory rejected: " + str(error), file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
