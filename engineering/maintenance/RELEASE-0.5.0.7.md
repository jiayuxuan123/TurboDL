# TurboDL 0.5.0.7

本版重写下载核心。C++20 接管传输、调度和断点，Windows 通过原生 CLI 使用同一核心，Android 通过 JNI 接入。Kotlin/JVM API、插件运行时和旧断点继续由兼容层承载。

## 代码替换范围

- 新增 C++20 共用核心，负责任务生命周期、网络请求、分片调度、断点、写盘、校验和诊断。
- Android 通过版本化 C ABI 和 JNI 适配层接入；JNI 传递任务快照和批量事件，下载字节不经过逐块跨语言调用。
- 原 Kotlin `turbodl-core` 的公开 API、事件和插件运行时继续保留。旧调用方仍按原构造函数提交任务，新核心作为宿主可选的传输后端运行。
- 缺少原生库的构建和旧插件任务可使用 JVM 兼容路径。任务提交时固定执行后端和断点格式。

## 原生依赖

- HTTP/1.1、HTTP/2、HTTP/3、HTTPS、FTP 和 SFTP 共用 libcurl multi 事件循环。HTTP/2 使用 nghttp2，HTTP/3 使用 ngtcp2、nghttp3 和 LibreSSL，SFTP 使用 libssh2。
- 断点和数据块摘要使用 SQLite WAL 和 `synchronous=FULL`。
- BT/磁力使用 pinned libtorrent。
- HLS/DASH 的媒体处理和 MP4 合成使用 pinned FFmpeg。
- Windows 使用 CNG SHA-256。Android 使用按 ABI 编译的 LibreSSL。
- Android 构建覆盖 ARM64、ARMv7、x86_64 和 x86。各 ABI 使用 API 24、静态 C++ 运行库和 16 KiB ELF LOAD 对齐。

## 任务生命周期与资源

- 任务拥有明确的状态、代次和终态，暂停、取消、关闭都等待原生任务退出后再释放资源。
- 任务名额、连接数、HTTP 流、缓冲区、限速、文件句柄和处理队列由共享资源域统一管理。
- 探测请求、密钥请求、清单请求、媒体子任务和重试请求都计入资源预算。
- 连接预算耗尽时进入排队，不允许各任务自行抬高全局上限。
- 资源释放必须幂等；旧回调不能修改新一次尝试的进度或状态。

## 调度与网络

- 分片窗口和持久化区间分开，调度器根据实测吞吐、延迟、启动成本和剩余字节分配工作。
- 慢尾协助只作用于同一来源，避免把一个来源的速度套到另一个来源。
- 429/503 统一处理 `Retry-After`、冷却和取消；授权失败单独归因。
- 镜像来源保留各自的请求头，跨源镜像只发送安全的非凭据头。
- HTTP 响应头到达后立即唤醒调度器，减少等待完整响应或连接复用造成的空转。
- 原生 curl 的 `CURLOPT_PROTOCOLS_STR` 按实际编译能力生成。Android 没有 SFTP 时不再把 `sftp` 写进协议列表，修复 `Unsupported HTTP option PROTOCOLS_STR` 导致 HTTP 任务无法启动的问题。
- HTTP 策略可选自动、HTTP/1.1、允许 HTTP/2 和优先 HTTP/3。HTTP/3 需要服务端和当前网络支持 QUIC。任务指标分别记录实际协商的版本、请求数、HTTP 流数和连接数。
- FTP 支持账号认证和断点。SFTP 使用账号密码或 SSH 密钥，并使用 `known_hosts` 校验服务端身份。

## 存储与恢复

- 目标文件使用独占锁，防止同一文件被重新提交的旧任务和新任务同时写入。
- 数据文件完成同步后才提交断点；进程被杀后只复用有摘要和来源身份证明的区间。
- 来源长度、ETag、校验摘要和账号作用域变化时拒绝错误续传，保留原始数据供人工处理。
- 用户目标路径已有文件时，需要匹配的断点记录才能写入。原文件会保留在原位置。
- 存储异常会保留具体原因，例如目录不可写、空间不足、文件锁冲突或校验失败。

## 媒体与 BT

- HLS/DASH：清单、音视频轨、初始化片段、ByteRange、AES-128、分片保存、MP4 合成和直播结束。
- 媒体状态区分网络完成、处理阶段和最终保存；保存失败可只重试保存。
- libtorrent BT/磁力：Tracker/DHT、文件选择、磁力元信息、暂停恢复、坏块检查、上下行限速和目录产物。
- 多文件 BT 返回完整目录；单文件种子按普通文件处理。
- Metalink v3/v4 读取文件名、大小、SHA-256 和多个来源。来源具备同一份摘要时才可混合下载。

## Windows 和 Android 入口

- Windows x64 的公开 `turbodl` 启动器在同目录存在共用核心时，将兼容的单任务命令交给原生 CLI。通过 `turbodl native --help` 查看 HLS/DASH、BT、Metalink、SSH、代理、DoH、镜像和 HTTP 版本等参数。批量、旧版专属参数和插件可使用 `turbodl jvm`。
- Windows x64 已通过公开 CLI 到本地 HTTP/2 TLS 和 HTTP/3 QUIC 服务的传输验证。两种协议均独立校验了完整文件与实际协商版本。
- Android 新传输构建按四个 ABI 编译 HTTP/2、HTTP/3、FTP、SFTP。云取已有 HTTP/HTTPS、FTP、HLS/DASH 和磁力任务入口，选择 HTTP/3 后可在详情核对实际协议。

## 测试与证据

- Android ARM64、ARMv7、x86_64、x86 已完成新传输依赖、BT、媒体和 Metalink 链接。各库在剥离符号后通过 16 KiB ELF 对齐、动态依赖和 JNI 导出检查。
- 在连接的 Android 设备上安装了云取测试包，四个原先失败的任务重试后全部完成，文件写入公共下载目录；下载结果包含两个 APK 和两个带 UUID 文件名的文件。
- 此前真机测试使用修复存储初始化的包。新 HTTP/2、HTTP/3、SFTP 构建需要补做 Android 设备验证。
- HLS/DASH、BT/磁力、FTP、跨进程强杀恢复和长时间压力仍需要对应真实资源与设备时间；本地编译和单个真机 HTTP 流程不等于全协议验收。

## 已知边界

- Windows ARM64、Linux、macOS 和 iOS 的新核心产物与各目标上的运行验证仍待完成。
- ED2K 目前提供链接解析、校验和 AICH 基础组件。Peer 下载和续传仍待完成。
- 全量真实网络同条件比较、设备后台恢复和长时测试仍是正式发行条件。
