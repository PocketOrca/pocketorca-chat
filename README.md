# PocketOrca-Chat

一个 Android 客户端，直连你自己的 Hermes Agent `api_server`（局域网或私有 VPN）。无中间服务、无 Firebase、无 Google Play Services。

## 这是什么

微信形态的聊天 App，每个"联系人"就是一个 Hermes 端点：地址 + 端口 + `API_SERVER_KEY`。主要解决的是类似微信的客户端不能添加多Agents，会被经常限流，还不能搜索聊天历史记录，所以才有了这个客户端，通过它可以与机器上的 agent 对话——包括工具调用、审批卡片和图片。

核心约束：**对 Hermes 服务端零改动。** 全部走原生 `api_server` HTTP API。Hermes 0.2x+ 开启 api_server 即可使用。

## 这不是什么

- 非官方产品，无关联
- 不是 Hermes WebUI / dashboard 的封装
- 不替代桌面端，是手机形态的遥控器
- 不是推送通知客户端（无 FCM、无厂商推送）

## 功能

- 多联系人，一端点一联系人，各自独立 key
- 每联系人一个会话，本地 SQLite 聊天记录，重启不丢
- 特意关闭流式：回复整条到达，微信式
- 审批卡片：危险命令弹卡可点（once / session / always / deny）
- 图片收发（服务端 `MEDIA:` 路径自动渲染，点图可存相册）
- 后台轮询可调间隔、前台服务、开机自启
- 设备端斜杠命令：/help /new /clear /stop /model /context /status /search
- 跨联系人历史搜索
- Keystore 加密存 key（AES/GCM，每联系人独立）
- 字体大小调节，跟随系统明暗

## 已知短板

- **后台延迟达不到微信级。** 无推送通道，靠轮询。Android 12+ Doze 会拉长间隔。前台服务 + setExactAndAllowWhileIdle 可压到 1-2 分钟，代价是耗电。这是设计的物理后果，不是 bug。
- **允许明文 HTTP**（usesCleartextTraffic="true"）。前提是链路已被 VPN 加密或在内网。见安全节。
- `api_server` 无模型目录接口，`/model` 接受任意 `provider/model` 字符串，填错会把会话锁死直到改对。
- 仅在 Android 13/14（三星 One UI）实测。minSdk 29 / targetSdk 36。
- 无 iOS 版，无计划。

## 安装

从 Releases 下载 APK 安装。已签名；覆盖旧版本保留数据。

首次启动添加联系人：
- **名称**：随意
- **地址**：跑 Hermes 的主机 IP，如 `192.168.1.100`（可省略 scheme，默认 http）
- **端口**：默认 `8642`
- **API Key**：那台机器的 `API_SERVER_KEY`

### 服务端配置（Hermes api_server 开启方法）

在跑 Hermes 的机器上执行：

```bash
# 1. 生成强随机 key 并配置
hermes config set API_SERVER_ENABLED true
hermes config set API_SERVER_KEY "$(openssl rand -hex 24)"   # 必须 ≥16 字符，短 key 会被静默拒绝
hermes config set API_SERVER_HOST 0.0.0.0
hermes config set API_SERVER_PORT 8642
hermes gateway restart

# 2. 验证
ss -tln | grep 8642
curl http://127.0.0.1:8642/v1/health

# 3. 查看你的 key（粘贴进 App 联系人表单）
grep API_SERVER_KEY ~/.hermes/.env
```

配置写入 `~/.hermes/.env`，必须 `hermes gateway restart` 才生效——只改配置不会自动重启任何东西。

**手机侧连接**：添加联系人填地址、端口 8642、第 3 步查出的 key。

## 安全 — 请务必读完

把 App 连到一个 Hermes 端点 = **这个 App 可以让那台机器执行命令**。审批 UI 是指尖和 shell 之间唯一的防线。

1. **绝不把 api_server 暴露到公网。** 不做端口转发、不做公网反向代理、不"就开一分钟"。只绑局域网或 VPN 接口（WireGuard、Tailscale 等你已信任的隧道）。
2. **把 API_SERVER_KEY 当作那台主机的 root。** 单一静态 token：无作用域、无过期、无按设备吊销。泄漏就改 `.env` 换新并重启。
3. **保持审批模式开启。** 服务端关审批，这个 App 就成了没有确认步骤的远程 shell。
4. 隧道内是明文 HTTP——这是刻意的取舍，可接受的唯一理由是隧道本身已加密。
5. 本地 key 用 Android Keystore 按联系人为单位加密，防的是普通文件读取和备份导出，防不了已 root 设备的 Keystore 绕过——本来也没打算防。

## 构建

无 Gradle 工程，构建是手写的 `aapt2` + `d8` + `apksigner` 链（快、无依赖解析）。需要 JDK 17+、Android build-tools 34+、API 36 的 android.jar：

```sh
export SDK_BUILD_TOOLS=$HOME/android/sdk/build-tools/36.0.0
export ANDROID_JAR=$HOME/android/sdk/platforms/android-36/android.jar
export KS=$HOME/my-release.keystore
export KS_PASS=...
export KEY_ALIAS=...

VC=43 VN=0.1.2 ./build.sh
```

脚本会校验 APK 内 `assets/app.js` 与源码哈希一致，不符即失败，杜绝静默过期构建。

## 布局

```
app/src/main/java/com/pocketorca/chat/
  MainActivity.java     WebView 宿主、JS 桥、Activity 生命周期
  PollScheduler.java    前台服务、闹钟驱动轮询、通知
  HermesAdapter.java    Hermes api_server 的 HTTP 客户端
  ChatDB.java           本地聊天记录（SQLite、WAL、按消息 id 去重）
  Store.java            联系人与设置
  CredentialVault.java  Keystore 加密 token 存储
  MediaSaver.java       收到的图片存入相册
  BootReceiver.java     开机重启轮询
app/src/main/assets/
  index.html, app.js    全部 UI——无框架、无构建步骤
```

UI 是单个 HTML + 单个 JS，跑在加固过的 WebView 里。当时选这个形态是因为偶尔需要直接在设备上改。

## 相关项目

如果你想要一个仍在维护的 Hermes 手机客户端，可以看
[rusty4444/hermes-android](https://github.com/rusty4444/hermes-android)（Flutter，支持 REST 和 gateway JSON-RPC 双传输）或
[Hy4ri/hermes-mobile](https://github.com/Hy4ri/hermes-mobile)。Nous Research 也有官方移动端内测。对多数人来说那些是更好的选择；这个项目存在的主要原因是作者想要多联系人和零服务端补丁。

## 许可

MIT。见 [LICENSE](LICENSE)。
