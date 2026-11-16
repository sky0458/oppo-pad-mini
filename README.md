# OPPO Pad Mini · External VNC Display

Android 平板外接 HDMI 显示器时，使用 Android Presentation API 在**外屏**绘制独立的 VNC 远程桌面，**主屏**保留连接控制界面及触控板。

这是面向 ColorOS 17 的最小可验证原型（MVP）：不需要 Root、不依赖强制桌面模式，也不修改系统显示配置。

## 适用场景

- 主屏：OPPO Pad Mini 原生桌面 / 连接面板 / 触控板
- HDMI 外屏：Mac / Linux / Windows 的 VNC 远程桌面
- 鼠标、键盘：转发至远程电脑，支持触控板与硬件键鼠
- 外屏使用自己的物理 Display，避免把 Pad Mini 的 3:2 镜像缩放到 16:9 造成的黑边（但远端 VNC 画面比例仍可能导致留边）

## 第二阶段：不连接 VNC 的外接键鼠测试

本版本加入了 **“键鼠测试（不连接VNC）”** 按钮。选择 HDMI Display（实机可能是 `#9`，不要写死 ID），然后点击该按钮：

1. 外接显示器出现 **HDMI INPUT TEST**，中心有绿色应用虚拟光标。
2. 在平板触控板区域移动鼠标：外屏绿色光标应移动，`Motion events` 增加；鼠标仍停留在平板主屏是正常现象。
3. 点击 **“抓取鼠标”**。若 ColorOS 允许 Android Pointer Capture，状态变成 **Pointer capture: ACTIVE**。这时系统鼠标指针通常从平板主屏隐藏，硬件鼠标相对移动会驱动外屏虚拟光标。**光标并不是系统指针跨屏迁移**。
4. 在触控板区域点击、右键、滚轮，观察外屏 `Clicks`、`Last button`、`Scroll` 数字和事件记录。
5. 确保平板触控板获得焦点，使用外接实体键盘输入 `A`、`Enter`、`Ctrl` 等，观察外屏 `Last key` 与 `Keys`。
6. 通过平板触控点击 **“释放鼠标”** 或 **“退出键鼠测试”** 恢复正常鼠标操作。

若捕获失败，鼠标仍可在平板触控板区域内测试移动及点击；这与系统不允许全局捕获不同。**本模式只能证明应用可接收并在外屏可视化输入事件**，不能证明 ColorOS 允许直接把系统鼠标光标移到外接显示器，更不能证明 VNC 远程键鼠已成功。

## 如何运行

1. 在 macOS **系统设置 → 通用 → 共享 → 屏幕共享** 中开启共享，并允许当前 Mac 用户访问。默认可使用 Mac 本地账号认证；不必额外开启旧版 VNC 专用密码。
2. 平板与 Mac 位于同一局域网，或通过 ZeroTier 互通。**不要在公网直接开放 TCP 5900**。
3. 使用 Android Studio（JDK 17）打开本仓库，构建并安装 app，或下载 GitHub Actions 生成的 debug APK。
4. 将平板接入 HDMI 显示器，选择外部 Display，先按“测试外屏”。
5. **只有 HDMI 上出现 Presentation OK 测试文字**，才说明 ColorOS 在这台设备上允许独立输出。如果仍然只有镜像或 Presentation 失败，应用无法绕过固件的显示限制。
6. 输入 Mac IP、端口 5900，填写 **Mac 本地短用户名 + Mac 登录密码**，认证选“Mac 账号密码”或“认证：自动”，按“连接 VNC”。可在 Mac 终端用 `id -un` 确认短用户名（不是 Apple ID 邮箱）。如果已经在 macOS 单独设置了“VNC 显示程序可以使用密码控制屏幕”，可以改选“VNC 专用密码”，用户名留空。

## 密码认证及诊断（v0.2.0）

