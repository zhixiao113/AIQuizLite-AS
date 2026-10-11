# 真机验收构建与在线更新交付

## 用户确认的规则（2026-10-11）

每次交付新的真机验收版本，由代理主动完成版本号递增、原证书 Release 构建、测试、签名校验和在线更新文件准备；不能仅交付 Debug 包，不能再要求用户自行修改版本号并构建。

固定签名文件：项目父目录 `app_sign.jks`，别名 `key0`。固定证书 SHA-256：
`6973568484920238d35d94425c70601210ca2eaf7e728326785b66fb7e9576c8`。
不得改用 `keystore/aiquizlite.jks`，不得以卸载旧应用解决签名不匹配。

## 构建步骤

1. 检查源码版本及 `update-server/version.json`，用补丁递增 `app/build.gradle` 的 versionCode 与 versionName。新验收包 code 必须大于已准备的版本；构建失败且未发布可重试同一版本。
2. 运行 `scripts/build-device-release.ps1`。它会校验原证书、运行更新发布的 5 项测试、Release lint/单元测试/构建，并再次验证 APK 的包名、版本和签名后原子更新本地在线更新清单。
3. 交付已验证的 Release APK 下载链接，同时报告实际版本、测试结果、更新服务状态和剩余真机验收项。
4. 本地 HTTP 服务监听 127.0.0.1:4387，用户现有 SakuraFrp 隧道应映射到该端口。准备文件不等于公网可用：分别检查本地 HTTP 与公网地址；不得假定用户隧道已开启。
5. 不自动安装、卸载或恢复用户手机数据。手机覆盖升级后需确认题库与进度保留，再执行功能验收。

重新打开「启动更新服务器.bat」无需重建：入口依次选当前 versionCode 的 `artifacts/device-release-vN/outputs/apk/release/app-release.apk`、匹配当前源码版本的更新清单所指向的已准备 APK、手动 Android Studio 输出 `app/release/app-release.apk`。显式 `--apk` 仍优先；每次选出的包都必须重新通过实际版本、包名和固定签名校验。未传更新说明时保留当前版本说明。

```powershell
./scripts/build-device-release.ps1 -Notes '本次验收更新说明'
```

## 本机凭据

签名密码只在 `.local-signing/release-passwords.json` 中用 Windows DPAPI 加密保存，已由 `.gitignore` 排除；仅当前 Windows 用户在本机可以解密。构建时通过临时环境变量提供密码，不作为命令行参数、不写入源码或记忆，脚本结束恢复环境。

更换电脑/Windows 用户，或修改签名密码后，需重新运行 `scripts/configure-release-signing.ps1` 交互配置；不复制密码到聊天记录或项目文档。证书库密码与私钥密码可以分别配置。

## 当前限制

更新客户端原有 HTTPS 信任策略仍需独立加固，本轮不修改隧道或 TLS 配置。签名校验不替代安全传输。本机缺少可连接的测试手机，构建交付不代表真机功能已验收。

## 首次执行结果（2026-10-11）

- 版本从 1.1.1 / code 12 升级至 1.1.2 / code 13。
- 153 项 Release 单元测试、5 项更新发布测试通过；Release lint 0 错误 / 112 警告，assembleRelease 成功。
- 原证书与 `key0` 签名成功，包名/实际版本/证书均经发布器再次校验。
- APK：`artifacts/device-release-v13/outputs/apk/release/app-release.apk`。
- 更新包：`update-server/AIQuizLite-v13-acb3ee5710c31e7d.apk`，清单已同步；重复交付同一 versionCode 被构建脚本拒绝。
- 启动隐藏窗口本地服务，127.0.0.1:4387/version.json 返回 HTTP 200、code13、1.1.2，更新包文件哈希与清单一致。
- 未发现 SakuraFrp 进程；公网更新地址 TLS 握手 EOF，尚不能确认在线下载可用。需开启既有隧道再验证，不能把本地准备成功当作公网发布验收。
- ADB 无设备；未安装、未卸载、未恢复手机数据。

## BAT 启动入口修复（2026-10-11）

用户双击 BAT 后仍读取旧 Android Studio code12 包，导致版本不符。根因是入口默认 APK 路径未接上代理的 code13 构建目录；不是新包缺少或签名错误。

已增加默认产物选择和安全路径检查，更新 BAT 使用说明。新增启动入口测试先复现两项失败，修复后共 10 项更新发布/入口测试通过。实际无参数运行 BAT 已选择 code13 包、检测到现有 4387 服务并正常返回；HTTP 再次确认 1.1.2/code13 和原更新说明。显式指定旧包仍拒绝发布，签名校验未关闭。

本次只修复电脑端入口，未修改 Android 应用、未递增或重建 APK；手机继续使用已验证的 1.1.2/code13 验收包。公网和真机功能验收状态未因此改变。
