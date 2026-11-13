#!/usr/bin/env python3
"""Public-CLI regression tests for the verification receipt tools.

Run either way (stdlib only, no pytest needed):

    python -m unittest discover -s scripts/tests -p test_verification_receipts.py
    python scripts/tests/test_verification_receipts.py -v

Every case builds a throwaway Git repository under
``<worktree>/.runtime/retro-receipts/cases/<case>/``, copies the three public
scripts into it and replaces the fixture's ``scripts/verify.sh`` with a fake
payload that never touches Maven, npm or Playwright. The tests therefore only
observe the public CLI: exit code, stdout metadata, archived receipt bytes,
source snapshots and Git object bytes.

Cleanup re-checks that every path it removes still resolves inside that case
directory, removes junctions/symlinks as links (never following them) and never
references a path outside this worktree. Historical rejected cleanup paths from
earlier tickets are not touched.
"""

import hashlib
import os
import shutil
import subprocess
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
SCRIPTS = ROOT / "scripts"
CASES_ROOT = ROOT / ".runtime" / "retro-receipts" / "cases"

COPY_SCRIPTS = (
    "verify-run.sh",
    "verification_receipts.py",
    "check-verification-receipts.sh",
)

ARCHIVE_FILES = (
    ".gitattributes",
    "receipt.txt",
    "source-before.txt",
    "source-after.txt",
    "log-summary.txt",
)

FAKE_VERIFY = """#!/usr/bin/env bash
# Fake gate payload used only by scripts/tests/test_verification_receipts.py.
# It never invokes Maven, npm, Playwright or any real verification step.
set -u
printf '==> fake verify.sh %s\\n' "${1:-<default>}"
printf 'fake: real PostgreSQL container test skipped\\r\\n'
printf 'fake: one ordinary log line\\n'
printf 'fake: request token=%s\\n' FAKE_TOKEN_DO_NOT_ARCHIVE
if [[ -n "${FAKE_VERIFY_DRIFT:-}" ]]; then
  printf 'drift\\n' >> backend/src/app.txt
fi
if [[ -n "${FAKE_VERIFY_ADD_UNTRACKED:-}" ]]; then
  printf 'new source\\n' > backend/src/untracked-during-run.txt
fi
if [[ -n "${FAKE_VERIFY_DELETE_SOURCE:-}" ]]; then
  rm -f frontend/src/ui.ts
fi
if [[ -n "${FAKE_VERIFY_EXIT:-}" ]]; then
  exit "${FAKE_VERIFY_EXIT}"
fi
exit 0
"""

FAKE_SECRET = b"FAKE_TOKEN_DO_NOT_ARCHIVE"

UNICODE_SOURCE = "班级 说明.txt"


# --------------------------------------------------------------------------
# filesystem / environment helpers
# --------------------------------------------------------------------------

def _is_link_like(path):
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


def _remove_tree(path):
    """Remove exactly this tree; links are unlinked, never followed."""
    if _is_link_like(path):
        try:
            os.rmdir(path)
        except OSError:
            os.unlink(path)
        return
    if path.is_dir():
        for child in path.iterdir():
            _remove_tree(child)
        try:
            os.rmdir(path)
        except PermissionError:
            os.chmod(path, 0o777)
            os.rmdir(path)
    else:
        try:
            os.unlink(path)
        except PermissionError:
            # Git creates loose objects read-only on Windows; make this one
            # user-writable file deletable. Never applied to links.
            os.chmod(path, 0o666)
            os.unlink(path)


def safe_rmtree(path, root=None):
    """Remove ``path`` only if it really resolves inside ``root``."""
    target = Path(path)
    boundary = Path(root or CASES_ROOT).resolve()
    if not target.exists() and not _is_link_like(target):
        return
    resolved = target.resolve()
    if resolved == boundary or not resolved.is_relative_to(boundary):
        raise AssertionError(
            f"refusing to clean {target}: resolved {resolved} is outside {boundary}"
        )
    _remove_tree(target)


def remove_link(path):
    """Remove a symlink or junction itself; the target is never touched."""
    if Path(path).is_symlink():
        os.unlink(path)
        return
    os.rmdir(path)


_BASH = []


def git_exe():
    candidate = os.environ.get("PAIBAN_GIT") or shutil.which("git")
    if not candidate:
        raise RuntimeError("git executable not found on PATH")
    return candidate


