#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AIQuizLite 更新服务器（SakuraFrp 内网穿透模式）
用法：
    python update_server.py ["更新说明文字"]
功能：
    1. 校验 release APK 实际版本与 app/build.gradle 一致
    2. 验证固定签名后，把 APK 复制到 update-server 目录
    3. 生成 version.json（手机端据此检测更新）
    4. 在本机 127.0.0.1:4387 启动 HTTP 服务，由 SakuraFrp 隧道映射到固定公网地址
手机端无需填写服务器地址（已内置在 App 的 UpdateChecker.DEFAULT_SERVER_URL 中）。
"""

import argparse
import hashlib
import json
import os
import re
import shutil
import socket
import sys
import time
import subprocess
import tempfile
from pathlib import Path
import urllib.request
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

ROOT = os.path.dirname(os.path.abspath(__file__))
GRADLE_FILE = os.path.join(ROOT, "app", "build.gradle")
APK_SOURCE = os.path.join(ROOT, "app", "release", "app-release.apk")
SERVE_DIR = os.path.join(ROOT, "update-server")
PACKAGE_NAME = "com.example.aiquizlite"
# Public certificate of the distributed app/release APK (Android Studio: app_sign.jks).
# Do not switch this to the Gradle debug/development key: installed apps cannot follow it.
EXPECTED_SIGNER = "6973568484920238d35d94425c70601210ca2eaf7e728326785b66fb7e9576c8"
DEFAULT_PORT = 4387  # 与 SakuraFrp 隧道 mcMC 的本地端口一致


def parse_version():
    """从 app/build.gradle 读取 versionCode 与 versionName"""
    with open(GRADLE_FILE, encoding="utf-8") as handle:
        text = handle.read()
    code_match = re.search(r"versionCode\s+(\d+)", text)
    name_match = re.search(r'versionName\s+"([^"]+)"', text)
    version_code = int(code_match.group(1)) if code_match else 0
    version_name = name_match.group(1) if name_match else "0.0.0"
    return version_code, version_name


def prepared_release(version_code, version_name):
    """Read metadata only for this source version; a stale manifest cannot select an old APK."""
    try:
        with open(os.path.join(SERVE_DIR, "version.json"), encoding="utf-8") as handle:
            info = json.load(handle)
        if (isinstance(info, dict) and info.get("versionCode") == version_code
                and info.get("versionName") == version_name):
            return info
    except (OSError, ValueError):
        pass
    return {}


def select_default_apk(version_code, version_name):
    """Connect the desktop launcher to the agent's versioned release output, not a stale IDE APK."""
    current_build = Path(ROOT, "artifacts", "device-release-v{}".format(version_code),
                         "outputs", "apk", "release", "app-release.apk")
    if current_build.is_file():
        return str(current_build)
    info = prepared_release(version_code, version_name)
    file_name = info.get("apkFile")
    if file_name:
        if (not isinstance(file_name, str) or "/" in file_name or "\\" in file_name
                or not file_name.lower().endswith(".apk")):
            raise ValueError("更新清单的 APK 文件路径不安全，已拒绝启动")
        candidate = Path(SERVE_DIR, file_name)
        if candidate.resolve().parent != Path(SERVE_DIR).resolve():
            raise ValueError("更新清单的 APK 文件路径不安全，已拒绝启动")
        if candidate.is_file():
            return str(candidate)
    # Retain manual Android Studio builds; deploy still verifies actual version and signer.
    return APK_SOURCE


