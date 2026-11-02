# AI讲解按钮、连线一次提示与1.1.9发布

日期：2026-11-02。

## 本次行为

用户要求AI讲解的“复制”和“重新生成”调换位置，“复制”改为关闭；用户明确确认一次提示指的是“请点击另一侧项目的连接点”。共享讲解布局改为左侧重新生成、右侧关闭；练习QuizActivity和模考复盘AiExplanationDialogLauncher均绑定dismiss。顶部关闭继续可用，生成、缓存与请求失败恢复规则保留。

MatchingHintStore使用独立matching_onboarding_v1偏好文件保存connection_hint_shown；普通与展开工作区、不同题目/题库及重开APP共用同一状态。仅首次点击连接点时提示，新建视图不提前消费提示；继续点选和连接触感正常执行。连接移除、清空确认、非法连接反馈保持原规则。

## 验证

提示持久化新增6项测试，直接JDK/JUnit执行新旧提示模块共12项通过。资源XML检查确认左重新生成、右关闭、唯一ID和两处关闭绑定。58项Python发布回归、凭据/供应链门禁及公开旧包签名基线通过。

云端干净Release构建：37个测试套件，185项测试，0失败、0错误、1项可选私有题库用例跳过（184通过）；包含新增MatchingHintStoreTest。Release Lint 0错误、114警告。此前本机216项统计混入12个已移除模块的历史测试报告，不能作为当前源码的测试总数；发布以云端干净构建为准，本机clean后复核同为37套件、185项（184通过/1跳过），Debug Lint 0错误/106警告，测试、Lint与无签名调试构建成功；未重建或重签云端发布包。

## GitHub发布

用户明确要求发布后，核对最高已用code19、stable19、previous18，源码递增1.1.9/code20，仅提交本次Android源文件/测试、版本及验收记录，其他本地浏览器扩展内容未提交。

- 源提交：3e9488345101afe11aa06b1856b2ae1869e3e1a3。
- main质量门禁成功：https://github.com/zhixiao113/AIQuizLite-AS/actions/runs/41419638210 。
- 按既有受保护workflow_dispatch从main触发发布并携带本次说明；精确提交、validate成功和production保护核对后，按用户既有委托代为审批，未修改审批规则。
- 原签名发布成功：https://github.com/zhixiao113/AIQuizLite-AS/actions/runs/41419982433 。
- 公开下载：https://github.com/zhixiao113/AIQuizLite-AS/releases/download/v1.1.9/app-release.apk 。
- 实际包名com.example.aiquizlite，1.1.9/code20，4,987,769字节。
- APK SHA256：f81216a3787a39fb80b47bb0cf728fa958a2cd62b5799f27ebd1eb5d38507034。
- 原签名SHA256：6973568484920238d35d94425c70601210ca2eaf7e728326785b66fb7e9576c8。
- update-channel提交：1d2a098f135556f4ea6144a2cf6cb3bac6fe39e7；stable20、previous19、highestCode20。公开清单初期命中旧CDN缓存，等待后已复验code20。

已重新下载公开APK并用Android SDK验签、读取实际版本、核对哈希/大小；保留同一份云端APK至artifacts/device-release-v20和app/release，云端metadata及baselineProfiles同步，update-server本地备用也使用同一份APK，没有重建或重签code20。详细结果：artifacts/ai-dialog-hint-2026-11-02/public-release-verification.json。

## 手机待验收

ADB无设备，emulator -list-avds为空，未安装、卸载或恢复用户手机。云端发布成功与手机实测分别记录。

1. 在手机APP点击检查更新，覆盖升级至1.1.9，确认原题库和进度保留。
2. 在练习和模考复盘分别打开AI讲解，确认左侧重新生成、右侧关闭，关闭后回到题目且不写剪贴板。
3. 连线首次点击出现操作提示，继续点击、换题、展开/返回和重开APP均不再出现提示；继续验证连接和提交正常。
