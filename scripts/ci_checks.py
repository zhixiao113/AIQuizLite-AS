"""Small, dependency-free CI supply-chain/credential gates; never print matching values."""
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
PATTERN = re.compile(r"(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,}|sk-[A-Za-z0-9_-]{30,}|-----BEGIN [A-Z ]*PRIVATE KEY-----)")


def inspect():
    paths = subprocess.check_output(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"], cwd=ROOT)
    errors = []
    for name in set(paths.decode("utf-8").split("\0")):
        if not name:
            continue
        path = ROOT / name
        if name == "local.properties" or name.startswith(".local-signing/") or path.suffix in {".jks", ".keystore"}:
            errors.append("Tracked/local-sensitive path: " + name)
        if path.is_file() and path.suffix in {".java", ".py", ".ps1", ".yml", ".yaml", ".gradle", ".properties", ".md"}:
            text = path.read_text(encoding="utf-8-sig", errors="replace")
            if PATTERN.search(text):
                errors.append("Potential credential in " + name)
    workflows = list((ROOT / ".github/workflows").glob("*.yml"))
    for path in workflows:
        text = path.read_text(encoding="utf-8")
        if "pull_request_target" in text:
            errors.append("Unsafe PR privilege trigger in " + path.name)
        for reference in re.findall(r"uses:\s*([^\s#]+)", text):
            if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+@[0-9a-f]{40}", reference):
                errors.append("Unpinned Action in " + path.name)
    wrapper = (ROOT / "gradle/wrapper/gradle-wrapper.properties").read_text(encoding="utf-8")
    if "distributionSha256Sum=31c55713e40233a8303827ceb42ca48a47267a0ad4bab9177123121e71524c26" not in wrapper:
        errors.append("Gradle download checksum is not pinned")
    if errors:
        raise RuntimeError("\n".join(errors))


if __name__ == "__main__":
    try:
        inspect()
        print("Credential paths, source patterns, Action pins and Gradle checksum gates passed.")
    except RuntimeError as error:
        print(error)
        sys.exit(1)