def sha256_of(path):
    """计算文件 SHA-256，供手机端下载后校验完整性"""
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def sdk_build_tools():
    sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    properties = Path(ROOT, "local.properties")
    if not sdk and properties.exists():
        match = re.search(r"^sdk\.dir=(.+)$", properties.read_text(encoding="utf-8"), re.M)
        if match:
            sdk = match.group(1).strip().replace(r"\:", ":").replace("\\\\", "\\")
    if not sdk:
        raise ValueError("找不到 Android SDK，请设置 local.properties 的 sdk.dir")
    candidates = sorted(Path(sdk, "build-tools").glob("*"),
                        key=lambda p: tuple(int(n) for n in re.findall(r"\d+", p.name)), reverse=True)
    for candidate in candidates:
        if (candidate / "lib" / "apksigner.jar").is_file() and (candidate / ("aapt2.exe" if os.name == "nt" else "aapt2")).is_file():
            return candidate
    raise ValueError("Android SDK 缺少 aapt2 / apksigner，请安装 Build Tools")


def run_tool(command):
    result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if result.returncode:
        raise ValueError("APK 校验工具失败：" + result.stderr.strip())
    return result.stdout


def inspect_apk(path):
    """Read the actual manifest and cryptographically verify the APK before publication."""
    build_tools = sdk_build_tools()
    aapt = build_tools / ("aapt2.exe" if os.name == "nt" else "aapt2")
    manifest = run_tool([str(aapt), "dump", "badging", str(path)])
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", manifest, re.M)
    if not package:
        raise ValueError("无法读取 APK 的包名和版本信息")
    java_home = os.environ.get("JAVA_HOME")
    java = str(Path(java_home, "bin", "java.exe" if os.name == "nt" else "java")) if java_home else "java"
    certificates = run_tool([java, "-jar", str(build_tools / "lib" / "apksigner.jar"),
                             "verify", "--print-certs", str(path)])
    signers = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$", certificates, re.M)
    if len(signers) != 1:
        raise ValueError("更新包必须使用唯一的固定发布签名")
    return {"packageName": package.group(1), "versionCode": int(package.group(2)),
            "versionName": package.group(3), "signerSha256": signers[0].lower()}


def deploy(version_code, version_name, notes, apk_source=None, serve_dir=None):
    """部署 APK 并生成 version.json"""
    apk_source = apk_source or APK_SOURCE
    serve_dir = serve_dir or SERVE_DIR
    if not os.path.exists(apk_source):
        print("[错误] 未找到 APK 文件：")
        print("       " + apk_source)
        print("       请先准备正式验收包，或用 --apk 指定已使用原证书签名的 APK。")
        return False
    try:
        os.makedirs(serve_dir, exist_ok=True)
        # Validate a private snapshot, so rebuilding the source cannot race publication.
        with tempfile.TemporaryDirectory(prefix=".prepare-", dir=serve_dir) as staging:
            snapshot = os.path.join(staging, "update.apk")
            shutil.copy2(apk_source, snapshot)
            actual = inspect_apk(snapshot)
            if actual["packageName"] != PACKAGE_NAME:
                raise ValueError("APK 包名不匹配，已保留原更新包")
            if (actual["versionCode"], actual["versionName"]) != (version_code, version_name):
                raise ValueError("APK 实际版本为 {versionName} (code {versionCode})，与源码不符，请重新编译".format(**actual))
            if actual["signerSha256"] != EXPECTED_SIGNER:
                raise ValueError("APK 签名与已分发的正式包不同，已拒绝发布；请使用原 app_sign.jks 签名，不能换用 keystore/aiquizlite.jks")
            digest = sha256_of(snapshot)
            apk_name = "AIQuizLite-v{}-{}.apk".format(actual["versionCode"], digest[:16])
            info = dict(actual, apkFile=apk_name, apkSha256=digest, notes=notes,
                        releasedAt=time.strftime("%Y-%m-%d %H:%M:%S"))
            manifest = os.path.join(staging, "version.json")
            with open(manifest, "w", encoding="utf-8") as handle:
                json.dump(info, handle, ensure_ascii=False, indent=2)
            # Immutable URL first; atomic manifest swap last, preserving in-flight downloads.
            os.replace(snapshot, os.path.join(serve_dir, apk_name))
            os.replace(manifest, os.path.join(serve_dir, "version.json"))
    except (OSError, ValueError) as error:
        print("[错误] " + str(error))
        return False

    print("[部署完成] 版本 v{} (code {})".format(version_name, version_code))
    print("           APK: {}".format(os.path.join(serve_dir, apk_name)))
    return True


