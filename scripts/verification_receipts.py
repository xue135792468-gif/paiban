#!/usr/bin/env python3
"""Verification run receipts: run the real gate and archive byte-exact evidence.

This module is the implementation behind the two thin Bash front ends:

    bash scripts/verify-run.sh <run-id> [--quick|--full|--deploy]
    bash scripts/check-verification-receipts.sh [--staged|--worktree] [run-id]

Design rules (kept deliberately narrow, this is not a generic task runner):

* only ``scripts/verify.sh`` is executed; there is no way to substitute a
  command, a shell string, or an eval of user input;
* ``<run-id>`` is a single safe path segment, existing run ids are never
  overwritten, and every output path is resolved and must stay inside the
  worktree;
* raw logs and scratch space stay in ``.runtime/verification/<run-id>/``;
  the archived receipt lives in ``.scratch/verification/<run-id>/`` and is
  never staged or committed by this tool;
* source evidence is the actual file bytes (SHA-256 of what was read), taken
  immediately before and immediately after the command, bound to the HEAD
  commit at each end; the closing snapshot also covers untracked sources that
  appeared during the run and sources that disappeared;
* a source change during the run returns 86 even when the command passed; an
  unchanged source keeps the command's own exit code; only a successful
  command with identical sources and a valid archive returns 0.

The checker validates archived bytes and metadata only. A recorded non-zero
command exit or a recorded source drift is preserved history, not damage, and
an integrity pass never claims the gate itself passed.
"""

from __future__ import annotations

import hashlib
import os
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

TOOL_REL = "scripts/verification_receipts.py"
SCHEMA = 1
SOURCE_SCOPES = ("backend", "frontend", "scripts", ".githooks")
RUNTIME_REL = ".runtime/verification"
ARCHIVE_REL = ".scratch/verification"
MANIFEST = "receipt-SHA256SUMS.txt"
GITATTRIBUTES_NAME = ".gitattributes"
GITATTRIBUTES_BYTES = (
    b"# Verification receipts are byte-exact evidence.\n"
    b"# '-text' disables EOL conversion so CRLF/LF survive add/commit/checkout.\n"
    b"* -text whitespace=cr-at-eol\n"
)
MODE_FLAGS = {"quick": "--quick", "full": "--full", "deploy": "--deploy"}
FLAG_MODES = {flag: mode for mode, flag in MODE_FLAGS.items()}
DEFAULT_MODE = "full"

EXIT_USAGE = 64
EXIT_PRECONDITION = 65
EXIT_BOUNDARY = 66
EXIT_ARCHIVE_INVALID = 67
EXIT_SOURCE_DRIFT = 86

RUN_ID_RE = re.compile(r"\A[A-Za-z0-9][A-Za-z0-9._-]{0,63}\Z")
HEX40_RE = re.compile(r"\A[0-9a-f]{40,64}\Z")
SHA256_RE = re.compile(r"\A[0-9a-f]{64}\Z")
INT_RE = re.compile(r"\A-?\d+\Z")

ARCHIVE_FILES = (
    GITATTRIBUTES_NAME,
    "receipt.txt",
    "source-before.txt",
    "source-after.txt",
    "log-summary.txt",
)

REQUIRED_FIELDS = (
    "RECEIPT_SCHEMA",
    "RECEIPT_TOOL",
    "RUN_ID",
    "MODE",
    "MODE_FLAG",
    "COMMAND",
    "START_UTC",
    "END_UTC",
    "HEAD_BEFORE",
    "HEAD_AFTER",
    "HEAD_IDENTICAL",
    "SOURCE_SCOPE",
    "SOURCE_FILES",
    "SOURCE_BEFORE",
    "SOURCE_AFTER",
    "SOURCE_BEFORE_SHA256",
    "SOURCE_AFTER_SHA256",
    "SOURCE_IDENTICAL",
    "SOURCE_DRIFT_FILES",
    "COMMAND_EXIT_CODE",
    "TOOL_EXIT_CODE",
    "LOG_RAW",
    "LOG_BYTES",
    "LOG_SHA256",
    "LOG_SUMMARY",
    "ARCHIVE_FILES",
    "CHECKSUM_TABLE",
)

USAGE = """usage:
  bash scripts/verify-run.sh <run-id> [--quick|--full|--deploy]
  bash scripts/check-verification-receipts.sh [--staged|--worktree] [run-id]

verify-run.sh runs the repository gate for real:
  it executes `bash scripts/verify.sh <mode>` (default --full) from the worktree
  root. There is no option to run a different command.

  Raw evidence stays local in .runtime/verification/<run-id>/ (verify.log, tmp,
  npm-cache, screenshots). The archived receipt is written to
  .scratch/verification/<run-id>/ and is never staged or committed here.

  <run-id> must be one safe path segment: [A-Za-z0-9][A-Za-z0-9._-]*.
  Absolute paths, path separators and . / .. are refused. An existing run id is
  never overwritten, and every resolved output path must stay in the worktree.

  Preconditions: tracked worktree clean, and no untracked non-ignored source
  under backend, frontend, scripts or .githooks. Caches elsewhere (for example
  old .scratch directories) do not block a run.

  Exit codes: 0 verified (command 0, identical sources, valid archive);
  the command's own non-zero exit code when sources did not change; 86 when
  sources changed during the run; 64 usage; 65 precondition; 66 output path
  escapes the worktree; 67 the archive failed its own byte check.

check-verification-receipts.sh verifies archived receipt bytes:
  default (no flags) checks the receipts committed in HEAD, --staged checks the
  Git index blobs that are about to be committed, --worktree checks workspace
  bytes (explicitly labelled; not Git object validation).

  It compares every archived file against receipt-SHA256SUMS.txt, checks that
  the receipt metadata agrees with the archived bytes, and never writes the Git
  index. Exit 0 = archived bytes and metadata are consistent. Exit 1 = mismatch.
  Integrity is not business success: a recorded non-zero command exit or source
  drift stays valid evidence and is reported as recorded history.
"""


# --------------------------------------------------------------------------
# small helpers
# --------------------------------------------------------------------------

