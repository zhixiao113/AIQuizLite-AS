# Quality gates 公开包下载 504 的诊断与修复（2026-11-09）

## 现象

push 到 `main` 后 `Quality gates / quality` 约 6s 失败，失败步骤是 “Publication regressions and supply-chain gates”，
堆栈停在 `scripts/verify_public_baseline.py:11` → `scripts/github_release.py:69`
（`urllib.request.build_opener(PublicRedirect()).open(...)`）：

```
urllib.error.HTTPError: HTTP Error 504: Gateway Time-out
...
github_release.ReleaseError: Public download unavailable or TLS verification failed
```

取的是 `releases/download/v1.1.5/release-manifest.json`。同一提交的 gradle 步骤根本没机会执行，
所以 3/3 之类的检查数不可信；随后几个提交也连带显示 “All checks have failed”。

## 根因

1. `download()` 只有一次尝试：GitHub 发布 CDN/网关在短时事件中返回 504 是瞬时的，
   单次失败就把整个质量门（以及 `release.yml` 发布前的同一道检查）判死。
2. 所有传输异常都被折叠成 `Public download unavailable or TLS verification failed`，
   丢掉了 HTTP 状态码，看起来像证书校验/网络策略问题，实际只是网关超时。

安全边界本身没有问题：清单仍需包名、原始签名、`schemaVersion`、`apkUrl` 白名单、
`apkSha256`、`apkSize` 全部匹配，下载后还会再验大小、哈希、包名/版本/签名。

## 修复

`scripts/github_release.py`：

- 只对瞬时错误做有界重试：HTTP 408/425/429/500/502/503/504，以及连接重置、超时、
  截断响应（`http.client.HTTPException`）。最多 5 次尝试，间隔 2/5/10/20s（合计 37s 上限）。
- 服务端给了 `Retry-After` 时取较大值，仍上限 60s。
- 不重试：URL/主机白名单拒绝、超过大小上限、`ReleaseError` 等策略错误，以及永久 4xx（如 404）。
- 失败信息带上状态码（`Public download failed with HTTP 504`），不再一律说 TLS。
- 重试写文件用 `wb` 重开，半截文件不会拼进下一次尝试。
- `Publisher.check_health` 本来就有 8 次 ×10s 的轮询，改为 `retry=False`，避免两套退避相乘
  （否则最坏情况会把发布 job 拖到超时）。

## 测试

- `tests/test_github_release.py::DownloadRetryTest` 新增 7 个用例：504 后成功、重试用尽报状态码、
  404 不重试、连接重置/截断响应重试、策略错误与非法 URL 不重试、`Retry-After` 生效、
  `retry=False` 只打一次。
- 另用真实本地 HTTP 服务做了临时冒烟（未入库）：504×2 → 200 重试成功；503 + `Retry-After: 1`
  实际等待后报 `HTTP 503`；连接拒绝重试后报 `unavailable`；`retry=False` 立即失败。
- `python3 scripts/ci_checks.py` 通过；`python3 -m unittest tests.test_github_release` 26 项通过，
  其余 2 项失败是本机 Windows 沙箱拒绝写系统临时目录（`PermissionError`），与本次改动无关。

## 验证边界

本机网络直连 `github.com:443` 不可达（curl 连接超时，与
`docs/qa/online-update-connection-reset-2026-11-02.md` 记录的网络环境一致），
无法在本地复现真实下载链路；修复效果以重新触发 Quality gates 的结果为准。
若 504 持续超过约 40s 的退避窗口，质量门仍会失败并明确报出状态码，不会静默放行。
