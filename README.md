# OPPO Pad Mini · External VNC Display

Android 平板外接 HDMI 显示器时，使用 Android Presentation API 在**外屏**绘制独立的 VNC 远程桌面，**主屏**保留连接控制界面及触控板。

这是面向 ColorOS 17 的最小可验证原型（MVP）：不需要 Root、不依赖强制桌面模式，也不修改系统显示配置。

## 适用场景

- 主屏：OPPO Pad Mini 原生桌面 / 连接面板 / 触控板
- HDMI 外屏：Mac / Linux / Windows 的 VNC 远程桌面
- 鼠标、键盘：转发至远程电脑，支持触控板与硬件键鼠
- 外屏使用自己的物理 Display，避免把 Pad Mini 的 3:2 镜像缩放到 16:9 造成的黑边（但远端 VNC 画面比例仍可能导致留边）

## 如何运行

1. 在 macOS 设置 -> 通用 -> 共享 -> 屏幕共享中开启 VNC 服务，并启用“VNC 显示程序可以使用密码控制屏幕”。使用专门的 VNC 密码，而不是 Apple ID 或 macOS 用户登录密码。
2. 平板与 Mac 位于同一局域网，或通过 ZeroTier 互通。**不要在公网直接开放 TCP 5900**。
3. 使用 Android Studio（JDK 17）打开本仓库，构建并安装 app，或下载 GitHub Actions 生成的 debug APK。
4. 将平板接入 HDMI 显示器，选择外部 Display，先按“测试外屏”。
5. **只有 HDMI 上出现 Presentation OK 测试文字**，才说明 ColorOS 在这台设备上允许独立输出。如果仍然只有镜像或 Presentation 失败，应用无法绕过固件的显示限制。
6. 输入 Mac 的 IP、端口 5900、VNC 专用密码，按“连接 VNC”。

## 技术结构

- Android Java 17 / Gradle / minSdk 26
- 外屏：DisplayManager + Presentation + Bitmap Canvas 绘制
- 内屏：连接管理、触控板、物理键鼠转发
- 协议：仓库自带的**开源纯 Java RFB 3.3/3.7/3.8 客户端最小实现**（RFB 规范 RFC 6143），无需二进制闭源 SDK。该代码是本项目独立实现，**没有直接嵌入 AVNC 或 bVNC**。

## 当前支持

- VNC 密码验证 (RFB SecurityType 2)
- Raw、CopyRect、DesktopSize 编码
- 触控板左键点击、滚轮、键盘按键和鼠标指针捕获
- 外屏缩放：适应、裁切铺满、拉伸铺满
- 不保存 VNC 密码；保存主机和端口

## 当前不支持 / 已知限制

- **不支持** Apple Remote Desktop 专有用户名/密码认证；需要在 Mac 启用 VNC 独立密码。
- **不支持** TLS / VeNCrypt / SSH 隧道。RFB 认证并不代表画面/输入通道加密，请只在可信局域网或 ZeroTier 等加密内网中使用。
- **暂不支持** Tight / ZRLE / H.264 压缩：Raw 对带宽敏感，高分辨率和高帧率下性能有限。这一版本以验证 ColorOS 外屏能力为先。
- VNC 服务端输出比例和外屏比例不同，适应模式仍可能出现黑边；可以切换裁切或拉伸，但这不是远程桌面分辨率自动协商。
- 不保证所有 ColorOS 17 设备都开放独立外屏 Display / Presentation。

## 开源参考

- Android [Presentation API](https://developer.android.com/reference/android/app/Presentation)
- [RFC 6143: Remote Framebuffer Protocol](https://www.rfc-editor.org/rfc/rfc6143)
- [AVNC](https://github.com/gujjwal00/avnc)（成熟客户端，可作为后续 LibVNCClient/TLS 接入参考）
- [DroidVM](https://github.com/Droid-VM/DroidVM)（开源项目中已存在 VNC + Presentation 多屏实现可供研究；本仓库未复制其 GPL 源码）

## Roadmap

1. ColorOS 17 / OPPO Pad Mini HDMI 实机验证与外屏适配
2. 集成成熟开源 LibVNCClient，实现 Tight、ZRLE、TLS 和更高效的渲染
3. 键鼠设备识别、键位映射、双向剪贴板
4. 重连、分辨率自动调整、可选远端虚拟显示器

## 许可证

MIT。第三方项目参考不代表其源码已被引入。
