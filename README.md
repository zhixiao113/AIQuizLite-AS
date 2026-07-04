# AIQuizLite

基于当前目录下 `AI升级考试题_排版整理版.docx/.pdf` 生成的轻量 Android 刷题应用。

功能：

- 日常刷题：随机刷完整题库，做错自动加入错题本。
- 错题本：错题连续答对 3 次后自动移出。
- 模拟考试：172 题随机顺序，选项顺序打乱，最后统一交卷。

本地题库生成：

```powershell
python .\tools\extract_questions.py
```

构建 Debug APK：

```powershell
.\gradlew.bat assembleDebug
```