def utcnow():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def sha256_hex(data):
    return hashlib.sha256(data).hexdigest()


def _inside(path, root):
    path = Path(path)
    root = Path(root)
    return path == root or root in path.parents


def _is_link_like(path):
    path = Path(path)
    if path.is_symlink():
        return True
    isjunction = getattr(os.path, "isjunction", None)
    if isjunction is not None:
        try:
            return bool(isjunction(path))
        except OSError:
            return False
    try:
        attrs = os.lstat(path).st_file_attributes
    except (AttributeError, OSError):
        return False
    return bool(attrs & 0x400)  # FILE_ATTRIBUTE_REPARSE_POINT


def script_root():
    return Path(os.path.realpath(__file__)).parent.parent


class ToolError(Exception):
    def __init__(self, code, message, details=()):
        super().__init__(message)
        self.code = code
        self.message = message
        self.details = [str(item) for item in details]


def usage_error(message):
    print(USAGE, file=sys.stderr)
    print(f"ERROR={message}", file=sys.stderr)
    raise SystemExit(EXIT_USAGE)


# --------------------------------------------------------------------------
# host binaries: always the native Windows Git for Windows (never WSL)
# --------------------------------------------------------------------------

def _is_wsl_stub(candidate):
    lowered = str(candidate).replace("/", "\\").lower()
    return "\\windows\\system32\\" in lowered


def find_git():
    candidate = os.environ.get("PAIBAN_GIT") or None
    if candidate is None:
        import shutil

        candidate = shutil.which("git")
    if not candidate:
        raise ToolError(EXIT_PRECONDITION, "git executable not found; set PAIBAN_GIT")
    if _is_wsl_stub(candidate):
        raise ToolError(
            EXIT_PRECONDITION,
            f"refusing to use the WSL launcher as git: {candidate}",
            ["install Git for Windows or point PAIBAN_GIT at its git.exe"],
        )
    return candidate


def find_bash():
    import shutil

    candidates = []
    if os.environ.get("PAIBAN_BASH"):
        candidates.append(os.environ["PAIBAN_BASH"])
    found = shutil.which("bash")
    if found:
        candidates.append(found)
    git = os.environ.get("PAIBAN_GIT") or shutil.which("git")
    if git:
        try:
            exec_path = subprocess.run(
                [git, "--exec-path"], capture_output=True, text=True, encoding="utf-8", check=True
            ).stdout.strip()
        except (OSError, subprocess.CalledProcessError):
            exec_path = ""
        if exec_path:
            base = Path(exec_path)
            for depth in (2, 3):
                try:
                    install = base.parents[depth]
                except IndexError:
                    continue
                candidates.extend(
                    [
                        str(install / "bin" / "bash.exe"),
                        str(install / "usr" / "bin" / "bash.exe"),
                        str(install / "bin" / "bash"),
                    ]
                )
    for candidate in candidates:
        if not candidate or not Path(candidate).is_file():
            continue
        if _is_wsl_stub(candidate):
            continue  # C:\Windows\System32\bash.exe launches WSL, not Git Bash
        return str(Path(candidate).resolve())
    raise ToolError(
        EXIT_PRECONDITION,
        "no Git Bash found",
        ["set PAIBAN_BASH to a Git for Windows bash.exe (never the WSL launcher)"],
    )


def _cygpath(flag, path):
    try:
        proc = subprocess.run(
            ["cygpath", flag, str(path)], capture_output=True, text=True, encoding="utf-8", check=True
        )
        return proc.stdout.strip() or str(path)
    except (OSError, subprocess.CalledProcessError):
        return str(path)


def native_path(path):
    if os.name == "nt":
        return str(path)
    return _cygpath("-w", path)


def bash_path(path):
    if os.name == "nt":
        return _cygpath("-u", path)
    return str(path)


# --------------------------------------------------------------------------
# git access (argument lists only, no shell strings, no eval)
# --------------------------------------------------------------------------

class Git:
    def __init__(self, root, exe, readonly=False):
        self.root = Path(root)
        self.exe = exe
        self.env = dict(os.environ)
        self.env.setdefault("GIT_TERMINAL_PROMPT", "0")
        if readonly:
            # Never refresh/write the index while only reading evidence.
            self.env["GIT_OPTIONAL_LOCKS"] = "0"

    def run(self, *args, code=EXIT_PRECONDITION):
        proc = subprocess.run(
            [self.exe, "-c", "core.quotepath=false", *args],
            cwd=str(self.root),
            env=self.env,
            capture_output=True,
        )
        if proc.returncode != 0:
            message = proc.stderr.decode("utf-8", "replace").strip()
            raise ToolError(code, f"git {' '.join(args)} failed ({proc.returncode}): {message}")
        return proc

    def rev(self, ref):
        return self.run("rev-parse", "--verify", ref).stdout.decode("ascii", "replace").strip()

    def zlist(self, *args):
        return [chunk for chunk in self.run(*args).stdout.split(b"\0") if chunk]

    def tree_blobs(self, ref):
        blobs = {}
        for chunk in self.zlist("ls-tree", "-r", "-z", ref, "--", *SOURCE_SCOPES):
            meta, tab, path = chunk.partition(b"\t")
            if not tab:
                continue
            parts = meta.split()
            if len(parts) < 3:
                continue
            blobs[path] = parts[2]
        return blobs

    def tracked_paths(self):
        return self.zlist("ls-files", "-z", "--", *SOURCE_SCOPES)


def require_pristine_sources(git):
    status = git.run("status", "--porcelain", "--untracked-files=no").stdout
    dirty = [
        line for line in status.decode("utf-8", "surrogateescape").splitlines() if line.strip()
    ]
    if dirty:
        raise ToolError(
            EXIT_PRECONDITION,
            "tracked worktree is not clean; commit or revert before a verification run",
            dirty[:20],
        )
    untracked = [
        os.fsdecode(path)
        for path in git.zlist(
            "ls-files", "-z", "--others", "--exclude-standard", "--", *SOURCE_SCOPES
        )
    ]
    if untracked:
        raise ToolError(
            EXIT_PRECONDITION,
            "untracked source files under backend/frontend/scripts/.githooks",
            untracked[:20] + (["..."] if len(untracked) > 20 else []),
        )


