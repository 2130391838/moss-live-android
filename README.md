# MOSS Live · Android 客户端

一个**独立运行在手机上**的 MOSS Live 实时语音客户端：不经过电脑、不需要本地中继，手机直接连 MOSS 云端，跟模型像打电话一样边说边听。

> 非官方项目。MOSS 模型与 API 归上海模思智能科技有限公司所有，使用前请遵守其服务条款，注意按连接时长计费。

## 它做了什么

| 能力 | 说明 |
| --- | --- |
| 全双工对话 | 上行音频持续发送，不等对方说完；模型说话时你随时可以插话 |
| 自然打断 | 端上做能量 VAD + 服务端 VAD 复核，开口就能打断，不用先说「等一下」 |
| 回声守护 | 播放期间本地判定为回声的帧上行填零，避免自己被自己的音箱打断 |
| 实时字幕 | 双向流式字幕，同一句不重复 |
| 双角色 | Luna / Leo，内置中文人设提示词 |
| 音量增益 | 1×–6× 软件增益 + tanh 软限幅，记住上次设置 |
| 记住 API Key | 存在 SharedPreferences，下次直接点「开始通话」 |

## 协议要点

不是 OpenAI Realtime 兼容协议，是 MOSS 自己的 action 协议。

```
wss://api.mosi.cn/v1/realtime?model=moss-live-1.0&user=android-<随机十六进制>
Authorization: Bearer <API Key>
```

握手顺序：

1. `{"action":"attach_session"}` → 等 `session_attached`
2. `{"action":"session.update","session":{"persona":{...},"instructions":"..."}}` → 等 `session.updated`（有 `error` 即失败）
3. `{"action":"media.mode","binary_protocol":"tagged-v1"}`
4. `{"action":"mic_state","enabled":true}` → 可以开始送音频

音频格式：

- **上行**：16 kHz 单声道 PCM16，每 20 ms 一帧 = 320 采样 = 640 字节，帧首加 1 字节 tag `0x00`，共 641 字节/帧
- **下行**：48 kHz 单声道 PCM16 原始字节，**无**前缀；按 `response.audio.context` 里的 `chunk_id` 与音频块配对

回执（不回上游会卡住）：

- 每块播完 → `playback.chunk.completed`
- 一轮播完 → `playback.completed`
- 被打断/暂停 → `playback.stopped`（带 `run_id`、`turn_id`、`played_audio_ms`）

调用者必须先开口，服务端才会开始回答；说完之后要继续按 20 ms 送静音帧，否则服务端判断不出「说完了」。

## 构建

不需要 Gradle、不需要 Android Studio，`aapt2 + javac + d8 + apksigner` 直接出 APK。

依赖：

- JDK 17
- Android SDK：`platforms;android-34`、`build-tools;34.0.0`

```sh
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./build.sh          # 产物：MOSS-Live.apk
```

`build.sh` 首次运行会自动生成自签密钥 `moss.keystore`（alias `moss`，密码 `mosslive`）。这个密钥只够自己装apk用，**不要用于任何正式发布**；要覆盖安装就得一直用同一个 keystore，注意备份。

## 安装

1. 把 `MOSS-Live.apk` 传到手机（数据线、微信文件传输助手、网盘都行）
2. 点击安装，系统提示「未知来源」时允许
3. 打开 App，填入在 [platform.moss.cn](https://platform.moss.cn/) 创建的 API Key
4. 选角色 → 开始通话，第一次会请求麦克风权限

## 兼容性

- minSdk 26（Android 8.0）/ targetSdk 34
- 采集用 `MediaRecorder.AudioSource.VOICE_COMMUNICATION`，并请求系统 `AcousticEchoCanceler`
- 播放用 `AudioAttributes.USAGE_VOICE_COMMUNICATION`，路由走通话通道

手机一体化机身 + SoC 硬件 AEC 的回声消除通常比「外接麦克风 + 独立音箱」好得多；如果仍然觉得自己打断自己，插耳机是 100% 有效的办法。

## 第三方组件

`libs/` 里两个 jar 随仓库提供，省去联网拉依赖：

- [OkHttp 3.14.9](https://square.github.io/okhttp/) — Apache License 2.0
- [Okio 1.17.2](https://github.com/square/okio) — Apache License 2.0

## 目录

```
AndroidManifest.xml      清单：INTERNET / RECORD_AUDIO / MODIFY_AUDIO_SETTINGS / WAKE_LOCK
build.sh                 一键打包（aapt2 → javac → d8 → zipalign → apksigner）
libs/                    OkHttp + Okio
res/                     图标与主题（深色底 + 橙色主色）
src/cn/mosslocal/live/
  MossClient.java        WebSocket 协议、采集、播放、打断、回声守护、字幕
  MainActivity.java      设置页 + 通话页（纯代码 UI）
test/ProtoTest.java      桌面 JVM 协议一致性验收（直连真实上游跑一遍握手与音频）
```

## 说明

本仓库只包含这个安卓客户端本身的代码。桌面端（本地 Python 中继 + 浏览器界面）来自社区的另一个复刻项目，不在本仓库范围内。
