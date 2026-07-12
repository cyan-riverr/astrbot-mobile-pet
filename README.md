# AstrBot Mobile Pet 🐇

一个运行在 Android 手机上的 AI 桌宠系统，通过 WebSocket 与 [AstrBot](https://github.com/Soulter/AstrBot) 实时通信，让 AI 以桌面悬浮宠物的形式陪伴你。

> 💡 本项目的灵感来源于 [ai-live2d-body](https://github.com/zziying/ai-live2d-body)，感谢该项目提供的思路。

> ⚠️ 这个项目做得比较简陋，UI 和功能都还很粗糙。所以把所有代码全部开源了，欢迎自由二改、魔改、拿去当素材，随意折腾。

## 原理

```
┌──────────────┐    WebSocket     ┌──────────────────┐     AstrBot      ┌─────────┐
│  Android App │ ◄──────────────► │  AstrBot Plugin  │ ◄──────────────► │  LLM AI │
│  (桌面悬浮窗) │                  │  (消息桥接+控制)  │                  │         │
└──────────────┘                  └──────────────────┘                  └─────────┘
```

### Android App（桌面悬浮窗）

- 以 `Service + WindowManager` 实现桌面悬浮宠物，支持拖拽、自动漫游、点击交互
- 通过 WebSocket 连接 AstrBot 插件，实时收发消息
- 支持自定义素材（GIF/静态图），为不同状态（idle/touch/hug/feed/sleep 等）设置不同形象
- 内置屏幕共享功能：通过 MediaProjection API 截取当前屏幕，经 WebSocket 发送给 AI
- 内置聊天输入框，可直接在桌宠气泡中与 AI 对话
- 菜单面板提供快捷交互（戳戳/抱抱/摸摸/投喂/叫你/聊天）

### AstrBot Plugin（消息桥接）

- 作为 AstrBot 插件运行，提供 WebSocket 服务端
- 将桌宠消息注入 AstrBot 消息处理管线，AI 的回复自动路由回桌宠气泡
- 解析 AI 回复中的控制标签 `[pet:...]`，转换为桌宠指令（状态切换/气泡文字/走路/屏幕共享请求）
- 支持的控制标签：
  - `[pet:状态]` — 切换桌宠状态（idle/touch/hug/feed/bath/sleep/working/drag/walk）
  - `[pet:bubble:文字]` — 显示气泡文字
  - `[pet:bubble:文字 state=状态]` — 显示气泡并切换状态
  - `[pet:walk dx=X dy=Y duration=毫秒]` — 控制桌宠移动
  - `[pet:walk dx=X dy=Y duration=毫秒 text=文字]` — 边走边说
  - `[pet:request action=screen_share text=文字]` — 请求用户共享屏幕

## 项目结构

```
├── main.py               # AstrBot 插件主文件
├── metadata.yaml         # 插件元数据
├── requirements.txt      # 插件 Python 依赖
├── _conf_schema.json     # 插件配置模板
├── android-app/          # Android 桌宠 App 源码（Android Studio 项目）
├── release/              # 预编译的 APK
│   └── astrbot-mobile-pet.apk
└── README.md
```

> 仓库根目录本身就是 AstrBot 插件，可以直接通过 GitHub 链接安装。

## 使用方法

### 1. 安装 AstrBot 插件

在 AstrBot 中通过 GitHub 链接直接安装本仓库，或者手动将仓库克隆到 `data/plugins/astrbot_plugin_mobile_pet/` 下。

在插件配置中设置：
- `ws_port`：WebSocket 端口（默认 1016）
- `target_unified_msg_origin`：目标会话标识（格式：`platform:message_type:session_id`）
- `sender_nickname`：桌宠消息的发送者昵称

### 2. 安装 Android App

从 `release/` 目录下载 APK 安装，或自行用 Android Studio 编译 `android-app/` 源码。

打开 App 后：
1. 输入 AstrBot 服务器的 WebSocket 地址（如 `ws://192.168.1.100:1016/pet`）
2. 点击"启动桌宠"
3. 授予悬浮窗权限
4. 可选：在设置中为各状态配置自定义素材图片

### 3. AI Prompt 配置

在 AI 的 system prompt 中加入桌宠控制标签的说明，让 AI 知道可以使用 `[pet:...]` 标签来控制桌宠。

## 自定义素材

App 支持为以下状态设置自定义素材（支持 GIF 动图和静态图片）：

| 状态 | 说明 |
|------|------|
| idle | 待机 |
| touch | 被摸/戳 |
| hug | 抱抱 |
| feed | 投喂 |
| bath | 洗澡 |
| sleep | 睡觉 |
| working | 工作中 |
| walk | 走路 |
| drag | 被拖拽 |

未设置自定义素材的状态会自动回落到 idle 素材，idle 也未设置则显示默认 emoji 表情。

## 技术要点

- **WebSocket 通信**：App 与插件通过 WebSocket 保持长连接，支持自动重连
- **悬浮窗管理**：使用 `TYPE_APPLICATION_OVERLAY` 实现全局悬浮，支持拖拽定位和边缘吸附
- **屏幕共享**：通过 Android MediaProjection API 截屏，Base64 编码后经 WebSocket 传输
- **消息注入**：插件将桌宠消息包装为 AstrBot 标准事件，复用 AI 对话管线
- **状态机**：桌宠有完整的状态管理，支持状态切换动画和自动回归 idle

## 依赖

- **Android App**：Android 8.0+（API 26+），OkHttp 4.x
- **AstrBot Plugin**：AstrBot ≥ 4.26.3，Python 3.9+，websockets 库

> ⚠️ 鸿蒙系统可能无法使用 bot 主动请求屏幕共享功能。

## Credits

- 灵感来源：[ai-live2d-body](https://github.com/zziying/ai-live2d-body)
- AI 框架：[AstrBot](https://github.com/Soulter/AstrBot)
- 开发辅助：Claude (Anthropic) & GPT (OpenAI)

## License

MIT