# --------------------------------------------------------------------------
# source snapshots: actual bytes, not just canonical hashes
# --------------------------------------------------------------------------

def take_snapshot(
    git, root, label, head, include_untracked=False, tolerate_missing=False
):
    """Snapshot the four source scopes as actual bytes.

    ``include_untracked`` closes over sources created during the run and
    ``tolerate_missing`` records sources deleted during the run, so both show up
    as drift instead of aborting evidence collection.
    """
    root_r = Path(os.path.realpath(root))
    blobs = git.tree_blobs(head)
    tracked = set(git.tracked_paths())
    paths = set(tracked)
    if include_untracked:
        paths |= {
            path
            for path in git.zlist(
                "ls-files", "-z", "--others", "--exclude-standard", "--", *SOURCE_SCOPES
            )
        }
    entries = {}
    body = bytearray()
    for raw in sorted(paths):
        if any(char in raw for char in (b"\n", b"\r", b"\t")):
            raise ToolError(
                EXIT_PRECONDITION,
                "a source path contains control characters; refusing to snapshot",
                [repr(raw)],
            )
        rel = os.fsdecode(raw)
        resolved = Path(os.path.realpath(root / rel))
        if not _inside(resolved, root_r):
            raise ToolError(
                EXIT_BOUNDARY,
                f"source resolves outside the worktree: {rel} -> {resolved}",
            )
        state = "tracked" if raw in tracked else "untracked"
        if not resolved.is_file():
            if not tolerate_missing:
                raise ToolError(
                    EXIT_PRECONDITION,
                    f"tracked source is missing from the worktree: {rel}",
                )
            entries[raw] = ("missing", "-", 0)
            body += b"missing - 0 - " + raw + b"\n"
            continue
        data = resolved.read_bytes()
        digest = sha256_hex(data)
        entries[raw] = (state, digest, len(data))
        body += f"{state} {digest} {len(data)} ".encode("ascii")
        body += blobs.get(raw, b"-") + b" " + raw + b"\n"
    header = (
        "# verification source snapshot v1\n"
        "# columns: state sha256 bytes git_blob_at_HEAD path\n"
        "# state: tracked | untracked | missing (a source that vanished mid-run)\n"
        f"SNAPSHOT={label}\n"
        f"HEAD={head}\n"
        f"SCOPE={' '.join(SOURCE_SCOPES)}\n"
        f"FILES={len(entries)}\n"
    ).encode("utf-8")
    return {"entries": entries, "bytes": header + bytes(body)}


def diff_snapshots(before, after):
    drifted = [path for path in before if path not in after]
    drifted += [path for path in after if path not in before]
    drifted += [path for path in before if path in after and before[path] != after[path]]
    return sorted(set(drifted))


LOG_MARKERS = (
    "BUILD FAILURE",
    "Tests run:",
    "ERROR",
    "==> verify: ok",
)


def log_line_count(data):
    return data.count(b"\n") + int(bool(data) and not data.endswith(b"\n"))


def build_log_summary(log_rel, log_bytes):
    """Metadata-only digest of the raw log: never archive log text.

    The verify log can carry request context, tokens or customer data, so the
    archive keeps only size/digest/line counts plus marker counts. The full log
    stays in .runtime and is deliberately not copied.
    """
    lines = log_bytes.split(b"\n")
    total = log_line_count(log_bytes)
    out = [
        "# metadata-only log digest; the raw log stays local and is never archived",
        "# no log text is stored here, only counts and hashes",
        f"LOG_RAW={log_rel}",
        f"LOG_BYTES={len(log_bytes)}",
        f"LOG_SHA256={sha256_hex(log_bytes)}",
        f"LOG_LINES_TOTAL={total}",
        f"LOG_EMPTY={int(not log_bytes)}",
    ]
    for marker in LOG_MARKERS:
        count = sum(1 for line in lines if marker.encode("utf-8") in line)
        key = re.sub(r"[^A-Z0-9]+", "_", marker.upper()).strip("_")
        out.append(f"LOG_LINES_WITH_{key}={count}")
    skipped = sum(bool(re.search(rb"\bskip(?:ped|ping)?\b", line, re.IGNORECASE)) for line in lines)
    out.append(f"LOG_LINES_WITH_SKIPPED={skipped}")
    if b"Docker not found: skipping PostgresContainerIT" in log_bytes:
        postgres_signal = "skipped-no-docker"
    elif b"Docker is required for --deploy" in log_bytes:
        postgres_signal = "blocked-no-docker"
    elif b"-Dtestcontainers=true" in log_bytes:
        postgres_signal = "requested-via-testcontainers"
    else:
        postgres_signal = "not-observed"
    out.append(f"POSTGRES_CONTAINER_SIGNAL={postgres_signal}")
    out.append("LOG_EXCERPT=redacted-by-design")
    return ("\n".join(out) + "\n").encode("utf-8")


# --------------------------------------------------------------------------
# receipt validation (shared by the run self-check and the checker CLI)
# --------------------------------------------------------------------------

def _safe_receipt_name(name):
    if not name or name in (".", ".."):
        return False
    if any(char in name for char in ("/", "\\", ":", "\0")):
        return False
    if name != name.strip():
        return False
    return not any(ord(char) < 32 for char in name)


def parse_receipt_bytes(data):
    fields = {}
    for line in data.decode("utf-8", "surrogateescape").splitlines():
        if not line or line.startswith("#"):
            continue
        key, sep, value = line.partition("=")
        if sep:
            fields[key] = value
    return fields