| 认证选择 | 服务端类型 | 填写方式 |
| --- | --- | --- |
| 认证：自动 | 优先 30（已填写用户名），否则 2 | Mac 用户名 + 登录密码，或只填 VNC 专用密码 |
| Mac 账号密码 | Apple DH / 30，AES-ECB | Mac 短用户名 + Mac 登录密码 |
| Mac 账号（CBC兼容） | Apple DH / 30，AES-CBC | Mac 短用户名 + Mac 登录密码；仅供与特定新系统兼容性测试 |
| VNC 专用密码 | 标准 VNC / 2，DES | macOS 单独配置的 VNC 密码；仅前 8 字节有效 |

如果仍无法认证，优先点击 **“查看日志” → “复制日志”**，或 **“导出日志”**，选择 Android 文件管理器中的保存位置，生成 `oppo-pad-mini-vnc-日期.log`。日志记录协议版本、服务器通告的认证类型（如 `[30, 33, 36, 35]`）、选择的认证方式、认证结果、屏幕大小、网络错误和连接操作，保留最近三个文件，总量约 1.5 MiB。**用户名和密码不写入日志，IPv4 地址最后一段自动隐藏**；导出前仍应检查日志是否包含其他私人信息。文件在应用私有目录内持久保存，不需要存储权限，也不会自动上传。

注意：Mac 账号认证（30）仅加密认证凭据，**不是全程端到端加密**；不要把 TCP 5900 暴露到公网，建议走 ZeroTier、WireGuard 等可信加密网络。部分新 macOS 版本可能只开放 33 / 35 / 36 等认证，仍需后续实现；CBC 兼容模式也不能保证适用于所有 Mac。外接显示器画面还需要在实机上确认。

## v0.3.0：连接成功但 HDMI 黑屏

如果日志中有 `SecurityResult=0`、`ServerInit desktop=...`，说明认证成功，接下来要检查帧数据和显示：

1. 重新连接 VNC；程序现在会在 `FramebufferUpdate` 中记录 Raw / CopyRect / DesktopSize 矩形数量。收到 DesktopSize 后会主动请求**非增量完整刷新**。
2. HDMI 画面左上角将显示绿色 `HDMI VNC | frame N | ...` 调试标记。如果看得到该标记，但下面全黑，说明外屏已经渲染了位图，重点检查服务端画面；如果没有标记，查看日志中的 `RENDER`。
3. 点击新按钮 **“远端重绘”**，手动强制请求完整画面。
4. 查看日志中 `FRAME Received` 的 `nonBlack=x/y`：若为 0，表示抽样的像素全部为黑色；若大于 0，说明 Mac 已发送非黑色像素，继续检查 HDMI 渲染链路。`RENDER First bitmap drawn` 表示 Android View 已执行位图绘制。
5. 程序会记录未捕获 Java 异常的堆栈至 `CRASH`，用于排查应用重启；OS 直接杀死进程（如严重内存不足）不保证能留下堆栈。

该调试标记仅为定位当前 OPPO Pad Mini + ColorOS 的输出问题，后续稳定版将提供隐藏开关。

## v0.4.0：修复闪退、Mac 控制权限与纯黑画面诊断

- **修复严重闪退**：此前 `sendPointer()`、`sendKey()`、`requestFullRefresh()` 从 Android UI 主线程直接操作 Socket，触发 `NetworkOnMainThreadException`。现在全部交给独立的单线程发送器，并合并高频鼠标移动，避免无限排队；点击、滚轮及按键消息保持顺序。
- **Mac 输入控制**：使用 Apple DH 认证（类型 30）时，发送 Apple `SetMode` 控制消息 `0a 00 00 01`。macOS 可能默认把第三方 VNC 客户端视为仅观察，拒绝鼠标和键盘消息。连接后第一次键盘输入建议稍等 2 秒。
- **纯黑 Raw 帧定位**：日志增加 `nonBlackWire`（远端 Raw 数据中非黑 RGB 像素总数）和 `nonZero4thByte`（第四字节统计），用于判断到底是服务端传来的黑屏，还是客户端错误解码。
- **外接屏排障**：点击新按钮 **“HDMI 彩条”**，外屏应该直接显示红、绿、蓝、白四条竖色带；此画面完全不依赖网络和 VNC。再次点击恢复 VNC 画面。

