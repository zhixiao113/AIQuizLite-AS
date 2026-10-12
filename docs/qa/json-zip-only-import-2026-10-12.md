# 下线资料转题，统一 JSON/ZIP 导入（2026-10-12）

## 用户决定与实现范围

资料转题耗时长，用户明确要求取消应用内 TXT/PDF/DOCX 转题，改用外部制作的已有 JSON/ZIP。首页改版仍暂停；本轮只调整题库导入，不上传云端签名凭据或改变学校服务器。

- 总题库「＋ 资料」改为「＋ 导入题库」，进入已有 JSON/ZIP 导入页；另一入口明确为「下载库导入」，避免重复的 ZIP 文件选择弹窗。
- JSON 本地读取或粘贴后可检查再保存；ZIP 直接验证并导入题库与图片。成功结果统一回传题库 ID、自动选中；失败或取消不改变原选择。
- 微信/文件管理器通过同一个本地导入入口。明确的 TXT/PDF/DOC/DOCX 或其他非题库扩展名拒绝，即使 MIME 被误报成 JSON/ZIP；取消 PDF/Word 的系统打开/分享声明。
- 保留 JSON 被误报为 text/plain、ZIP 被误报为 octet-stream 的兼容；提供者无扩展名且只有泛型 MIME 时在本地检查 PK/JSON 并走现有内容校验，不回退到 AI。
- 已有五种题型、题图/提交后解析图、旧 ZIP 格式、安全限额、重复 ID 校验及导出/备份恢复沿用原实现。
- 教程改为已有 JSON/ZIP、微信分享与粘贴题库 JSON；继续提供给外部工具使用的格式提示词，没有应用内 AI 转换调用。AI 讲解与单题重生成不在取消范围内。

移除资料工作台及专用分析/提取/规划/生成/后台任务/草稿/DOCX 媒体模块，移除 PDFBox 依赖和相应 12 个专用测试文件。题库导入共用的旧 MaterialQuestionRules 题型兼容规则及其测试保留，不改变旧题库的解析。源码可从旧 Git 历史恢复；未删除手机上的题库、图片、学习记录、已生成题库或旧草稿文件。

## 本地验证

- 169 项 Release Java 测试通过，0 失败、0 错误、0 跳过。此前 186 项包含已取消模块的 31 项测试，删除专用测试并新增 14 项导入/兼容回归后为 169 项，不把测试数降低误称为未运行旧功能的成功。
- ExternalImportRulesTest 的 15 项用例覆盖：JSON/ZIP、大小写/错误 MIME、共享 JSON/Markdown/BOM、普通资料拒绝、无名泛型 URI、本地嗅探以及文件名保留。
- ExistingQuestionBankImportTest 验证五种题型、旧 material_q ID/来源/图片引用保留、外部 Markdown JSON、普通文字拒绝；本机实库 ZIP 解包与模型解析得到 101 题、13 道连线题。
- 实库路径仅通过本机 AIQUIZ_IMPORT_FIXTURE 环境变量传入，原题库未提交公开仓库。云端没有私人题库时仅跳过该可选实库用例，其余导入回归照常执行。
- 31 项 Python 发布、回滚和本地入口测试全部通过；Release 构建成功；lint 0 错误、105 警告。凭据和依赖固定引用门禁、diff 检查通过。
- 原证书由构建器与 Android SDK 再次核验，已同步仓库 APK、输出版本元数据及 baseline profiles；默认本地 prepare-only 入口实测自动选中新版，不启动服务器/隧道。

## 构建产物

- 1.1.5 / versionCode16，先核对云端最高 code15 再提升源码两项版本。
- 包名 com.example.aiquizlite，原证书 SHA-256：6973568484920238d35d94425c70601210ca2eaf7e728326785b66fb7e9576c8。
- APK：`artifacts/device-release-v16/outputs/apk/release/app-release.apk`，同步至 `app/release/app-release.apk`。
- APK 大小：5,162,262 字节（之前 code15 为 13,660,564 字节）。
- APK SHA-256：629a6048bf5492e0cb444fe4cee92c07df0584157369ff9d5f9772c6002be618。
- 本地更新文件：`update-server/AIQuizLite-v16-629a6048bf5492e0.apk`、`version.json`；旧包保留。

## 在线发布与验收边界

源码提交 `6bb2321f674549b8fe755aac9e13bfdda89e387a` 已正常推送 main 和既有工作分支；推送触发的 [Quality gates 38222110175](https://github.com/zhixiao113/AIQuizLite-AS/actions/runs/38222110175) 成功。

本机原证书签名并经受控工具发布，报告 `artifacts/github-release/report.json` 本次运行始于 2026-10-12T08:15:31Z，结束于 08:17:40Z，outcome=success；APK 回执/公开下载、健康检查与 stable 切换均验证通过。清单分支提交 `8a3b8ba88a2302330b4d2607137f30a3d8c63efa`，canonical stable=1.1.5/code16/SHA629a6048…，previous=1.1.4/code15；历史 Release 保留，latest 只在 stable 验证后更新。

[1.1.5 正式安装包](https://github.com/zhixiao113/AIQuizLite-AS/releases/download/v1.1.5/app-release.apk) 可通过当前 1.1.4 的检查更新获取。code16 已公开，不得重建后覆盖或复用；后续 App 修改必须使用更高版本号。仍未上传云端签名 Secrets，不把此次本机签名发布描述为云端自动签名完成。

1.1.4 用户已报告验收通过，此结论不自动覆盖 1.1.5。本次只读 ADB 检查无法连接本机 daemon，没有执行任何手机安装或数据操作。

用户更新后待确认：

1. 从 1.1.4 在线更新至 1.1.5，已导入/以前生成的题库、图片、错题、收藏、进度仍在，AI 讲解/单题重生成可用。
2. 「＋ 导入题库」选择 JSON（包括中文名/大小写扩展名）、预览、完成或取消；确认保存后自动选中，失败不影响已有题库。
3. 本地 ZIP、下载库 ZIP、微信/文件管理器 JSON 与 ZIP 均可导入，检查 101 题/13 连线及提交后解析图时机。
4. 无 AI 配置、断网状态下可导入本地题库（系统云盘提供者本身可能需要网络，不等同于 App 调用 AI）。
5. TXT/PDF/Word 和非 JSON 的普通分享文字不会进入 AI 工作台；提供者误报 MIME 时仍拒绝已知资料扩展名。
6. 导入过程旋转/返回再进入、读 URI 授权失败、坏 JSON、图片缺失/坏 ZIP、重复 ID 的错误反馈与数据保留。

不要为测试主动卸载用户应用、清空学习数据或覆盖原题库。