def is_update_server_running(port):
    """探测端口上是否已有本更新服务器在运行"""
    try:
        with urllib.request.urlopen(
                "http://127.0.0.1:{}/version.json".format(port), timeout=2
        ) as response:
            return response.status == 200 and "apkFile" in response.read().decode("utf-8", "ignore")
    except Exception:
        return False


def find_free_port(start):
    """从 start 开始找一个空闲端口"""
    for port in range(start, start + 20):
        probe = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        probe.settimeout(1)
        try:
            probe.bind(("127.0.0.1", port))
            probe.close()
            return port
        except OSError:
            probe.close()
    return None


class QuietHandler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=SERVE_DIR, **kwargs)

    def end_headers(self):
        self.send_header("Cache-Control", "no-store")
        super().end_headers()

    def log_message(self, fmt, *args):
        print("  [请求] " + fmt % args)


def main():
    parser = argparse.ArgumentParser(description="AIQuizLite 更新服务器（SakuraFrp 内网穿透模式）")
    parser.add_argument("notes", nargs="?", default=None, help="更新说明文字；不填写时保留当前版本说明")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="本地监听端口，默认 4387（与 SakuraFrp 隧道一致）")
    parser.add_argument("--apk", default=None, help="待校验的 release APK；默认自动选择当前版本构建/已准备包")
    parser.add_argument("--prepare-only", action="store_true", help="只校验并生成更新文件，不启动服务")
    parser.add_argument("--output-dir", default=SERVE_DIR, help="更新文件输出目录")
    args = parser.parse_args()
    if not args.prepare_only and os.path.abspath(args.output_dir) != os.path.abspath(SERVE_DIR):
        parser.error("自定义输出目录请同时使用 --prepare-only")

    version_code, version_name = parse_version()
    if version_code == 0:
        print("[错误] 无法从 app/build.gradle 读取版本号。")
        return 1

    try:
        source = args.apk or select_default_apk(version_code, version_name)
    except ValueError as error:
        print("[错误] " + str(error))
        return 1
    info = prepared_release(version_code, version_name)
    notes = args.notes if args.notes is not None else info.get("notes", "新版本发布，建议更新。")
    if not isinstance(notes, str):
        notes = "新版本发布，建议更新。"
    print("[更新包来源] " + str(source))
    if not deploy(version_code, version_name, notes, source, args.output_dir):
        return 1
    if args.prepare_only:
        return 0

    if is_update_server_running(args.port):
        print()
        print("=" * 56)
        print("检测到更新服务器已在运行（端口 {}），无需重复启动。".format(args.port))
        print("  本地更新文件已刷新；请确认 mcMC 隧道开启后在手机检测更新。")
        print("=" * 56)
        return

    port = find_free_port(args.port)
    if port is None:
        print("[错误] 端口 {} 附近被占用，请关闭占用程序后重试。".format(args.port))
        return

    print()
    print("=" * 56)
    print("更新服务器已启动（SakuraFrp 内网穿透模式）")
    print("  本地监听: 127.0.0.1:{}".format(port))
    print("  请确认 SakuraFrp 启动器中隧道「mcMC」已开启：")
    print("    公网 TCP 41717 → 127.0.0.1:4387")
    print("  手机端 App 使用内置地址（UpdateChecker.DEFAULT_SERVER_URL）")
    print("  本机自测: http://127.0.0.1:{}/version.json".format(port))
    print("  按 Ctrl+C 停止服务器")
    print("=" * 56)
    print()

    try:
        server = ThreadingHTTPServer(("127.0.0.1", port), QuietHandler)
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n服务器已停止。")
    except PermissionError:
        print("[错误] 没有权限监听端口 {}，请尝试更换端口或关闭占用程序。".format(port))


if __name__ == "__main__":
    sys.exit(main())
