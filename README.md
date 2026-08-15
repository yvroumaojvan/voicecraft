# 妙音工坊（VoiceCraft）

一款完全开源的 Android 文字转语音应用：输入文字 → 选择音色 → 一键合成真人级人声并试听、保存。

基于 **微软 Edge TTS（Neural）** 引擎，内置 **16 种中文真人级音色**（晓晓 / 云希 / 云扬 / 晓伊…），完全免费、无需注册、无需 API Key。

## ✨ 功能

- 📝 **文字转语音**：输入文本，标点自动停顿（逗号短停、句号长停、问号叹号带语气）
- 🎙 **16 种中文音色**：温暖女声、阳光少年、沉稳男声、专业播音、孩童音色…任你挑
- 🐟 **Fish Audio 引擎**（可选）：填入 API Key + 音色 ID，用免费模型 `s2.1-pro-free`（$0）合成平台音色（如莫提斯），音色与源站完全一致
- 🎚 **语速调节**：-50% ~ +50%
- ▶️ **试听播放**：合成后立即播放
- 💾 **保存到下载**：一键导出 MP3（`下载/妙音工坊/` 目录）
- 📜 **历史记录**：最近 30 条合成记录，点击重播、长按删除
- 🔌 **本地离线兜底**：断网时可用手机自带 TTS 引擎离线朗读

## 🚀 在线编译 APK（无需本地 Android 环境）

本项目配置了 GitHub Actions，每次推送到 `main` 分支都会**自动编译 APK**：

1. 打开本仓库的 **Actions** 页面
2. 选择最新一次运行的 **Build APK** 工作流
3. 在底部 Artifacts 区域下载 `voicecraft-apk`
4. 解压安装到 Android 手机即可

> 也可在 Actions 页面点 **Run workflow** 手动触发编译。

## 🛠 本地编译（Termux 或任意 Linux）

```bash
cd app
# 需要: android.jar (API 35) + aapt/d8/zipalign/apksigner + JDK17+
ANDROID_JAR=/path/to/android.jar bash build.sh
# 产物: app/out/voicecraft.apk
```

## 📂 项目结构

```
app/
├── AndroidManifest.xml        # 应用清单
├── build.sh                   # 一键构建脚本（零 Gradle）
├── src/com/yvroumaojvan/voicecraft/
│   ├── MainActivity.java      # 主界面（合成/播放/保存/历史/本地兜底）
│   ├── EdgeTTS.java           # Edge TTS 协议核心
│   ├── WssClient.java         # 自研零依赖 WebSocket 客户端
│   └── Voices.java            # 16 种中文音色列表
└── res/                       # 深色主题 UI 资源
```

## ⚙️ 技术亮点

- **零依赖**：不依赖 Gradle / AndroidX / OkHttp / Kotlin，纯 Android framework + 自研 WebSocket 客户端
- **协议复刻**：Edge TTS 的 Sec-MS-GEC 令牌算法与 WebSocket 消息流由 Python edge-tts 库精确移植
- **标点停顿**：利用微软语音模型原生能力，逗号/句号/问号/感叹号自动产生自然停顿

## 📄 许可

MIT License（代码部分）。合成音频由微软 Edge 服务生成，请遵守其服务条款。
