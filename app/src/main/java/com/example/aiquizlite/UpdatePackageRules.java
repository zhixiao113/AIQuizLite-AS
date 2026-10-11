package com.example.aiquizlite;

import java.util.Set;

/** Compatibility gate shared by the downloader and its regression tests. */
final class UpdatePackageRules {
    private UpdatePackageRules() { }

    static String validationError(String installedPackage, long installedVersion,
                                  String apkPackage, long apkVersion, String apkVersionName,
                                  long advertisedVersion, String advertisedName,
                                  Set<String> installedSigners, Set<String> apkSigners) {
        if (apkPackage == null || !apkPackage.equals(installedPackage)) {
            return "更新包不属于当前应用，请联系开发者检查更新包。";
        }
        if (installedSigners == null || installedSigners.isEmpty()
                || apkSigners == null || apkSigners.isEmpty()) {
            return "无法验证更新包签名，已停止安装，请重新下载或联系开发者。";
        }
        if (!installedSigners.equals(apkSigners)) {
            return "更新包与当前应用签名不同，无法直接覆盖安装。请联系开发者提供同签名更新包。"
                    + "\n请勿直接卸载，以免丢失题库和学习记录；如需重装，请先在“备份与数据”中导出完整备份。";
        }
        if (apkVersion != advertisedVersion || apkVersionName == null
                || !apkVersionName.equals(advertisedName)) {
            return "更新包实际版本与服务器信息不一致，请联系开发者重新发布更新包。";
        }
        if (apkVersion <= installedVersion) {
            return "下载的更新包不是新版本，无需安装。";
        }
        return null;
    }
}