def bash_exe():
    if _BASH:
        return _BASH[0]
    candidates = []
    if os.environ.get("PAIBAN_BASH"):
        candidates.append(os.environ["PAIBAN_BASH"])
    found = shutil.which("bash")
    if found:
        candidates.append(found)
    try:
        exec_path = subprocess.run(
            [git_exe(), "--exec-path"], capture_output=True, text=True, encoding="utf-8", check=True
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
        lowered = candidate.lower()
        if "system32" in lowered or "windowsapps" in lowered:
            continue  # WSL launcher / store alias, not Git Bash
        _BASH.append(candidate)
        return candidate
    raise RuntimeError("no Git Bash found; set PAIBAN_BASH to a Git for Windows bash.exe")


def _run(args, cwd, env=None, timeout=300):
    proc_env = dict(os.environ)
    for key in list(proc_env):
        if key.startswith("FAKE_VERIFY"):
            del proc_env[key]
    if env:
        proc_env.update(env)
    return subprocess.run(
        args,
        cwd=str(cwd),
        env=proc_env,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=timeout,
    )


def _git(repo, *args):
    proc = _run([git_exe(), *args], cwd=repo)
    if proc.returncode != 0:
        raise AssertionError(
            f"git {' '.join(args)} failed ({proc.returncode})\n"
            f"stdout:\n{proc.stdout}\nstderr:\n{proc.stderr}"
        )
    return proc.stdout


def _fixture_repo(case):
    """Create a fresh Git fixture; the whole case dir is rebuilt each time."""
    case_dir = CASES_ROOT / case
    safe_rmtree(case_dir)
    repo = case_dir / "repo"
    (repo / "scripts").mkdir(parents=True)
    for name in COPY_SCRIPTS:
        source = SCRIPTS / name
        if not source.is_file():
            raise AssertionError(f"missing public script: {source}")
        (repo / "scripts" / name).write_bytes(source.read_bytes())
    (repo / "scripts" / "verify.sh").write_bytes(FAKE_VERIFY.encode("utf-8"))
    (repo / "backend" / "src").mkdir(parents=True)
    (repo / "backend" / "src" / "app.txt").write_bytes(b"alpha\n")
    (repo / "backend" / "src" / UNICODE_SOURCE).write_bytes(b"unicode source\n")
    (repo / "frontend" / "src").mkdir(parents=True)
    (repo / "frontend" / "src" / "ui.ts").write_bytes(b"export const x = 1;\n")
    (repo / "scripts" / "tool.sh").write_bytes(b"#!/usr/bin/env bash\necho tool\n")
    (repo / ".githooks").mkdir()
    (repo / ".githooks" / "pre-commit").write_bytes(b"#!/usr/bin/env bash\nexit 0\n")

    hooks = case_dir / "empty-hooks"
    hooks.mkdir(parents=True, exist_ok=True)

    _git(repo, "init", "-q", "-b", "main")
    for key, value in (
        ("user.name", "Receipt Fixture"),
        ("user.email", "receipt-fixture@example.invalid"),
        ("commit.gpgsign", "false"),
        ("tag.gpgsign", "false"),
        ("core.hooksPath", str(hooks)),
        # The real workspace runs with autocrlf=true; keep that hazard visible.
        ("core.autocrlf", "true"),
        ("core.safecrlf", "false"),
        ("core.quotepath", "false"),
    ):
        _git(repo, "config", key, value)
    _git(repo, "add", "-A")
    _git(repo, "commit", "-q", "-m", "fixture baseline")
    return repo


def _read_fields(path):
    fields = {}
    text = path.read_text(encoding="utf-8", errors="surrogateescape")
    for line in text.splitlines():
        if not line or line.startswith("#"):
            continue
        key, sep, value = line.partition("=")
        if sep:
            fields[key] = value
    return fields


class ReceiptCase(unittest.TestCase):
    maxDiff = None

    def setUp(self):
        self.case_dir = CASES_ROOT / self._testMethodName
        self.repo = _fixture_repo(self._testMethodName)

    def tearDown(self):
        safe_rmtree(self.case_dir)

    # -- CLI shims ---------------------------------------------------------
    def verify_run(self, *args, env=None):
        return _run([bash_exe(), "scripts/verify-run.sh", *args], cwd=self.repo, env=env)

    def check_receipts(self, *args, env=None):
        return _run(
            [bash_exe(), "scripts/check-verification-receipts.sh", *args],
            cwd=self.repo,
            env=env,
        )

    def archive(self, run_id):
        return self.repo / ".scratch" / "verification" / run_id

    def runtime(self, run_id):
        return self.repo / ".runtime" / "verification" / run_id

    def fields(self, run_id):
        return _read_fields(self.archive(run_id) / "receipt.txt")

    def output(self, proc):
        return proc.stdout + "\n" + proc.stderr

    def assertExit(self, expected, proc, label):
        self.assertEqual(
            proc.returncode,
            expected,
            f"{label}: expected exit {expected}, got {proc.returncode}\n"
            f"stdout:\n{proc.stdout}\nstderr:\n{proc.stderr}",
        )

    def assertArchiveComplete(self, run_id):
        archive = self.archive(run_id)
        self.assertTrue(archive.is_dir(), f"missing archive dir {archive}")
        for name in ARCHIVE_FILES:
            self.assertTrue((archive / name).is_file(), f"missing archived file {name}")
        self.assertFalse(
            (archive / "verify.log").exists(),
            "raw verify.log must stay local in .runtime, not in the archive",
        )
        self.assertIn("* -text", (archive / ".gitattributes").read_text(encoding="utf-8"))
        manifest = (archive / "receipt-SHA256SUMS.txt").read_text(encoding="utf-8")
        rows = [row for row in manifest.splitlines() if row]
        self.assertEqual(len(rows), len(ARCHIVE_FILES), manifest)
        for row in rows:
            self.assertRegex(row, r"^[0-9a-f]{64}  [^/\\\s]+$")

    def refresh_manifest(self, archive):
        """Rewrite the checksum table after a deliberate test-side edit."""
        rows = []
        for name in ARCHIVE_FILES:
            digest = hashlib.sha256((archive / name).read_bytes()).hexdigest()
            rows.append(f"{digest}  {name}\n")
        (archive / "receipt-SHA256SUMS.txt").write_bytes("".join(rows).encode("utf-8"))


class UsageTests(ReceiptCase):
    def test_help_documents_cli_and_does_nothing(self):
        proc = self.verify_run("--help")
        self.assertExit(0, proc, "verify-run.sh --help")
        self.assertIn("usage", proc.stdout.lower())
        self.assertIn("run-id", proc.stdout)
        self.assertIn("--deploy", proc.stdout)
        self.assertFalse((self.repo / ".runtime" / "verification").exists())

    def test_missing_run_id_is_usage_error(self):
        proc = self.verify_run()
        self.assertExit(64, proc, "no run-id")
        self.assertIn("run-id", self.output(proc))
        self.assertFalse((self.repo / ".runtime" / "verification").exists())

    def test_unsafe_run_ids_are_rejected(self):
        for run_id in ("../escape", "/absolute", "nested/run", "nested\\run", ".", "..", "-rf", "a b"):
            proc = self.verify_run(run_id)
            self.assertExit(64, proc, f"run-id {run_id!r}")
            self.assertIn("run-id", self.output(proc))
        self.assertFalse((self.repo / ".runtime" / "verification").exists())

    def test_unknown_mode_is_usage_error(self):
        proc = self.verify_run("run-x", "--bogus")
        self.assertExit(64, proc, "bad mode")
        self.assertFalse((self.repo / ".runtime" / "verification").exists())

    def test_checker_help(self):
        proc = self.check_receipts("--help")
        self.assertExit(0, proc, "check --help")
        self.assertIn("--staged", proc.stdout)
        self.assertIn("HEAD", proc.stdout)


class RunTests(ReceiptCase):
    def test_success_archives_full_receipt(self):
        proc = self.verify_run("run-1")
        self.assertExit(0, proc, "successful run")
        head = _git(self.repo, "rev-parse", "HEAD").strip()

        self.assertArchiveComplete("run-1")
        self.assertTrue((self.runtime("run-1") / "verify.log").is_file())

        fields = self.fields("run-1")
        self.assertEqual(fields["RUN_ID"], "run-1")
        self.assertEqual(fields["MODE"], "full")
        self.assertEqual(fields["MODE_FLAG"], "--full")
        self.assertEqual(fields["COMMAND"], "bash scripts/verify.sh --full")
        self.assertEqual(fields["HEAD_BEFORE"], head)
        self.assertEqual(fields["HEAD_AFTER"], head)
        self.assertEqual(fields["HEAD_IDENTICAL"], "1")
        self.assertEqual(fields["SOURCE_SCOPE"], "backend frontend scripts .githooks")
        self.assertEqual(fields["SOURCE_IDENTICAL"], "1")
        self.assertEqual(fields["SOURCE_DRIFT_FILES"], "0")
        self.assertEqual(fields["COMMAND_EXIT_CODE"], "0")
        self.assertEqual(fields["TOOL_EXIT_CODE"], "0")
        self.assertEqual(fields["CHECKSUM_TABLE"], "receipt-SHA256SUMS.txt")
        self.assertEqual(fields["ARCHIVE_FILES"], str(len(ARCHIVE_FILES)))
        self.assertTrue(fields["START_UTC"].endswith("Z"))
        self.assertTrue(fields["END_UTC"].endswith("Z"))
        self.assertGreater(int(fields["SOURCE_FILES"]), 0)
        self.assertEqual(
            fields["SOURCE_BEFORE_SHA256"],
            hashlib.sha256((self.archive("run-1") / "source-before.txt").read_bytes()).hexdigest(),
        )

        # receipts are evidence, never auto-staged
        self.assertEqual(_git(self.repo, "diff", "--cached", "--name-only").strip(), "")
        self.assertIn(
            ".scratch/verification/run-1/",
            _git(self.repo, "status", "--porcelain", "--untracked-files=all"),
        )

        check = self.check_receipts("--worktree")
        self.assertExit(0, check, "worktree check")
        self.assertIn("INTEGRITY only", check.stdout)

    def test_command_failure_preserves_exit_code(self):
        proc = self.verify_run("run-7", env={"FAKE_VERIFY_EXIT": "7"})
        self.assertExit(7, proc, "fake gate exit 7")
        fields = self.fields("run-7")
        self.assertEqual(fields["COMMAND_EXIT_CODE"], "7")
        self.assertEqual(fields["TOOL_EXIT_CODE"], "7")
        self.assertEqual(fields["SOURCE_IDENTICAL"], "1")
        self.assertArchiveComplete("run-7")
        # A recorded non-zero command history is preserved evidence, not damage.
        check = self.check_receipts("--worktree")
        self.assertExit(0, check, "integrity of a failed-command receipt")

    def test_source_drift_returns_86(self):
        proc = self.verify_run("run-drift", "--quick", env={"FAKE_VERIFY_DRIFT": "1"})
        self.assertExit(86, proc, "source drift")
        fields = self.fields("run-drift")
        self.assertEqual(fields["MODE"], "quick")
        self.assertEqual(fields["MODE_FLAG"], "--quick")
        self.assertEqual(fields["COMMAND_EXIT_CODE"], "0")
        self.assertEqual(fields["TOOL_EXIT_CODE"], "86")
        self.assertEqual(fields["SOURCE_IDENTICAL"], "0")
        self.assertEqual(fields["SOURCE_DRIFT_FILES"], "1")
        self.assertIn("backend/src/app.txt", fields["SOURCE_DRIFT_SAMPLE"])
        self.assertIn("backend/src/app.txt", proc.stdout)
        self.assertArchiveComplete("run-drift")
        self.assertExit(0, self.check_receipts("--worktree"), "drifted receipt integrity")

    def test_untracked_source_created_during_run_is_drift(self):
        proc = self.verify_run(
            "run-new-src", env={"FAKE_VERIFY_ADD_UNTRACKED": "1"}
        )
        self.assertExit(86, proc, "untracked source created during the run")
        fields = self.fields("run-new-src")
        self.assertEqual(fields["COMMAND_EXIT_CODE"], "0")
        self.assertEqual(fields["TOOL_EXIT_CODE"], "86")
        self.assertEqual(fields["SOURCE_IDENTICAL"], "0")
        self.assertEqual(fields["SOURCE_DRIFT_UNTRACKED"], "1")
        self.assertIn("backend/src/untracked-during-run.txt", fields["SOURCE_DRIFT_SAMPLE"])
        self.assertArchiveComplete("run-new-src")
        after = (self.archive("run-new-src") / "source-after.txt").read_text(encoding="utf-8")
        self.assertIn("untracked", after)
        self.assertExit(0, self.check_receipts("--worktree"), "receipt integrity")
        stray = self.repo / "backend" / "src" / "untracked-during-run.txt"
        if stray.exists():
            stray.unlink()

    def test_deleted_source_during_run_keeps_receipt_and_real_exit(self):
        proc = self.verify_run(
            "run-del",
            env={"FAKE_VERIFY_DELETE_SOURCE": "1", "FAKE_VERIFY_EXIT": "3"},
        )
        self.assertExit(86, proc, "source deleted during the run")
        fields = self.fields("run-del")
        self.assertEqual(fields["COMMAND_EXIT_CODE"], "3", "real command exit is preserved")
        self.assertEqual(fields["TOOL_EXIT_CODE"], "86")
        self.assertEqual(fields["SOURCE_DRIFT_MISSING"], "1")
        self.assertIn("frontend/src/ui.ts", fields["SOURCE_DRIFT_SAMPLE"])
        self.assertArchiveComplete("run-del")
        after = (self.archive("run-del") / "source-after.txt").read_text(encoding="utf-8")
        self.assertIn("missing", after)
        self.assertExit(0, self.check_receipts("--worktree"), "receipt integrity")

    def test_untracked_source_rejected_scratch_cache_allowed(self):
        (self.repo / "backend" / "src" / "untracked.txt").write_bytes(b"wip\n")
        proc = self.verify_run("run-untracked")
        self.assertExit(65, proc, "untracked source")
        self.assertIn("backend/src/untracked.txt", self.output(proc))
        self.assertFalse(self.archive("run-untracked").exists())
        self.assertFalse(self.runtime("run-untracked").exists())

        (self.repo / "backend" / "src" / "untracked.txt").unlink()
        cache = self.repo / ".scratch" / "dingtalk-multiuser" / "cache"
        cache.mkdir(parents=True)
        (cache / "stale.bin").write_bytes(b"old cache\n")
        (self.repo / "notes.txt").write_bytes(b"scratch note\n")
        proc = self.verify_run("run-clean")
        self.assertExit(0, proc, "untracked non-source files must not block a run")

    def test_duplicate_run_id_is_refused(self):
        self.assertExit(0, self.verify_run("dup"), "first run")
        snapshot = {
            name: (self.archive("dup") / name).read_bytes() for name in ARCHIVE_FILES
        }
        proc = self.verify_run("dup")
        self.assertExit(65, proc, "duplicate run-id")
        self.assertIn("already exists", self.output(proc))
        for name, data in snapshot.items():
            self.assertEqual((self.archive("dup") / name).read_bytes(), data, name)

    def test_output_path_escape_is_rejected(self):
        outside = self.case_dir / "outside"
        outside.mkdir(parents=True)
        self._make_link(self.repo / ".runtime", outside)
        proc = self.verify_run("escape-1")
        self.assertExit(66, proc, ".runtime link escape")
        self.assertIn("outside the worktree", self.output(proc))
        self.assertFalse((outside / "verification").exists())

        remove_link(self.repo / ".runtime")
        (self.repo / ".scratch").mkdir(exist_ok=True)
        self._make_link(self.repo / ".scratch" / "verification", outside)
        proc = self.verify_run("escape-2")
        self.assertExit(66, proc, ".scratch link escape")
        self.assertFalse((outside / "escape-2").exists())

    def test_worktree_checker_rejects_archive_link_escape(self):
        outside = self.case_dir / "outside"
        outside.mkdir(parents=True)
        self._make_link(self.repo / ".scratch" / "verification", outside)
        proc = self.check_receipts("--worktree")
        self.assertExit(1, proc, "worktree archive link escape")
        self.assertIn("outside the worktree", self.output(proc))
        self.assertEqual(list(outside.iterdir()), [])

    def _make_link(self, link, target):
        link.parent.mkdir(parents=True, exist_ok=True)
        try:
            os.symlink(str(target), str(link), target_is_directory=True)
            return
        except (OSError, NotImplementedError, AttributeError):
            pass
        proc = _run(["cmd", "/c", "mklink", "/J", str(link), str(target)], cwd=self.repo)
        if proc.returncode != 0:
            self.skipTest(f"cannot create directory link: {self.output(proc)}")


class ArchiveTests(ReceiptCase):
    def test_log_archive_is_metadata_only(self):
        self.assertExit(0, self.verify_run("run-log"), "run")
        raw_log = (self.runtime("run-log") / "verify.log").read_bytes()
        self.assertIn(
            FAKE_SECRET, raw_log, "the fake gate writes the token into the raw log"
        )
        for name in ARCHIVE_FILES:
            data = (self.archive("run-log") / name).read_bytes()
            self.assertNotIn(FAKE_SECRET, data, f"{name} must not archive log text")
        summary = (self.archive("run-log") / "log-summary.txt").read_text(encoding="utf-8")
        self.assertIn("LOG_SHA256=", summary)
        self.assertIn("LOG_EXCERPT=redacted-by-design", summary)
        fields = self.fields("run-log")
        self.assertEqual(fields["LOG_SUMMARY_KIND"], "metadata-only")
        self.assertNotIn(FAKE_SECRET.decode(), summary)

    def test_unicode_and_space_source_name_round_trip(self):
        proc = self.verify_run("run-unicode")
        self.assertExit(0, proc, "unicode source")
        before = (self.archive("run-unicode") / "source-before.txt").read_text(
            encoding="utf-8"
        )
        self.assertIn(UNICODE_SOURCE, before)
        self.assertIn("backend/src/", before)
        self.assertExit(0, self.check_receipts("--worktree"), "unicode receipt integrity")

    def test_crlf_archive_bytes_survive_git(self):
        self.assertExit(0, self.verify_run("run-crlf"), "crlf run")
        archive = self.archive("run-crlf")
        # Rewrite one archived file with CRLF bytes and re-sign the manifest:
        # this is exactly the byte pattern a Windows-authored receipt can have.
        crlf = (
            b"# metadata-only log digest\r\n"
            b"LOG_BYTES=0\r\n"
            b"LOG_EXCERPT=redacted-by-design\r\n"
        )
        (archive / "log-summary.txt").write_bytes(crlf)
        self.refresh_manifest(archive)
        _git(self.repo, "add", ".scratch/verification/run-crlf")
        _git(self.repo, "commit", "-q", "-m", "archive receipt")
        blob = subprocess.run(
            [
                git_exe(),
                "-C",
                str(self.repo),
                "cat-file",
                "blob",
                "HEAD:.scratch/verification/run-crlf/log-summary.txt",
            ],
            cwd=str(self.repo),
            capture_output=True,
            timeout=120,
        )
        self.assertEqual(blob.returncode, 0, blob.stderr)
        self.assertEqual(
            blob.stdout,
            crlf,
            "committed Git object bytes must equal the archived bytes (no EOL rewriting)",
        )
        self.assertIn(b"\r\n", blob.stdout)
        check = self.check_receipts()
        self.assertExit(0, check, "HEAD check")
        self.assertIn("REF=HEAD", check.stdout)

    def test_staged_tamper_is_detected_and_head_stays_clean(self):
        self.assertExit(0, self.verify_run("run-tamper"), "run")
        _git(self.repo, "add", "-A")
        _git(self.repo, "commit", "-q", "-m", "archive receipt")
        self.assertExit(0, self.check_receipts("--staged"), "staged check")

        receipt = self.archive("run-tamper") / "receipt.txt"
        receipt.write_bytes(receipt.read_bytes() + b"TAMPERED=1\n")
        _git(self.repo, "add", ".scratch/verification/run-tamper/receipt.txt")
        index_before = hashlib.sha256((self.repo / ".git" / "index").read_bytes()).hexdigest()
        staged = self.check_receipts("--staged")
        self.assertExit(1, staged, "tampered staged receipt")
        self.assertIn("MISMATCH", staged.stdout)
        self.assertIn("receipt.txt", staged.stdout)
        self.assertIn("REF=INDEX", staged.stdout)
        index_after = hashlib.sha256((self.repo / ".git" / "index").read_bytes()).hexdigest()
        self.assertEqual(index_before, index_after, "checker must not write the Git index")

        head = self.check_receipts()
        self.assertExit(0, head, "HEAD receipt still intact")

    def test_worktree_mode_is_labelled_and_separate(self):
        self.assertExit(0, self.verify_run("run-wt"), "run")
        head = self.check_receipts()
        self.assertExit(0, head, "no receipts in HEAD yet")
        self.assertIn("RECEIPT_DIRS=0", head.stdout)

        summary = self.archive("run-wt") / "log-summary.txt"
        summary.write_bytes(summary.read_bytes() + b"hand edited\n")
        worktree = self.check_receipts("--worktree")
        self.assertExit(1, worktree, "edited worktree receipt")
        self.assertIn("REF=WORKTREE", worktree.stdout)
        self.assertIn("not Git object validation", worktree.stdout)
        self.assertExit(0, self.check_receipts("--staged"), "index untouched by worktree edits")


if __name__ == "__main__":
    unittest.main(verbosity=2)