def parse_snapshot(data, label, head):
    """Validate archived snapshot structure without reading any source paths."""
    headers, entries, problems = {}, {}, []
    for line in data.splitlines():
        if not line or line.startswith(b"#"):
            continue
        key, sep, value = line.partition(b"=")
        if sep and key in (b"SNAPSHOT", b"HEAD", b"SCOPE", b"FILES"):
            if key in headers:
                problems.append(f"duplicate snapshot header {key!r}")
            headers[key] = value.decode("ascii", "replace")
            continue
        parts = line.split(b" ", 4)
        if len(parts) != 5:
            problems.append("malformed source row")
            continue
        state, digest, size, blob, path = parts
        try:
            state, digest, blob = (part.decode("ascii") for part in (state, digest, blob))
            size = int(size)
            if state not in ("tracked", "untracked", "missing") or size < 0:
                raise ValueError()
            if state == "missing":
                if (digest, size, blob) != ("-", 0, "-"):
                    raise ValueError()
            elif not SHA256_RE.fullmatch(digest):
                raise ValueError()
            if state == "tracked" and not (
                HEX40_RE.fullmatch(blob) or (label == "after" and blob == "-")
            ):
                raise ValueError()
            if label == "before" and state != "tracked":
                raise ValueError()
        except (UnicodeError, ValueError):
            problems.append("invalid source state, digest, size or Git binding")
            continue
        if (
            not any(path.startswith(scope.encode() + b"/") for scope in SOURCE_SCOPES)
            or any(part in (b".", b"..", b"") for part in path.split(b"/"))
            or any(char in path for char in (b"\0", b"\t", b"\\"))
        ):
            problems.append("unsafe source path")
            continue
        if path in entries:
            problems.append("duplicate source path")
        entries[path] = (state, digest, size)
    expected_headers = {
        b"SNAPSHOT": label,
        b"HEAD": head,
        b"SCOPE": " ".join(SOURCE_SCOPES),
        b"FILES": str(len(entries)),
    }
    for key, value in expected_headers.items():
        if headers.get(key) != value:
            problems.append(f"snapshot {key.decode()} does not match its contents or receipt")
    return entries, problems


def validate_snapshot_metadata(fields, read):
    snapshots, problems = {}, []
    for label in ("before", "after"):
        name = f"source-{label}.txt"
        data = read(name)
        if data is None:
            continue
        entries, issues = parse_snapshot(data, label, fields.get(f"HEAD_{label.upper()}"))
        snapshots[label] = entries
        problems.extend(f"{name}: {issue}" for issue in issues)
    if len(snapshots) != 2 or problems:
        return problems
    before, after = snapshots["before"], snapshots["after"]
    drift = diff_snapshots(before, after)
    head_identical = fields.get("HEAD_BEFORE") == fields.get("HEAD_AFTER")
    expected = {
        "SOURCE_FILES": str(len(before)),
        "SOURCE_DRIFT_FILES": str(len(drift)),
        "SOURCE_IDENTICAL": str(int(head_identical and not drift)),
        "HEAD_IDENTICAL": str(int(head_identical)),
    }
    for key, value in expected.items():
        if fields.get(key) != value:
            problems.append(f"{key} does not match the archived source snapshots")
    return problems


def validate_receipt(run_id, read, names):
    """Return (receipt fields, integrity problems) for one archived receipt dir.

    ``read(name)`` returns raw bytes or None; ``names`` is every file name in
    the receipt directory. Only integrity/metadata is judged here.
    """
    problems = []
    manifest = read(MANIFEST)
    if manifest is None:
        return {}, [f"MISSING {MANIFEST}"]
    entries = {}
    for row in manifest.decode("utf-8", "surrogateescape").splitlines():
        if not row:
            continue
        digest, sep, name = row.partition("  ")
        if not sep or not SHA256_RE.match(digest):
            problems.append(f"MALFORMED manifest row: {row!r}")
            continue
        if not _safe_receipt_name(name):
            problems.append(f"UNSAFE manifest entry: {name!r}")
            continue
        if name in entries:
            problems.append(f"DUPLICATE manifest entry: {name}")
            continue
        entries[name] = digest
    for required in ARCHIVE_FILES:
        if required not in entries:
            problems.append(f"MISSING manifest entry: {required}")
    for name, expected in sorted(entries.items()):
        data = read(name)
        if data is None:
            problems.append(f"MISSING listed file: {name}")
            continue
        actual = sha256_hex(data)
        if actual != expected:
            problems.append(f"MISMATCH {name}: manifest={expected} actual={actual}")
    for name in sorted(names):
        if name == MANIFEST:
            continue
        if name not in entries:
            problems.append(f"UNLISTED file in receipt dir: {name}")

    receipt = read("receipt.txt")
    if receipt is None:
        problems.append("MISSING receipt.txt")
        return {}, problems
    fields = parse_receipt_bytes(receipt)
    if fields.get("ARCHIVE_VALID") == "0":
        problems.append("archive was marked invalid by the runner")
    for key in REQUIRED_FIELDS:
        if key not in fields:
            problems.append(f"receipt.txt missing field {key}")

    if fields.get("RECEIPT_SCHEMA") != str(SCHEMA):
        problems.append(f"unsupported RECEIPT_SCHEMA {fields.get('RECEIPT_SCHEMA')!r}")
    if fields.get("RECEIPT_TOOL") != TOOL_REL:
        problems.append(f"unexpected RECEIPT_TOOL {fields.get('RECEIPT_TOOL')!r}")
    if fields.get("RUN_ID") != run_id:
        problems.append(
            f"RUN_ID {fields.get('RUN_ID')!r} does not match directory {run_id!r}"
        )
    mode = fields.get("MODE")
    if mode not in MODE_FLAGS:
        problems.append(f"unknown MODE {mode!r}")
    elif fields.get("MODE_FLAG") != MODE_FLAGS[mode]:
        problems.append(f"MODE_FLAG {fields.get('MODE_FLAG')!r} does not match MODE {mode!r}")
    if fields.get("CHECKSUM_TABLE") != MANIFEST:
        problems.append(f"CHECKSUM_TABLE {fields.get('CHECKSUM_TABLE')!r}")
    if fields.get("ARCHIVE_FILES") != str(len(entries)):
        problems.append(
            f"ARCHIVE_FILES {fields.get('ARCHIVE_FILES')!r} != {len(entries)} manifest entries"
        )
    for key in ("HEAD_BEFORE", "HEAD_AFTER"):
        if key in fields and not HEX40_RE.match(fields[key]):
            problems.append(f"{key} is not a commit id: {fields[key]!r}")
    for key in ("COMMAND_EXIT_CODE", "TOOL_EXIT_CODE", "SOURCE_DRIFT_FILES", "SOURCE_FILES"):
        if key in fields and not INT_RE.match(fields[key]):
            problems.append(f"{key} is not an integer: {fields[key]!r}")
    for key in ("SOURCE_IDENTICAL", "HEAD_IDENTICAL"):
        if key in fields and fields[key] not in ("0", "1"):
            problems.append(f"{key} must be 0 or 1: {fields[key]!r}")
    for key, name in (
        ("SOURCE_BEFORE_SHA256", "source-before.txt"),
        ("SOURCE_AFTER_SHA256", "source-after.txt"),
    ):
        data = read(name)
        if data is not None and key in fields and fields[key] != sha256_hex(data):
            problems.append(f"{key} does not match the archived {name} bytes")
    if fields.get("SOURCE_BEFORE") != "source-before.txt":
        problems.append(f"SOURCE_BEFORE {fields.get('SOURCE_BEFORE')!r}")
    if fields.get("SOURCE_AFTER") != "source-after.txt":
        problems.append(f"SOURCE_AFTER {fields.get('SOURCE_AFTER')!r}")
    if fields.get("LOG_SUMMARY") != "log-summary.txt":
        problems.append(f"LOG_SUMMARY {fields.get('LOG_SUMMARY')!r}")
    problems.extend(validate_snapshot_metadata(fields, read))
    summary = read("log-summary.txt")
    if summary is not None:
        summary_fields = parse_receipt_bytes(summary)
        for key in ("LOG_RAW", "LOG_BYTES", "LOG_SHA256", "LOG_LINES_TOTAL"):
            if summary_fields.get(key) != fields.get(key):
                problems.append(f"{key} differs between receipt and log summary")
    if fields.get("HEAD_BEFORE") != fields.get("HEAD_AFTER") and fields.get(
        "HEAD_IDENTICAL"
    ) != "0":
        problems.append("HEAD_IDENTICAL must be 0 when HEAD changed during the run")

    try:
        command_exit = int(fields["COMMAND_EXIT_CODE"])
        tool_exit = int(fields["TOOL_EXIT_CODE"])
        identical = int(fields["SOURCE_IDENTICAL"])
    except (KeyError, ValueError):
        command_exit = tool_exit = identical = None
    if identical is not None:
        if identical == 0:
            if tool_exit != EXIT_SOURCE_DRIFT:
                problems.append(
                    f"SOURCE_IDENTICAL=0 must record TOOL_EXIT_CODE={EXIT_SOURCE_DRIFT}, got {tool_exit}"
                )
        elif command_exit != 0:
            if tool_exit != command_exit:
                problems.append(
                    "TOOL_EXIT_CODE must preserve the non-zero command exit code"
                )
        elif tool_exit != 0:
            problems.append(
                "a successful command with identical sources must record TOOL_EXIT_CODE=0"
            )
    return fields, problems


