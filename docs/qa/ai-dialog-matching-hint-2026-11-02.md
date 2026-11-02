# AI讲解按钮与连线一次提示

日期：2026-11-02。

用户要求AI讲解的“复制”和“重新生成”调换位置，“复制”改为关闭；用户确认连线题只提醒一次指的是“请点击另一侧项目的连接点”。

实现：共享讲解布局改为左侧重新生成、右侧关闭；练习QuizActivity和模考复盘AiExplanationDialogLauncher均绑定dismiss，不再从该按钮写剪贴板。顶部关闭继续可用。删除复制专用的临时displayed数组及相关传递，不修改生成、缓存或错误恢复规则。

MatchingHintStore在独立matching_onboarding_v1偏好文件保存connection_hint_shown，首次操作提示消费一次。所有MatchingQuestionView（普通、展开、不同题目及题库）使用同一记录；新建视图不会提前消费提示，后续点击仍更新连接起点和触感。当前连接移除、清空确认及非法连接反馈继续按原规则工作。

验证：新MatchingHintStoreTest 6项、既有ImageViewerHintStoreTest 6项，经JDK17+junit4.13.2+Android35接口直接编译运行，共12项通过。布局XML已解析并核对左右顺序、字符串、唯一ID及两处关闭绑定。git diff --check通过。

构建：Gradle wrapper官方下载首次超时；通过官方直连下载8.10.2，并按仓库固定SHA256校验。离线构建因本机缺少Android Gradle Plugin 8.8.1失败；在线完整构建成功：testDebugUnitTest/lintDebug/assembleDebug全部通过。216项单元测试，0失败、0错误、1项可选私有题库用例跳过（215通过）；Lint 0错误、113警告。调试无签名APK仅作构建验证，未发布。详见artifacts/ai-dialog-hint-2026-11-02/verification.json。

设备：ADB正常启动后无连接设备，emulator -list-avds为空，未进行手机触摸、视觉或辅助功能运行验收。没有发布新的APK，也未改动1.1.8/code19版本或原签名配置。

手机待核对：讲解页左侧重新生成、右侧关闭；关闭不复制且返回题目。练习和模考复盘各验证一次。连线提示首点出现，继续点、换题、展开/返回和重开APP均不再出现，连线仍可完成。


## 1.1.9 / code20 发布准备

用户随后明确要求发布更新、查看手机实测效果。远端main/checkpoint仍为a5fcd29，update-channel最高已用19、stable19、previous18，尚无v1.1.9标签，因此递增至1.1.9/code20。计划复用现有production受保护原签名云端流程，不重新构建已发布版本。仅提交本次Android源文件、新提示测试、版本及验收记录；浏览器扩展和其他本地文件独立保留。

发布与公开APK复验结果待补。ADB再次检查无设备；当前手机安装与实际触摸效果仍未完成，不能将云端发布等同于手机验收。
