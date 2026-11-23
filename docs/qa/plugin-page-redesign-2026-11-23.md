# 应用内插件页面改版（2026-11-23）

范围是 Android 应用侧边导航「插件」入口打开的 BrowserExtensionActivity。

## 页面调整

- 移除右上角「完成」，顶部仅保留返回箭头和「浏览器插件」标题。
- 简介区保留应用蓝色与浅色背景，展示电脑端平台、插件作用和「获取浏览器插件」主入口；入口打开独立插件仓库的最新 Release 页面。
- 用功能摘要和四步指南说明安装、模型配置、采集核对、文件传到手机导入的流程。安装目录以发布包实际结构为准，提示加载含 manifest.json 的文件夹。
- 底部整行「项目与安装说明」打开原 GitHub 仓库。没有浏览器时继续提示手动访问。
- 原动态布局改为 XML；正文和按钮可随字体换行，内容滚动，标题栏固定。系统栏与屏幕缺口使用 WindowInsets，导航栏浅色背景与深色图标配对。

## 验证

- 隔离构建目录：artifacts/build-plugin-redesign。
- `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline` 通过：238项测试，0失败，1项可选私有夹具跳过；Lint 0错误、164警告。
- 两名独立审查代理复查通过。审查发现的导航栏配色、安装目录说明均已修正。
- 用已安装的 Android 35 系统镜像新建专用模拟器，运行一次性本地签名的调试APK；未连接或安装到用户手机，未使用正式签名。
- 实际通过应用抽屉进入插件页，检查360dp宽屏幕默认字号和200%字体：内容能换行、可滚动到指南和仓库入口；顶部没有「完成」按钮。
- 点击「获取浏览器插件」进入 Chrome；返回插件后点击左上箭头回到应用首页。AndroidRuntime 无崩溃输出。未将 Chrome 的首次启动界面视为外部网页加载成功。
- 截图和UI层次保存在 artifacts/plugin-page-preview/：default.png、bottom.png、large-font.png、browser.xml、return-after.xml。专用模拟器验证结束后已关闭。

仅完成本地源码改版与验证，没有提高应用版本、提交推送或发布新版APK。后续发布后需在用户手机查看实际布局、返回与浏览器跳转。

## 发布（2026-11-23）

- 版本 1.1.18 / code29，提交 `1d7d2b5`，标签 `v1.1.18`。
- 保护发布运行 [45198050024](https://github.com/zhixiao113/AIQuizLite-AS/actions/runs/45198050024) 成功（约 105 秒），未出现待审批停留。
- Release 于 2026-11-23T14:49:44Z 发布：APK 5,104,858 字节，SHA-256 `252928ccb80ba1050ee2420aad9e0d29c981d038cc65492023c617a16c948278`。
- `apksigner` 验证原证书 `697356...`；`aapt2` 验证 versionCode 29 / versionName 1.1.18 / targetSdk 35。
- `update-channel` 的 `stable.json` 已指向 29，大小、哈希与 `publicationId 55aca47e47854e15ae6483e4a7e74603` 一致；核验副本在 `artifacts/cloud-verified-v29`。
- 真机检查仍待用户执行：抽屉「插件」→ 新版页面滚动、「获取浏览器插件」跳转 Release 页、返回首页、应用内检查更新识别 1.1.18。