# --------------------------------------------------------------------------
# run subcommand
# --------------------------------------------------------------------------

def validate_run_id(run_id):
    if not RUN_ID_RE.match(run_id or ""):
        raise ToolError(
            EXIT_USAGE,
            f"invalid run-id {run_id!r}: expected a single safe path segment",
            [
                "allowed: [A-Za-z0-9][A-Za-z0-9._-]* (max 64 chars)",
                "refused: absolute paths, path separators, '.' and '..'",
            ],
        )
    if run_id.endswith(".") or run_id.endswith(" "):
        raise ToolError(
            EXIT_USAGE,
            f"invalid run-id {run_id!r}: trailing dot or space is not portable",
        )


def resolve_output_path(root, rel, label):
    root_r = Path(os.path.realpath(root))
    resolved = Path(os.path.realpath(Path(root) / rel))
    if not _inside(resolved, root_r) or resolved == root_r:
        raise ToolError(
            EXIT_BOUNDARY,
            f"{label} resolves outside the worktree: {rel} -> {resolved}",
            [
                "a linked/junctioned output directory would let a run write outside "
                "the worktree; remove the link or pick another run id",
            ],
        )
    return resolved


def child_env(runtime_dir):
    tmp = runtime_dir / "tmp"
    npm = runtime_dir / "npm-cache"
    shots = runtime_dir / "screenshots"
    for directory in (tmp, npm, shots):
        directory.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ)
    env["TEMP"] = native_path(tmp)
    env["TMP"] = native_path(tmp)
    env["TMPDIR"] = bash_path(tmp)
    env["npm_config_cache"] = native_path(npm)
    env["PAIBAN_E2E_SCREENSHOTS"] = native_path(shots)
    # Everything else (VITEST_MAX_WORKERS, PAIBAN_MVN_EXTRA_ARGS, E2E ports...)
    # is inherited from the caller on purpose: host escape hatches are not
    # general defaults and are the caller's decision.
    env["PAIBAN_VERIFY_RUN_DIR"] = native_path(runtime_dir)
    return env


def parse_run_args(argv):
    run_id = None
    mode = DEFAULT_MODE
    mode_seen = False
    for arg in argv:
        if arg in ("-h", "--help"):
            print(USAGE)
            raise SystemExit(0)
        if arg in FLAG_MODES:
            if mode_seen:
                usage_error("give at most one of --quick, --full, --deploy")
            mode_seen = True
            mode = FLAG_MODES[arg]
        elif arg.startswith("-"):
            usage_error(f"unknown option {arg!r}")
        elif run_id is None:
            run_id = arg
        else:
            usage_error("exactly one run-id is expected")
    if run_id is None:
        usage_error("missing <run-id>")
    validate_run_id(run_id)
    return run_id, mode


def build_receipt(fields):
    lines = [
        "# Verification run receipt (schema 1).",
        "# The archived bytes are authoritative; see receipt-SHA256SUMS.txt.",
        "# A recorded non-zero command exit or source drift is preserved history,",
        "# not a damaged receipt. Integrity here is not a claim that the gate passed.",
    ]
    lines.extend(f"{key}={value}" for key, value in fields)
    return ("\n".join(lines) + "\n").encode("utf-8", "surrogateescape")


