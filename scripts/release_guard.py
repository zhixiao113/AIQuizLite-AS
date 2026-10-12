"""Validate a release ref before the protected signing job can start."""
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent


def validate(event, ref, operation, version_name, ancestor, head, main_head):
    if operation not in {"publish", "bootstrap", "rollback"}:
        raise ValueError("Unsupported release operation")
    if not re.fullmatch(r"[0-9a-f]{40}", head):
        raise ValueError("Invalid checked-out commit")
    if event == "workflow_dispatch":
        if ref != "refs/heads/main" or head != main_head:
            raise ValueError("Manual production tasks must use current main")
    elif event == "push":
        if operation != "publish" or not ancestor:
            raise ValueError("Release tag must point to a commit on main")
        if not re.fullmatch(r"\d+\.\d+\.\d+", version_name) or ref != "refs/tags/v" + version_name:
            raise ValueError("Release tag must exactly match application versionName")
    else:
        raise ValueError("Untrusted workflow event")
    return operation, head


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True).strip()


def main():
    event = os.environ.get("RELEASE_EVENT", "")
    ref = os.environ.get("RELEASE_REF", "")
    operation = os.environ.get("RELEASE_OPERATION", "publish")
    head = git("rev-parse", "HEAD")
    main_head = git("rev-parse", "refs/remotes/origin/main")
    ancestor = subprocess.run(["git", "merge-base", "--is-ancestor", head, main_head], cwd=ROOT).returncode == 0
    source = (ROOT / "app/build.gradle").read_text(encoding="utf-8")
    version_name = re.search(r'versionName\s+"([^"]+)"', source).group(1)
    operation, head = validate(event, ref, operation, version_name, ancestor, head, main_head)
    output = os.environ.get("GITHUB_OUTPUT")
    if output:
        with open(output, "a", encoding="utf-8") as stream:
            stream.write("operation=" + operation + "\nsource_commit=" + head + "\n")
    print("Trusted release ref/version validated before production signing.")


if __name__ == "__main__":
    try:
        main()
    except Exception:
        print("Release ref validation failed; no production signing credentials used.")
        sys.exit(1)
