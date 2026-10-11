# 在线更新签名 / 版本问题（2026-09-18）

## 根因证据

- 用户截图：安装失败 -7，与已安装应用签名不同（界面显示 1.0.3 → 1.0.3）。没有连接用户真机，尚未读取手机证书。
- 发布脚本原先读取源码的版本，但无条件复制 `app/build/outputs/apk/debug/app-debug.apk`，不验证包内版本或签名。
- `update-server/version.json` 宣称 1.0.5/code 6；对应 `AIQuizLite-v1.0.5.apk` 实际是 1.0.4/code 5。
- 本地包有三条互不兼容的签名链（以下均为公开证书 SHA-256）：
  - 历史 `.debug/debug.keystore` / `update-server/AIQuizLite-v1.0.3.apk`：`e948c8ff38e7225913651b77acf3c3999e62f0a52841736562372abe9f0cf6a2`。
  - 新建 `keystore/aiquizlite.jks` / 误发布的 1.0.5 文件：`f2c8f93b8ee0ca3a52e136a439b60f3cb78fb52bb74a93426fff6ab6202c7902`。
  - Android Studio 手动发布 `app/release/app-release.apk`：`6973568484920238d35d94425c70601210ca2eaf7e728326785b66fb7e9576c8`；IDE 记录的文件为 `E:/projects/project-bs/app_sign.jks`，alias `key0`。

## 修复

- 默认发布源改成手动签名的 `app/release/app-release.apk`，固定校验其已分发证书 `697356…`。`--apk` 可指定其他产物路径，但仍必须符合该正式证书。
- 对 APK 快照运行 SDK aapt2 与 apksigner：验证实际包名、版本、签名有效性和证书；校验失败保留原清单。文件名包含内容哈希，先放置 APK，再原子替换 version.json，避免旧下载撞到新文件。
- 客户端下载完成后验证 APK 包名、真实版本和当前应用完整签名集合，再启动系统安装器。签名不符提示使用原签名包并先备份，避免直接卸载；当前实现有意要求签名集合完全一致，不支持密钥轮换。
- 修复无安装权限时下载弹窗不关闭；取消操作在启动安装器前再次检查；失败文件清理并释放下载连接。
- 源码版本递增为 1.0.6/code 7；Gradle 支持本机配置签名文件与 alias。未修改密码或原密钥。

## 验证和交付限制

- 发布脚本 5 项回归先在旧实现失败，修复后通过：旧包、新签名、错误包名、无效签名拒绝发布；正确包生成真实元数据及哈希。
- Android 126 项单元测试通过（含 8 项更新兼容性测试），Release 构建通过，Lint 0 errors / 87 warnings。已有更新网络实现的 TLS 信任所有证书告警仍存在；SHA-256 清单不是独立真实性证明，安装真实性仍依赖 APK 签名和系统安装器。
- Android 35 只读测试模拟器：历史签名 1.0.3 安装成功；新密钥 1.0.6 覆盖失败，错误为 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。同代码重新用原历史测试密钥签名后，`adb install -r` 成功，系统报告 1.0.6/code 7；全程未卸载。
- `artifacts/update-fix-release/` 是测试产物，包含新建密钥签名包和历史测试签名包，不是 `app_sign.jks` 正式交付包，不得放入正式更新服务器。
- 线上 `update-server` 未替换。需要使用原 `app_sign.jks` 在 Android Studio 重新打包 1.0.6，然后运行修复后的发布脚本；无需将签名密码提供给代理。
- 待完成：确认用户手机现有证书、原密钥正式打包、发布后真机验证检查更新 → 下载 → 覆盖安装 → 题库/学习记录保留。