def write_archive(archive_dir, files):
    archive_dir.mkdir(parents=True, exist_ok=False)
    for name in sorted(files):
        (archive_dir / name).write_bytes(files[name])
    write_manifest(archive_dir, files)


def write_manifest(archive_dir, files):
    manifest = b"".join(
        f"{sha256_hex(files[name])}  {name}\n".encode("utf-8") for name in sorted(files)
    )
    (archive_dir / MANIFEST).write_bytes(manifest)


def cmd_run(argv):
    run_id, mode = parse_run_args(argv)
    root = script_root()
    gate = root / "scripts" / "verify.sh"
    if not gate.is_file():
        raise ToolError(
            EXIT_PRECONDITION,
            f"scripts/verify.sh is missing under {root}",
            ["verify-run.sh only runs the repository gate; it takes no command argument"],
        )
    git = Git(root, find_git())
    bash = find_bash()

    runtime_rel = f"{RUNTIME_REL}/{run_id}"
    archive_rel = f"{ARCHIVE_REL}/{run_id}"
    runtime_dir = resolve_output_path(root, runtime_rel, "local runtime dir")
    archive_dir = resolve_output_path(root, archive_rel, "archived receipt dir")
    for path, label in (
        (runtime_dir, f"{RUNTIME_REL}/<run-id>"),
        (archive_dir, f"{ARCHIVE_REL}/<run-id>"),
    ):
        if path.exists():
            raise ToolError(
                EXIT_PRECONDITION,
                f"run id already exists at {label}; run ids are never overwritten",
                [f"existing path: {path}"],
            )

    head_before = git.rev("HEAD")
    require_pristine_sources(git)

    runtime_dir.mkdir(parents=True, exist_ok=False)
    log_path = runtime_dir / "verify.log"
    flag = MODE_FLAGS[mode]
    command = f"bash scripts/verify.sh {flag}"

    before = take_snapshot(git, root, "before", head_before)
    (runtime_dir / "source-before.txt").write_bytes(before["bytes"])

    print(f"VERIFY_RUN_ID={run_id}")
    print(f"VERIFY_RUN_MODE={mode}")
    print(f"VERIFY_RUN_ROOT={root}")
    print(f"VERIFY_RUN_RUNTIME_DIR={runtime_dir}")
    print(f"VERIFY_RUN_ARCHIVE_DIR={archive_dir}")
    print(f"VERIFY_RUN_HEAD_BEFORE={head_before}")
    print(f"VERIFY_RUN_COMMAND={command}")
    print(f"VERIFY_RUN_LOG_RAW={log_path}")

    env = child_env(runtime_dir)
    started = utcnow()
    with log_path.open("wb") as log:
        proc = subprocess.run(
            [bash, "scripts/verify.sh", flag],
            cwd=str(root),
            env=env,
            stdout=log,
            stderr=subprocess.STDOUT,
        )
    ended = utcnow()
    command_exit = proc.returncode
    print(f"VERIFY_RUN_START_UTC={started}")
    print(f"VERIFY_RUN_COMMAND_EXIT_CODE={command_exit}")

    head_after = git.rev("HEAD")
    after = take_snapshot(
        git, root, "after", head_after, include_untracked=True, tolerate_missing=True
    )
    (runtime_dir / "source-after.txt").write_bytes(after["bytes"])
    drift_raw = diff_snapshots(before["entries"], after["entries"])
    drift = [os.fsdecode(path) for path in drift_raw]
    drift_missing = sum(
        1
        for path in drift_raw
        if after["entries"].get(path, ("missing", "-", 0))[0] == "missing"
    )
    drift_untracked = sum(
        1
        for path in drift_raw
        if after["entries"].get(path, ("missing", "-", 0))[0] == "untracked"
    )
    head_identical = head_before == head_after
    identical = head_identical and not drift
    tool_exit = EXIT_SOURCE_DRIFT if not identical else command_exit

    print(f"VERIFY_RUN_HEAD_AFTER={head_after}")
    print(f"VERIFY_RUN_SOURCE_IDENTICAL={int(identical)}")
    print(f"VERIFY_RUN_DRIFT_FILES={len(drift)}")
    print(f"VERIFY_RUN_DRIFT_MISSING={drift_missing}")
    print(f"VERIFY_RUN_DRIFT_UNTRACKED={drift_untracked}")
    for path in drift[:10]:
        print(f"VERIFY_RUN_DRIFT_PATH={path}")
    if len(drift) > 10:
        print("VERIFY_RUN_DRIFT_TRUNCATED=1")
    print(f"VERIFY_RUN_END_UTC={ended}")

    log_bytes = log_path.read_bytes()
    summary = build_log_summary(f"{runtime_rel}/verify.log", log_bytes)
    log_sha = sha256_hex(log_bytes)
    log_lines = log_line_count(log_bytes)

    receipt_fields = [
        ("RECEIPT_SCHEMA", str(SCHEMA)),
        ("RECEIPT_TOOL", TOOL_REL),
        ("RUN_ID", run_id),
        ("MODE", mode),
        ("MODE_FLAG", flag),
        ("COMMAND", command),
        ("ROOT", "."),
        ("START_UTC", started),
        ("END_UTC", ended),
        ("HEAD_BEFORE", head_before),
        ("HEAD_AFTER", head_after),
        ("HEAD_IDENTICAL", str(int(head_identical))),
        ("SOURCE_SCOPE", " ".join(SOURCE_SCOPES)),
        ("SOURCE_FILES", str(len(before["entries"]))),
        ("SOURCE_BEFORE", "source-before.txt"),
        ("SOURCE_AFTER", "source-after.txt"),
        ("SOURCE_BEFORE_SHA256", sha256_hex(before["bytes"])),
        ("SOURCE_AFTER_SHA256", sha256_hex(after["bytes"])),
        ("SOURCE_IDENTICAL", str(int(identical))),
        ("SOURCE_DRIFT_FILES", str(len(drift))),
        ("SOURCE_DRIFT_SAMPLE", "|".join(drift[:5])),
        ("SOURCE_DRIFT_MISSING", str(drift_missing)),
        ("SOURCE_DRIFT_UNTRACKED", str(drift_untracked)),
        ("COMMAND_EXIT_CODE", str(command_exit)),
        ("TOOL_EXIT_CODE", str(tool_exit)),
        ("LOG_RAW", f"{runtime_rel}/verify.log"),
        ("LOG_BYTES", str(len(log_bytes))),
        ("LOG_SHA256", log_sha),
        ("LOG_LINES_TOTAL", str(log_lines)),
        ("LOG_SUMMARY", "log-summary.txt"),
        ("LOG_SUMMARY_KIND", "metadata-only"),
        ("ARCHIVE_FILES", str(len(ARCHIVE_FILES))),
        ("CHECKSUM_TABLE", MANIFEST),
        (
            "NOTES",
            "raw verify.log stays local and is never archived; the archive keeps a "
            "metadata-only log digest plus source snapshots; skipped steps (for "
            "example real PostgreSQL) are only what scripts/verify.sh printed",
        ),
    ]
    files = {
        GITATTRIBUTES_NAME: GITATTRIBUTES_BYTES,
        "receipt.txt": build_receipt(receipt_fields),
        "source-before.txt": before["bytes"],
        "source-after.txt": after["bytes"],
        "log-summary.txt": summary,
    }
    write_archive(archive_dir, files)

    names = {path.name for path in archive_dir.iterdir() if path.is_file()}
    _, problems = validate_receipt(
        run_id,
        lambda name: (archive_dir / name).read_bytes()
        if (archive_dir / name).is_file()
        else None,
        names,
    )
    print(f"VERIFY_RUN_ARCHIVE={archive_dir}")
    print(f"VERIFY_RUN_ARCHIVE_FILES={len(files) + 1}")
    print(f"VERIFY_RUN_ARCHIVE_VALID={int(not problems)}")
    for problem in problems:
        print(f"VERIFY_RUN_ARCHIVE_PROBLEM={problem}")
    if problems:
        # Keep the failed archive invalid, but record the runner's actual exit.
        # Other archived bytes are not repaired or replaced after a failed check.
        failed_fields = [
            (key, str(EXIT_ARCHIVE_INVALID) if key == "TOOL_EXIT_CODE" else value)
            for key, value in receipt_fields
        ]
        files["receipt.txt"] = build_receipt(failed_fields + [("ARCHIVE_VALID", "0")])
        try:
            (archive_dir / "receipt.txt").write_bytes(files["receipt.txt"])
            write_manifest(archive_dir, files)
        except OSError as error:
            print(f"VERIFY_RUN_ARCHIVE_UPDATE_FAILED={error}", file=sys.stderr)
        print(f"VERIFY_RUN_TOOL_EXIT_CODE={EXIT_ARCHIVE_INVALID}")
        return EXIT_ARCHIVE_INVALID
    print(f"VERIFY_RUN_TOOL_EXIT_CODE={tool_exit}")
    return tool_exit


