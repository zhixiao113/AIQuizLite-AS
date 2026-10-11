# 题序跳转（2026-09-15）

## 已确认设计

用户选择「C 的布局，A 的显示」，并明确为：从底部弹出，内容使用 A 的题号网格和「上一组 / 下一组」。不包含输入题号框。

## 实现

- 点击答题页「第 n / N 题」打开圆角底部面板，每页 20 题、5 列，默认定位当前题所在组。
- 当前题蓝底白字；首尾组禁用对应翻页按钮；不足 20 题保留空位稳定面板高度。
- 点击题号后关闭面板并跳转；关闭按钮、遮罩、系统返回和下滑沿用 Material BottomSheet 行为。
- 打开面板取消待执行的自动下一题；跳转保留选择题、填空题草稿及模拟考试选择。
- 练习支持跳到未答题。答题计数按实际提交递增，避免跳到第 100 题就累计 100 题。
- 到达末题仍有漏答时，返回首个未答题，不提前完成本轮。
- 现有用户对顶部信息卡间距的修改保留。

## 验证

- 迁移后工作目录：`E:\projects\project-bs\AIQuizLite-AS`。
- `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug --offline` 通过；112 项单元测试（包含 5 项末题/漏答导航回归）通过。
- 最终 Lint：0 errors / 73 warnings，本次新增控件的兼容性、布局加载告警已处理。
- Android 35 独立模拟器 11 项交互检查通过：首组禁用上一组、下一组范围、跳到未答题并关闭、重开定位并高亮当前题、日常草稿保留、末组禁用下一组、末组范围、末组只显示 15 个有效题号、返回键关闭不跳题、模拟考试选择保留、遮罩关闭。
- 检查脚本/记录：`artifacts/question-jump-designs/smoke.ps1`、`smoke-results.txt`；实际截图：`implemented-sheet.png` 和 `implemented-last-page.png`。
- 尚未完成真机验证。

## 待验证场景

- 大字体、横屏、小屏、整场不足 20 题、填空草稿、下滑关闭和自动下一题取消。

## 2026-09-16 Release 缓存锁诊断

- 用户报告 Android Studio 的 `:app:assembleRelease` 在构建初始化阶段报 `Cannot lock file hash cache ... as it has already been locked by this process`。
- 旧路径 `E:\project-bs\AIQuizLite-AS` 是指向 `E:\projects\project-bs\AIQuizLite-AS` 的 junction；Android Studio recentProjects 仍将旧路径标为打开状态。
- Gradle `daemon-3988.out.log` 记录 12:10:59 从旧路径启动的相同错误；新路径命令行 Release 构建成功，旧路径命令行和同版本 Tooling API 探针随后也成功。未稳定重现，不能将路径联接断定为唯一根因。
- 不删除锁文件或题库。确认 Gradle 进程空闲后重启 daemon，并重新验证规范路径的 Release 构建。Android Studio 应关闭旧路径项目，打开新路径并同步，统一后续项目入口。
- Release 安装包：`app/build/outputs/apk/release/app-release.apk`；诊断脚本和输出位于 `artifacts/gradle-path-diagnosis/`。
