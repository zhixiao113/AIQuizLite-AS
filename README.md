# AIQuizLite

面向个人学习者和备考考生的本地 Android 刷题应用。

功能：

- 日常刷题：随机刷完整题库，做错自动加入错题本。
- 错题本：错题连续答对 3 次后自动移出。
- 模拟考试：172 题随机顺序，选项顺序打乱，最后统一交卷。
- 题库导入：从本地文件、微信分享或下载库导入已有 JSON/ZIP 题库，不在应用内解析资料或调用 AI 出题。
- 题库管理：支持 JSON/ZIP 兼容导入、编辑、图片、导出、下载库、备份与恢复。
- AI 辅助：支持 OpenAI 兼容接口的题目讲解和单题重生成；这两项可选功能仍需配置 API 地址、API Key 和模型，并消耗 Token。

1.1.5 起取消应用内资料转题（TXT/PDF/DOCX），「总题库 → ＋ 导入题库」统一导入已有 JSON/ZIP；「下载库导入」选择已保存的 ZIP。题库导入无需配置 AI，不发网络请求或消耗 Token。已导入或以前生成的题库、图片和学习记录保留。若需把学习资料转成题库，请在外部完成，再按格式导入；导入页仍提供外部可用的 JSON 格式提示词与粘贴功能。

本次实现、兼容测试和手机待验收项见 [JSON/ZIP 导入验收](docs/qa/json-zip-only-import-2026-10-12.md)。

含图片的题库可打包为 ZIP，从「总题库 → ＋ 导入题库 → 选择本地 JSON / ZIP 文件」导入，也可从微信/文件管理器分享到本应用。选择 ZIP 后会直接导入，JSON 文件仍可编辑后点击「完成」。

```text
图文题库.zip
├── 题库.json
└── image/
    └── q1.png
```

JSON 根节点支持题目数组，或 `{"name":"图文题库","questions":[...]}`。题目的 `image` 字段填 `"image/q1.png"`，无图片时填 `null`；其他题目字段沿用现有 JSON 格式。也支持仅填图片文件名，若 `image/` 与 `images/` 内存在同名图片，须填写完整相对路径。

兼容旧版 `question-bank.json + images/` 包；允许将 JSON 和图片文件夹一起放在 ZIP 内的一层外目录中，图片路径相对于 JSON。每包只放一个题库 JSON，文件名区分大小写。图片支持 PNG、JPEG、WebP；导入后保存在应用本地，并可在刷题时查看、放大。同名题库会另建副本，不覆盖已有题库。

导入限制：JSON 不超过 4MB，单文件解压后不超过 25MB，总解压内容不超过 100MB，最多 500 个文件/目录条目。缺失图片、无效图片或异常路径会导致导入失败。

本地题库生成：

```powershell
python .\tools\extract_questions.py
```

构建 Debug APK：

```powershell
.\gradlew.bat assembleDebug
```

在线更新发布：

- 手机已安装包与更新包必须使用同一签名。已分发的 `app/release/app-release.apk` 使用 Android Studio 的 `../app_sign.jks`（alias `key0`）；项目中另一个 `keystore/aiquizlite.jks` 和 `.debug/debug.keystore` 均不兼容这个正式包。
- 每个新的真机验收版本由代理递增 `versionCode` 和 `versionName`，通过受保护云端原签名工作流完成构建、测试和发布，按授权代为批准验收发布，并同步同一 APK 到本地备用。`scripts/build-device-release.ps1` 保留为开发者本地构建方法；不能为已发布的 code 再重签。不需要用户再次手动升级版本号并构建。
- 首次在新电脑上执行 `scripts/configure-release-signing.ps1`，交互配置签名密码；Windows DPAPI 加密文件仅留本机。证书私钥和密码不能提交代码仓库或日志；云端原签名凭据仅按明确授权配置到受保护 production Secrets。
- 双击 `启动更新服务器.bat`。自动选择当前代理构建包或已准备包，核对 APK 真实包名、版本、签名后才替换清单；无需为同一已准备版本重新打开 Android Studio 构建。旧 APK 或其他签名会拒绝发布。
- 完整操作及故障处理见 [本地更新方法](docs/本地更新方法.md)。本地方式继续保留；[服务器自动更新方案](docs/deployment/服务器自动更新方案.md)目前只是待配置确认的方案，尚未部署。

本次云端原签名验收包：1.1.7 / versionCode 18；仓库 `app/release/app-release.apk` 与云端公开 APK 完全一致，SHA-256 为 `294e4391a57f029880e348c8baeb099dbaab16c1eb06b4d64b8f2e790d825675`。App 功能与已验收 1.1.5 相同，本次闭环云端发布；此前 1.1.6 发布检查失败未上线，失败标签保留不覆盖。在线正式版本以 [stable 更新清单](https://raw.githubusercontent.com/zhixiao113/AIQuizLite-AS/update-channel/stable.json) 为准；[GitHub Release](https://github.com/zhixiao113/AIQuizLite-AS/releases/latest)保留历史安装包，previous 为 1.1.5/code16。

GitHub 应用内在线更新：

- 用户只需「设置 → 检查更新」，正式客户端固定使用 GitHub，界面不提供更新源或服务器地址选择。旧版本保存或旧备份恢复的本地地址不会改变更新目标。不需要学校服务器、公网 IP、域名或穿透；手机网络必须能够访问 GitHub 清单和 APK 下载源。
- 保留“检查 → 提示 → 下载/取消 → 校验 → 系统确认安装”。HTTPS 不再忽略证书，云端安装包需核对哈希、大小、真实包名/版本和原签名；失败不会误报最新版。
- Actions CI 自动测试但不分发无签名产物；正式发布由 `v版本号` 标签触发，先核对属于 main 且版本一致，再经 production 拥有者审批后构建/原签名/上传/推广。手动发布与回滚保留。签名 Secrets 已于 2026-10-12 按用户明确授权配置，用户授权代理代为批准验收发布，原有审批保护保留。1.1.7 云端签名/发布和公开下载已实际核验成功，手机升级仍待用户确认，详见 [云端签名验收记录](docs/qa/cloud-signing-2026-10-12.md)。每个新验收版本由代理主动递增版本并构建。
- 安装包先上传并通过公开下载核验，再切换独立 stable 清单；保留 previous、最高已用版本和审计记录，提供健康检查失败恢复及手动回滚。回滚不是 Android 降级。
- 旧版仍指向穿透地址，需要通过原本地更新或一次性覆盖安装新版；不卸载。本地服务/BAT 仅保留给开发者和旧客户端迁移，不作为新版用户可切换的入口，证书检查不能绕过。
- 具体命令见 [GitHub 在线更新操作](docs/deployment/GitHub在线更新操作.md)，本次测试与待验收项见 [用户更新与图片提示验收](docs/qa/user-update-image-hint-2026-10-12.md)，初始部署记录见 [GitHub 实施记录](docs/qa/github-update-2026-10-12.md)。

所有题目与解析图片共用全屏查看器。操作提示只在本次安装首次成功打开图片时显示，后续换图片、换题库、重启或升级不重复；普通设置重置和学习数据备份恢复也不重置该提示。缩放、双击复位和关闭按钮保持不变。

仅准备文件、不启动服务器，可执行：

```powershell
python update_server.py "更新说明" --prepare-only --output-dir artifacts/update-release
python -m unittest discover -s tests -v
```

旧版签名不同的设备需要使用对应原密钥构建兼容包，不能靠更新代码绕过系统校验。不要直接卸载；需要迁移签名时先导出完整备份。具体排查记录见 `docs/qa/online-update-2026-09-18.md`。