# --------------------------------------------------------------------------
# check subcommand
# --------------------------------------------------------------------------

def parse_check_args(argv):
    staged = False
    worktree = False
    run_id = None
    for arg in argv:
        if arg in ("-h", "--help"):
            print(USAGE)
            raise SystemExit(0)
        if arg == "--staged":
            if worktree:
                usage_error("--staged and --worktree are mutually exclusive")
            staged = True
        elif arg == "--worktree":
            if staged:
                usage_error("--staged and --worktree are mutually exclusive")
            worktree = True
        elif arg.startswith("-"):
            usage_error(f"unknown option {arg!r}")
        elif run_id is None:
            run_id = arg
        else:
            usage_error("at most one run-id is expected")
    if run_id is not None and (
        not RUN_ID_RE.match(run_id) or run_id in (".", "..") or "/" in run_id
    ):
        usage_error(f"invalid run-id {run_id!r}")
    return staged, worktree, run_id


def _receipt_prefix():
    return ARCHIVE_REL.encode("utf-8") + b"/"


def collect_ref_receipts(git, ref):
    """Map run id -> {file name: blob oid bytes} for one Git ref."""
    if ref == "INDEX":
        raw = git.run("ls-files", "-s", "-z", "--", ARCHIVE_REL).stdout
        stage_index = 2
    else:
        try:
            git.rev(ref)
        except ToolError:
            return {}, [], []
        raw = git.run("ls-tree", "-r", "-z", ref, "--", ARCHIVE_REL).stdout
        stage_index = None
    prefix = _receipt_prefix()
    dirs = {}
    ignored = []
    unexpected = []
    for chunk in raw.split(b"\0"):
        if not chunk:
            continue
        meta, tab, path = chunk.partition(b"\t")
        if not tab:
            continue
        parts = meta.split()
        if stage_index is not None:
            if len(parts) < 3:
                continue
            if parts[2] != b"0":
                ignored.append(f"conflicted index entry {os.fsdecode(path)}")
                continue
            oid = parts[1]
        else:
            if len(parts) < 3:
                continue
            oid = parts[2]
        if not path.startswith(prefix):
            unexpected.append(os.fsdecode(path))
            continue
        rest = path[len(prefix):]
        if b"/" not in rest:
            ignored.append(os.fsdecode(path))
            continue
        run_dir, _, name = rest.partition(b"/")
        if b"/" in name or b"\0" in name or not name:
            unexpected.append(os.fsdecode(path))
            continue
        dirs.setdefault(os.fsdecode(run_dir), {})[os.fsdecode(name)] = oid
    return dirs, ignored, unexpected


