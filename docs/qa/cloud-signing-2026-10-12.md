# 原证书云端签名与标签发布验收，2026-10-12

## 授权与边界

用户验收 1.1.5/code16 后，明确授权把原 app_sign.jks 及本机 DPAPI 密码配置到 GitHub production Environment Secrets。用户先选择自行审批，随后明确改为后续由代理代为批准，本次也按新授权执行。审批前核对目标运行、main 提交、版本与质量检查；不移除 required reviewers，不绕过审批关卡。

初始目标 1.1.6/code17，只递增版本号，不改 App 功能；首次发布检查失败后，修复并以 1.1.7/code18 完成云端发布，最终结果见文末。准备前核对公开最高已用 code16。签名成功与用户手机安装验收分别记录；本地备份脚本和历史 APK 保留，不接触学校虚拟机。

## 已完成配置

- GitHub 受保护 production 只允许 main 分支与 v* 标签，保留拥有者审批。
- 三项原签名 Secrets 用环境公钥 sealed-box 加密上传，Secret 名称元数据核验通过；JKS 原证书指纹再次核验，实际密码/Base64 私钥/Token 不记录。
- 本机 LibSodium 加密解密回环检查通过，44 项 Python 回归测试通过（新增 8 项发布 ref/version 守卫、5 项签名配置保护测试）；供应链/凭据门禁通过。
- 配置器在无审批、过宽分支规则、已有 Secret 或缺失输入时拒绝写入，不静默覆盖已有签名材料。
- release.yml 增加版本标签触发，先在不含 Secrets 的 validate job 检查 main 归属/精确标签版本/操作，再用输出的不可变 commit 签名。普通提交仅触发 CI，不能访问 production 凭据；发布与回滚仍共用串行并发组，不取消正在运行的事务。
- 云端临时私钥原证书校验、完整测试/lint、真实 APK 签名版本哈希大小验证、不可变 Release、stable/previous/最高使用计数/审计及失败保护回滚保留。
- 报告附件仅含测试/发布报告和公共版本元数据/profiles，不含私钥或 APK；APK 只通过已核验的 Release 分发。

## 初始验收步骤（执行前记录）

本地 Android 无签名 lint/test/assemble 检查已通过（169 项 Java 测试，0 failure/error，未提供私有题库样本的可选用例跳过 1 项；产物不作为安装包）。44 项 Python 回归复跑通过：沙箱临时目录权限不足导致的一次环境失败，在正常 Windows 临时目录权限下核验消除，未删除或禁用测试。推送 main 并确认云端普通 CI 通过后再推版本标签，核对无 Secret 的标签守卫。工作流仍需 production 审批后才可读取签名凭据，代理按用户新授权代为批准。

审批后验证云端原签名构建、公开下载与 stable 切换，下载真正的云端 APK 复核 SDK 信息后同步本地备用及仓库安装包，不另行重签同 code 的安装包。记录实际工作流 URL、SHA-256、大小与 previous 版本。网络/审批/签名失败不报告上线成功；公开候选 code 不复用。

首次执行前稳定版本为已验收的 code16；不能把配置成功或无签名测试通过写成云端正式发布已经完成。

## 首次云端执行与失败保护