### 推荐排查顺序

1. VNC 连接成功后先点“HDMI 彩条”。若彩条不可见，即使日志显示 `RENDER First bitmap drawn`，也应优先检查 ColorOS 的外屏 Presention 是否真的处于可见层，以及 HDMI 设备配置。
2. 若彩条可见但远程画面仍黑，检查导出日志里 `nonBlackWire`。若数值为 `0`，则 Mac 通过 RFB 实际发送了全黑 RGB 数据。可先确认 Mac 本地屏幕已解锁且亮屏，并使用另一款 VNC 客户端交叉验证服务端。
3. 若 `nonBlackWire` 大于 `0` 且 HDMI 彩条可见，重点检查 Android 的像素格式、渲染和缩放。请把日志发回继续定位。
4. 若仍闪退，看 `[CRASH]`；本次已消除已知的 `NetworkOnMainThreadException` 路径。

不要为排查而直接强制结束 macOS `WindowServer`；那会中断当前图形会话并可能丢失未保存的工作。

## 技术结构

- Android Java 17 / Gradle / minSdk 26
- 外屏：DisplayManager + Presentation + Bitmap Canvas 绘制
- 内屏：连接管理、触控板、物理键鼠转发
- 协议：仓库自带的**开源纯 Java RFB 3.3/3.7/3.8 客户端最小实现**（RFB 规范 RFC 6143），无需二进制闭源 SDK。该代码是本项目独立实现，**没有直接嵌入 AVNC 或 bVNC**。

## 当前支持

- VNC 密码验证 (RFB SecurityType 2) 和 Mac 本地账号密码认证 (RFB SecurityType 30)
- 本地日志自动保存（大小上限、轮转），查看、复制、导出 `.log` 文件
- Raw、CopyRect、DesktopSize 编码
- 触控板左键点击、滚轮、键盘按键和鼠标指针捕获
- 外屏缩放：适应、裁切铺满、拉伸铺满
- 不保存任何密码；保存主机和端口；日志不记录用户名/密码

## 当前不支持 / 已知限制

- **不支持** Apple Remote Desktop 其他认证类型 33 / 35 / 36（RSA-SRP / Kerberos / SRP）；仅实现 30（DH），需 Mac 实机验证。
- **不支持** TLS / VeNCrypt / SSH 隧道。RFB 认证并不代表画面/输入通道加密，请只在可信局域网或 ZeroTier 等加密内网中使用。
- **暂不支持** Tight / ZRLE / H.264 压缩：Raw 对带宽敏感，高分辨率和高帧率下性能有限。这一版本以验证 ColorOS 外屏能力为先。
- VNC 服务端输出比例和外屏比例不同，适应模式仍可能出现黑边；可以切换裁切或拉伸，但这不是远程桌面分辨率自动协商。
- 不保证所有 ColorOS 17 设备都开放独立外屏 Display / Presentation。

## 开源参考

- Android [Presentation API](https://developer.android.com/reference/android/app/Presentation)
- [RFC 6143: Remote Framebuffer Protocol](https://www.rfc-editor.org/rfc/rfc6143)
- [RFB 扩展规范：Diffie-Hellman Authentication (30)](https://github.com/rfbproto/rfbproto/blob/master/rfbproto.rst)
- [AVNC](https://github.com/gujjwal00/avnc)（成熟客户端，可作为后续 LibVNCClient/TLS 接入参考）
- [DroidVM](https://github.com/Droid-VM/DroidVM)（开源项目中已存在 VNC + Presentation 多屏实现可供研究；本仓库未复制其 GPL 源码）

## Roadmap

1. ColorOS 17 / OPPO Pad Mini HDMI 实机验证与外屏适配
2. 集成成熟开源 LibVNCClient，实现 Tight、ZRLE、TLS 和更高效的渲染
3. 键鼠设备识别、键位映射、双向剪贴板
4. 重连、分辨率自动调整、可选远端虚拟显示器

## 许可证

MIT。第三方项目参考不代表其源码已被引入。
