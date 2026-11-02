# 在线更新检查「Connection reset」诊断与修复（2026-11-02）

## 现象

手机上（1.1.9 / code20）点“检查更新”提示 `检查更新失败：Connection reset`；主页静默检查同样失败但不提示。云端发布侧无异常。

## 根因

客户端清单只有一个地址：`UpdateSourceRules.java:9-10` 的
`https://raw.githubusercontent.com/zhixiao113/AIQuizLite-AS/update-channel/stable.json`。
该主机在用户当前网络被重置（TLS 层 RST），而 GitHub 主体域名和 Release 资源域名正常。
因此 `UpdateChecker.checkUpdate` 在“取清单”这一步就抛 `java.net.SocketException: Connection reset`
（`UpdateChecker.friendlyError` 对此类异常直接透出 message），版本比较从未发生。

与签名、发布流程、清单内容无关：v1.1.9 的 Release 资产 `release-manifest.json`、`update-channel/stable.json`
与安装包版本/哈希/签名三者一致。

## 证据（桌面机直连、无代理，与手机同网络）

| 目标 | 结果 |
| --- | --- |
| `raw.githubusercontent.com/.../update-channel/stable.json` | ❌ `SocketException: Connection reset`（415ms，与用户报错一致） |
| `cdn.jsdelivr.net/gh/...` | ❌ `Connection reset` |
| `github.com/.../releases/download/v1.1.9/app-release.apk` | ✅ 302 → `release-assets.githubusercontent.com` → 206 |
| `github.com/.../releases/latest/download/release-manifest.json` | ✅ 200，745 字节，内容与 stable.json 完全一致 |
| `api.github.com/repos/.../releases/latest` | ✅ 206 |
| `fastly.jsdelivr.net` / `gcore.jsdelivr.net` / `testingcf.jsdelivr.net` 镜像 | ✅ 200，同一份清单 |
| `gh-proxy.com` / `ghproxy.net` 前置代理 | ✅ 206 |
| `jsd.onmicrosoft.cn` | ❌ PKIX 证书校验失败（不采用） |
| `raw.gitmirror.com` / `hub.gitmirror.com` | ❌ UnknownHost（不采用） |
| `ghfast.top` | ❌ 读超时 15s（不采用） |

TCP 层：`185.199.108.133:443`、`github.com:443`、`release-assets.githubusercontent.com:443` 均可连接（~100ms），
说明封锁发生在 TLS/SNI 层，不是 TCP 不可达。

## 修复

- `UpdateChecker`/`UpdateSourceRules` 改为**固定有序候选清单**，逐个尝试，第一个通过完整校验的清单生效：
  1. 规范分支文件 `raw.githubusercontent.com/.../update-channel/stable.json`（权威，回滚后也正确）；
  2. 最新 Release 资产 `github.com/.../releases/latest/download/release-manifest.json`
     （发布器在稳定清单验证通过后才把它标为 latest，内容同源；在 raw 被重置的网络上可达）；
  3. `fastly.jsdelivr.net` / `gcore.jsdelivr.net` 镜像（仅在 github.com 也不可达时使用，可能滞后约一次 CDN 缓存周期）。
- `GITHUB_MANIFEST` 常量取值保持不变，仍是已保存更新源的同一标识，不涉及偏好迁移；
  本地/自建源仍是单地址 `version.json` 协议，不参与 GitHub 候选。
- `validateRequest` 的 GitHub 主机白名单增加 `fastly.jsdelivr.net`、`gcore.jsdelivr.net`，
  仍强制 https、默认端口、默认证书校验；带端口、明文 http、仿冒域名（如 `fastly.jsdelivr.net.evil.org`）
  和 `cdn.jsdelivr.net`（本网络被重置）一律拒绝。
- `friendlyError` 对 `Connection reset` 给出可执行提示：“到 GitHub 更新服务器的连接被重置，当前网络可能屏蔽了 GitHub，请切换网络后重试。”

安全边界没有放宽：清单仍需 `packageName`、`signerSha256`（原证书）、`schemaVersion=1`、
`apkUrl` 仅限本仓库 `releases/download/<tag>/*.apk`、`apkSha256`、`apkSize` 全部匹配；
下载后的 APK 再校验大小、SHA-256、包名、versionCode 与原始签名。

## 测试

- 新增单元测试：候选顺序与逐个可用（均通过 `validateRequest`）、本地源仅单候选、镜像域名仿冒/`cdn.jsdelivr.net`/明文 http/带端口一律拒绝。
- 既有清单、传输、重定向与设置测试保持不变。

## 真机验收边界

本版本仅在桌面同网络验证了通道可达性与清单内容一致性；手机上仍需实际执行一次
检查更新 → 下载 → 安装，确认覆盖升级后题库、图片、错题、收藏、进度与设置保留。