- main 质量检查 [38229589597](https://github.com/zhixiao113/AIQuizLite-AS/actions/runs/38229589597) 通过，标签 v1.1.6 对应源提交 11c5329edc692ce53d29f68a0a10a516c39cbca5。
- 标签发布 [38229865838](https://github.com/zhixiao113/AIQuizLite-AS/actions/runs/38229865838) 的 validate 通过；代理按用户最新授权，通过原有 reviewer 权限批准 production（deployment 7010728639），未关闭审批保护。
- 原证书恢复、44 项 Python 回归、Release 构建/测试/lint 通过，云端 lint 0 错误/112 警告。发布步骤在 09:29:18–09:29:19 UTC 失败，只给出已脱敏的通用检查/API错误；临时私钥清理和报告上传通过。
- 只读核对没有 v1.1.6 Release/草稿，stable 仍是 1.1.5/code16；不能宣称上线或重签覆盖已公开 code。
- 增加手动、无 Secrets、无写权限的 SDK 诊断工作流，用固定哈希的已公开原签名 code16 包复现同 Ubuntu/Java17 的真实 APK 检查，不更改生产清单或签名材料。

## SDK 37 检查根因与修复

- 只读复现 [38251570851](https://github.com/zhixiao113/AIQuizLite-AS/actions/runs/38251570851) 确认同一已公开且哈希不变的 code16 包也被误拒；[38251777779](https://github.com/zhixiao113/AIQuizLite-AS/actions/runs/38251777779) 输出真实公共摘要标签 `V2 Signer: certificate SHA-256 digest:`，所含指纹正是固定原证书。排除密码/真实多签名/发布接口假设；不是 App 换了签名。
- 原 `update_server.inspect_apk` 只解析 `Signer #数字` 文本，而云端选择最新 Build Tools 37。改为先要求 `apksigner verify --print-certs-pem` 密码学验签成功，再从公共证书 DER 计算 SHA-256；不同证书拒绝、同一证书跨 scheme 重复合并，后续固定原证书校验不变，不信任展示文字的摘要。
- 新增 8 项真实调用边界回归：SDK37/旧标签、跨 scheme 同证书、不同证书、仅摘要无证书、坏 Base64、非证书公共密钥块、密码学验签失败。先复现失败后修复，52 项完整 Python 回归通过；本地实际 code16 APK 原签名复验通过。
- `verify_public_baseline.py` 固定公开 code16 的版本/大小/哈希，检查本机 SDK 与真实公开包；普通 CI 与云端私钥恢复之前均运行，避免只有无签名构建通过却漏检 SDK 变化。手动 SDK 诊断入口复用同一检查，无临时调试输出或私有材料。
- 保留失败 v1.1.6 标签且不移动，不复用其来源标识；修复发布目标递增为 1.1.7/code18，App 功能仍与已验收 1.1.5 相同；该修复阶段 stable 仍是 code16，后续结果如下。

## 1.1.7 云端发布完成与本地同步

- 源提交 f82809ebb7941d74bac5dbee102814940ef0567c 推送 main/checkpoint；[CI 38252387175](https://github.com/zhixiao113/AIQuizLite-AS/actions/runs/38252387175) 完成成功，包括在实际 SDK37 上复验固定公开原签名旧包。
- v1.1.7 标签对应该提交；[发布 38252694158](https://github.com/zhixiao113/AIQuizLite-AS/actions/runs/38252694158) 的标签守卫、production 审批（代理按用户授权批准 deployment 7014675495）、原证书恢复、完整质量检查、发布与临时私钥清理均成功。拥有者 reviewer 和 main/v* 限制保留。
- 52 项 Python 回归通过。169 项云端 Release Java 测试 0 failure/error，未提供私有题库样本的可选用例跳过 1 项；云端 lint 0 错误/112 警告。本机新版无签名质量检查亦通过，169/skip1，lint 0/104；无签名包未分发。
- 发布报告开始 2026-10-12 13:03:01 UTC，结束 13:03:16 UTC（香港时间 21:03:01–21:03:16）；上传回执、公开 APK 检查、stable 健康检查和 latest 展示同步通过。
- 实际公开 [原签名 APK](https://github.com/zhixiao113/AIQuizLite-AS/releases/download/v1.1.7/app-release.apk)：1.1.7/code18，包名 com.example.aiquizlite，4,981,397 字节；SHA-256 `294e4391a57f029880e348c8baeb099dbaab16c1eb06b4d64b8f2e790d825675`；证书指纹仍为固定 6973568484920238d35d94425c70601210ca2eaf7e728326785b66fb7e9576c8。
- 本机从公开地址重新下载该 APK，SDK 密码学验签/版本/包名/哈希/大小全部复验通过；公开 stable18/previous16/最高已用18 与 ledger 对齐，旧包保留。
- 下载的同一 APK 同步到 `artifacts/device-release-v18/outputs/apk/release/app-release.apk`、`app/release/app-release.apk`、`update-server/AIQuizLite-v18-294e4391a57f0298.apk`。报告附件元数据/profiles同步，默认本地 prepare 入口检查通过，三个副本哈希与公开包一致；没有重构建或重签，没有启动服务/隧道或接触学校 VM。

## 手机验收仍需用户完成

在已验收的 1.1.5 上进入「设置 → 检查更新」，应提示 1.1.7，下载后由系统确认覆盖安装，不卸载。确认设置版本号、题库/图片/学习进度保留，再重查应显示已是最新版；若手机 GitHub 网络不可达，可用上方同一公开 APK 一次性覆盖安装，但这不能代替应用内下载链路验收。代理未连接手机、未安装、未删除或恢复用户数据，不能把云端成功当作真机验收成功。