def collect_worktree_receipts(root):
    root_r = Path(os.path.realpath(root))
    base = Path(os.path.realpath(root / ARCHIVE_REL))
    dirs = {}
    ignored = []
    unexpected = []
    if not _inside(base, root_r):
        return {}, [], [f"{ARCHIVE_REL} resolves outside the worktree"]
    if not base.is_dir():
        return dirs, ignored, unexpected
    for child in sorted(base.iterdir()):
        if _is_link_like(child):
            unexpected.append(f"{ARCHIVE_REL}/{child.name} (link)")
            continue
        if not child.is_dir():
            ignored.append(f"{ARCHIVE_REL}/{child.name}")
            continue
        files = {}
        for item in sorted(child.iterdir()):
            if _is_link_like(item) or not item.is_file():
                unexpected.append(f"{ARCHIVE_REL}/{child.name}/{item.name}")
                continue
            files[item.name] = item
        dirs[child.name] = files
    return dirs, ignored, unexpected


def cmd_check(argv):
    staged, worktree, only_run_id = parse_check_args(argv)
    root = script_root()
    ref = "INDEX" if staged else ("WORKTREE" if worktree else "HEAD")
    git = Git(root, find_git(), readonly=True)

    if ref == "WORKTREE":
        dirs, ignored, unexpected = collect_worktree_receipts(root)
        roots = Path(os.path.realpath(root))

        def make_reader(files):
            def read(name):
                path = files.get(name)
                if path is None:
                    return None
                resolved = Path(os.path.realpath(path))
                if not _inside(resolved, roots) or not resolved.is_file():
                    return None
                return resolved.read_bytes()

            return read

        present_names = {run_id: set(files) for run_id, files in dirs.items()}
    else:
        dirs, ignored, unexpected = collect_ref_receipts(git, ref)

        def make_reader(files):
            def read(name):
                oid = files.get(name)
                if oid is None:
                    return None
                return git.run("cat-file", "blob", oid).stdout

            return read

        present_names = {run_id: set(files) for run_id, files in dirs.items()}

    print(f"VERIFICATION_RECEIPTS_REF={ref}")
    if ref == "WORKTREE":
        print(
            "VERIFICATION_RECEIPTS_SOURCE=workspace bytes (not Git object validation)"
        )
    else:
        print("VERIFICATION_RECEIPTS_SOURCE=git objects")

    selected = sorted(present_names)
    if only_run_id is not None:
        selected = [run_id for run_id in selected if run_id == only_run_id]

    total_files = 0
    mismatches = []
    missing = []
    unlisted = []
    malformed = []
    metadata = []
    ok_dirs = 0
    for run_id in selected:
        names = present_names[run_id]
        total_files += len(names)
        fields, problems = validate_receipt(run_id, make_reader(dirs[run_id]), names)
        for problem in problems:
            if problem.startswith("MISMATCH"):
                mismatches.append((run_id, problem))
            elif problem.startswith("MISSING"):
                missing.append((run_id, problem))
            elif problem.startswith("UNLISTED"):
                unlisted.append((run_id, problem))
            elif problem.startswith(("MALFORMED", "UNSAFE", "DUPLICATE")):
                malformed.append((run_id, problem))
            else:
                metadata.append((run_id, problem))
        status = "ok" if not problems else "fail"
        print(
            f"RECEIPT={ARCHIVE_REL}/{run_id} status={status} files={len(names)} "
            f"recorded_command_exit={fields.get('COMMAND_EXIT_CODE', '?')} "
            f"recorded_source_identical={fields.get('SOURCE_IDENTICAL', '?')} "
            f"recorded_tool_exit={fields.get('TOOL_EXIT_CODE', '?')}"
        )
        if not problems:
            ok_dirs += 1
        for problem in problems:
            print(f"PROBLEM={ARCHIVE_REL}/{run_id}: {problem}")

    if only_run_id is not None and only_run_id not in present_names:
        missing.append((only_run_id, f"receipt dir {only_run_id!r} not found in {ref}"))
        print(f"PROBLEM={ARCHIVE_REL}/{only_run_id}: not found in {ref}")

    for path in ignored:
        print(f"NOTE=ignored non-receipt path {path}")
    for path in unexpected:
        metadata.append(("<global>", f"unexpected path in receipt area: {path}"))
        print(f"PROBLEM={path}: unexpected path in receipt area")

    problems_total = len(mismatches) + len(missing) + len(unlisted) + len(malformed) + len(
        metadata
    )
    print(
        f"RECEIPT_DIRS={len(selected)} CHECKED_FILES={total_files} "
        f"MISMATCHES={len(mismatches)} UNLISTED={len(unlisted)} "
        f"MISSING={len(missing)} MALFORMED={len(malformed)} "
        f"METADATA_PROBLEMS={len(metadata)}"
    )
    if ref == "WORKTREE":
        print("NOTE=REF=WORKTREE reads workspace bytes, not Git object validation.")
    elif ref == "INDEX":
        print("NOTE=REF=INDEX checks staged blobs; no index writes were performed.")
    if not selected:
        print(f"NOTE=no archived receipts found in {ref}")
    print(
        "NOTE=INTEGRITY only: archived bytes and metadata agree. A recorded "
        "non-zero command exit or recorded source drift is preserved history, "
        "not damage, and this check does not prove the gate passed."
    )
    print(f"VERIFICATION_RECEIPTS={'ok' if problems_total == 0 else 'fail'}")
    return 0 if problems_total == 0 else 1


# --------------------------------------------------------------------------
# entry point
# --------------------------------------------------------------------------

def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    if not argv:
        usage_error("missing subcommand: run or check")
    command, rest = argv[0], argv[1:]
    try:
        if command in ("-h", "--help", "help"):
            print(USAGE)
            return 0
        if command == "run":
            return cmd_run(rest)
        if command == "check":
            return cmd_check(rest)
        usage_error(f"unknown subcommand {command!r}: expected run or check")
    except ToolError as error:
        for detail in error.details:
            print(detail, file=sys.stderr)
        print(f"ERROR={error.message}", file=sys.stderr)
        print(f"VERIFY_TOOL_EXIT_CODE={error.code}", file=sys.stderr)
        return error.code


if __name__ == "__main__":
    # Git Bash emits UTF-8 paths even when Windows defaults to a legacy code page.
    for stream in (sys.stdout, sys.stderr):
        stream.reconfigure(encoding="utf-8", errors="backslashreplace")
    sys.exit(main())
