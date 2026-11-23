# 浏览器插件独立仓库与应用内「插件」入口（2026-11-23）

## 需求

- 浏览器插件（`browser-extension/`）上传到独立 GitHub 仓库。
- 应用仓库 README 附上仓库链接并说明插件作用。
- 应用侧边栏设置项下新增「插件」入口，点进去可看到仓库链接和插件作用说明。

扫码/局域网直传方案经评估后由用户决定不实施，本次不涉及传输功能。

## 实现

### 1. 独立仓库

- 仓库：`zhixiao113/AIQuizLite-browser-extension`（公开，默认分支 `main`）。
- 内容 94 个文件：`extension/` 源码、`dist/` 构建产物、`scripts/`、`tests/`、`package.json`、`package-lock.json`、仓库级 `README.md` 与 `.gitignore`。
- 未提交：`node_modules/`、`artifacts/`、`.test-profile/`、开发用 `TODO.md`（内部记录留在主仓库）。
- 仓库 README 面向使用者重写：作用、能力、开发者模式安装、使用流程、输出格式、隐私、限制（明确不提供扫码/直传）、本地开发、目录与模块、版本记录。
- 提交前扫描扩展源码，未发现硬编码密钥；`.gitignore` 排除依赖与测试产物。

### 2. 应用仓库 README

`README.md` 顶部原 0.5.6 长段落替换为独立仓库链接与作用说明，并注明 `browser-extension/` 为主仓库开发副本、发布以独立仓库为准。

### 3. 应用内「插件」入口

- 抽屉顺序：首页 → 下载库 → AI 设置 → 设置 → **插件**。
- `BrowserExtensionActivity`（代码构建布局，沿用现有字体/卡片样式）展示：作用说明、能力列表、四步使用方法、仓库卡片（`zhixiao113/AIQuizLite-browser-extension` + 链接 + 「打开 GitHub 仓库」按钮）、边界说明（不扫码直传、不读取手机学习数据）。
- 链接点击走 `ACTION_VIEW`；无浏览器时以 Toast 提示手动访问。
- 新增 `ic_extension` 矢量图标与 `drawer_plugins` 字符串；`MainActivity.setupNavigationDrawer` 增加第六个入口。
- 版本：`1.1.17` / `code 28`。

## 验证

- `:app:testDebugUnitTest`：238 项，0 失败，1 项可选私有夹具跳过。
- `:app:lintDebug`：0 errors / 158 warnings（既有基线 157，新增项为既有类别）。
- `:app:assembleDebug`：使用 `AIQUIZ_CI_UNSIGNED=true` 通过；本机 `local.properties` 中的签名口令失效，未生成可安装的签名包。
- GitHub 仓库页面返回 HTTP 200，README 与描述正常渲染；主分支推送成功。

## 发布（2026-11-23）

- 扩展仓库 Release `v0.5.7` 已发布，资产 `AIQuizLite-browser-extension-v0.5.7.zip` 86,006 字节，SHA-256 `4dbc6350b75a583f44f65a0cde950431726a660820800c19bdf812e19f650900`。
- 应用 `v1.1.17`/code28：标签打在 `a738568`；Quality gates 成功；保护发布运行 [45175438887](https://github.com/zhixiao113/AIQuizLite-AS/actions/runs/45175438887) 的 validate 与 release 两个作业均成功，未出现待审批停留。
- 公开 Release 于 2026-11-23T12:35:27Z 发布；APK 5,091,902 字节，SHA-256 `94f283d34737ae2b1d6577fb0aa8c12d44d2ace9d6ea21438329b607c16f2b4b`，`apksigner` 验证原证书 `697356...`，`aapt2` 验证 versionCode 28 / versionName 1.1.17 / targetSdk 35。
- `update-channel` 分支 `stable.json` 已指向 1.1.17/code28，哈希、大小、来源提交与 `publicationId 074bc79de1b444649bc0024b08932699` 一致。
- 本地签名口令仍失效，未产出本地签名包；安装版使用云端签名 APK。

## 待办

- 真机检查：抽屉「插件」入口、说明页滚动、按钮跳转浏览器、返回首页。
