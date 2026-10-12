# GitHub 在线更新操作

正式客户端固定读取公开 `update-channel/stable.json`，APK 使用固定版本 Release 地址。用户设置仅保留「检查更新」，不展示更新源或服务器地址；历史本地选择及旧备份恢复不能改变正式更新目标。手机不需要登录 GitHub，也不依赖学校虚拟机、Nginx或公网 SSH。发布失败不等于 App 更新失败；不要把失败任务当成已完成发布。

## 当前脚本与入口

- `scripts/github_release.py`：校验 APK、上传草稿、公开下载核验、更新清单、健康检查、失败恢复、手动回滚。报告为本机 `artifacts/github-release/report.json`，云端同名报告作为 Actions 附件；精简审计记录保存在清单分支。
- `scripts/publish-github-release.ps1`：使用本机已有 GitHub Git 登录，仅把 Token 临时放在进程环境里，结束恢复；不用在命令行输入 Token。
- `.github/workflows/ci.yml`：main/PR 自动运行无私钥质量检查，产物不签名，不作为安装包分发。
- `.github/workflows/release.yml`：暂采用 GitHub Actions 页面手动触发，完整步骤自动执行；只有 main 可运行生产任务。等云端签名和真机验收完成再讨论是否启用版本 tag 触发，不把每次普通代码提交都发布为手机更新。

## 首次初始化

基础清单由已核验的 1.1.2/code13 原签名 Release 初始化一次，不能覆盖已有清单：

```powershell
./scripts/publish-github-release.ps1 -Mode bootstrap
```

已有通道时再次 bootstrap 会拒绝，这是保护措施，不是要求重新安装。首次清单没有 previous；发布下一版后会保存上一成功版本。

## 本机签名与发布

代理先提高 `app/build.gradle` 的两个版本号，核对本地已准备版本、清单最高已用版本与公开候选包，再运行：

```powershell
./scripts/build-device-release.ps1 -Notes '本次更新说明'
```

将源码与版本提交并推送至 main 后：

```powershell
./scripts/publish-github-release.ps1 -Mode publish -Notes '本次更新说明'
```

源码未提交时发布器拒绝发布，避免安装包被错误标为某个旧 commit。已存在 Release/tag 不覆盖；即使先前发布失败，也需核对是否曾公开候选包，公开过的 code 不重用。保留原密钥与签名；无私钥 CI 包或 Debug 包不能拿来发布。

公开 APK 上传并校验通过后才修改 stable。安装包与清单都验证完成才报告成功；回滚只是切换清单，不修改或删除旧 APK。GitHub “latest”展示不是客户端发布指针，手机以 stable.json 为准。

## 云端自动构建签名

仅在用户明确授权后，在 `production` Environment 配置原证书的 `AIQUIZ_RELEASE_KEYSTORE_BASE64`、`AIQUIZ_RELEASE_STORE_PASSWORD`、`AIQUIZ_RELEASE_KEY_PASSWORD`。Base64 不是加密，编码内容也不能提交或打印。原 `key0` 和固定证书指纹由脚本验证，缺少凭据直接失败，不回退其他密钥。

建议 production 仅允许 main，初期保留发布确认。工作流需 contents 写权限修改专用清单分支，不为此关闭 main 保护。仅一个维护者时不要配置无法完成的多人审批。若尚未授权/配置云端 Secrets，本机签名发布仍可用，云端 publish 不会成功。

进入仓库 Actions → Publish or roll back online update → Run workflow，选择 main：

- `publish`：原证书构建当前源码版本、完整验证并发布。
- `rollback`：target_code 留空恢复 previous；也可填一个已经保存、验证且未被撤回的较早 code。
- `bootstrap`：只用于首次建立 code13 基础清单。

每次新验收版本仍由代理修改版本并构建，不能让用户重复手动升级版本号；Actions 不凭运行次数生成与源码不一致的版本。

production 已按初期方案限制 main 并要求仓库拥有者确认。当前还未导入签名 Secrets，因此不要把已通过的无私钥 CI 当成正式云端签名成功；当前安装包通过原证书本机构建与受控发布工具交付。

## 手动回滚与日志

```powershell
./scripts/publish-github-release.ps1 -Mode rollback
./scripts/publish-github-release.ps1 -Mode rollback -TargetCode 13
```

回滚前重验历史 APK 的版本、哈希、大小和签名；回滚后复核公开清单。最高使用版本号保持不变，被撤回包保留但不会自动推广。若另一个任务已经更新了清单，本任务不强推、不覆盖较晚提交；检查最终状态后再决定操作。

上传/验证失败保持 stable 不变；切换后健康检查失败只恢复本次切换，自动恢复失败仍是失败状态。报告会区分失败、已恢复、未验证和被更晚操作取代。GitHub 不可用时无法保证立即回滚，也不能宣称成功。

客户端旧版本已安装到手机后不能自动降级；修复要使用高于所有已发版本的 code。完整日志由 Actions 保存，公开审计只写必要版本和状态；不能把密码、私钥、Token 或带敏感信息的原始异常上传到日志。

## 手机过渡与验收

现有 code13 仍使用旧穿透地址，无法自行发现 GitHub 清单。先通过保留的本地在线更新，或一次性下载同签名过渡 APK 并覆盖安装；不要卸载。

1.1.4/code15 起用户直接通过「设置 → 检查更新」检查 GitHub，不再选择或填写地址。首页自动检查和设置手动检查使用同一固定通道；1.1.3 曾选择本地地址的设备升级后也恢复 GitHub，学习设置与数据不删除。HTTPS 使用系统可信证书，不再忽略证书。

开发者本地脚本、BAT 和已校验的本地协议保留，用于旧客户端迁移、准备安装文件及开发调试；生产 App 不从用户偏好切换到本地地址。开发调试可在受控开发代码中显式调用传输 API，不能将地址选择再暴露给普通用户。旧 FRP 入口若证书无效须单独修复，不能用桌面 BAT 正常代替公网验证。

必须在目标手机确认：实际网络下清单/APK可达、安装授权、进度/取消/断网重试、覆盖后数据保留，以及从过渡包升到第二个更高 code。只通过电脑检查与单元测试不能关闭真机验收项。
