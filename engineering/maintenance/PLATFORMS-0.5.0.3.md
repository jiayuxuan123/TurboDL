# TurboDL 0.5.0.3 平台构建与验证边界

## 结论

- Zig `0.14.1` 对 8 个目标分别完成 launcher 与 dataplane 的实际交叉编译，
  共涉及 16 个 Zig 原生目标产物；`native-release-build.log` 最终以成功结束。
- 打包阶段由现有 `engineering/release/package_release.py` 强制检查 PE、ELF、
  Mach-O 头部。该检查验证文件格式和目标架构，不代表目标机实际运行通过。
- Windows x86_64 完成了 native 与 JVM 的实际运行 smoke；
  Windows ARM64、Linux、macOS 在本轮只有编译成功和产物/头部检查证据，
  不能声称目标机运行、签名或公证通过。
- Android 继续使用 Kotlin/ART 路径，不属于上述 8 个 Zig 原生目标。
  本轮 ADB 无可用设备，因此没有真机运行或真机测速结论。

## 8 个 Zig 原生目标

| 目标 | launcher + dataplane 交叉编译 | 包装头部检查 | 目标机运行证据 |
|---|---|---|---|
| `windows-x86_64` | 成功 | PE，x86_64 | Windows x64 实际运行通过 |
| `windows-arm64` | 成功 | PE，ARM64 | 无 |
| `linux-x86_64-glibc` | 成功 | ELF64，x86_64 | 无 |
| `linux-x86_64-musl` | 成功 | ELF64，x86_64 | 无 |
| `linux-arm64-glibc` | 成功 | ELF64，ARM64 | 无 |
| `linux-arm64-musl` | 成功 | ELF64，ARM64 | 无 |
| `macos-x86_64` | 成功 | 64-bit Mach-O，x86_64 | 无 |
| `macos-arm64` | 成功 | 64-bit Mach-O，ARM64 | 无 |

这里的“成功”只指交叉编译产出存在并通过格式检查。没有目标 Linux 主机、
ARM64 Windows 主机或 macOS 主机的运行结果时，不推导安装包可启动、系统兼容性、
性能或稳定性已经验收。

## 打包时强制的产物检查

`package_release.py` 固定枚举上述 8 个目标，并对每个目标的 launcher 与
dataplane 两个二进制执行 `check_binary()`：

- Windows：检查 `MZ`、PE 文件头签名以及 `0x8664`（x86_64）或 `0xAA64`
  （ARM64）机器类型。
- Linux：检查 ELF64 小端头以及 `e_machine` 为 `62`（x86_64）或
  `183`（ARM64）。
- macOS：检查 64-bit Mach-O magic，以及 CPU 类型为 x86_64 或 ARM64。

任一预期二进制缺失或头部不符合目标时，打包直接失败，不会生成缺少平台产物的
发布包。macOS 的 universal2 产物由两个已通过头检查的单架构 Mach-O 组合而成，
但本轮同样没有 macOS 目标机运行、代码签名或公证结果。

头部检查本身也有明确上限：它不能证明二进制能在目标系统启动，不能替代
依赖检查、系统版本覆盖、权限行为、崩溃/稳定性测试或安装包验收。

## Windows x86_64 运行 smoke

`NATIVE_SMOKE.json` 记录 Windows x86_64、launcher `0.5.0.3` 的
`native_and_jvm_runtime` 为 `PASS`：

| 路径 | 文件大小 | SHA-256 | 结果 |
|---|---:|---|---|
| native | `1,048,576 B = 1 MiB` | `28950c81cbb80d666da7600d2e27582beb3e6be6cf9988c8f4ec47be2255720b` | 实际运行通过 |
| JVM | `1,048,576 B = 1 MiB` | `28950c81cbb80d666da7600d2e27582beb3e6be6cf9988c8f4ec47be2255720b` | 实际运行通过 |

两个 1 MiB 文件是独立运行载荷，均与预期摘要一致；这里写 `MiB`，不写成
十进制 `MB`。该 smoke 只证明 Windows x64 上的这两条运行路径，不能外推到
Windows ARM64、Linux、macOS 或 Android。

## 其他平台状态

- Windows ARM64：launcher/dataplane 已交叉编译并通过 PE/ARM64 头部检查，
  未在 ARM64 Windows 真机运行，因此不声称运行成功或签名通过。
- Linux：x86_64/ARM64、glibc/musl 四个目标均已交叉编译并通过对应 ELF
  头部检查，未在目标发行版或容器实机运行。
- macOS：x86_64/ARM64 均已交叉编译并通过 Mach-O 头检查，未在 macOS
  真机运行；不声称 Apple 签名、公证或 Gatekeeper 验收通过。
- Android：继续使用 Kotlin/ART 实现，不套用 Zig 原生目标结论。本轮 ADB
  无设备，无真机安装、运行或速度数据。

## 可引用边界

可引用：

- Zig `0.14.1` 对 8 目标的 launcher + dataplane 交叉编译成功；
- 打包时 8 目标 PE/ELF/Mach-O 头部检查为强制步骤；
- Windows x86_64 native/JVM 各以 1 MiB 独立载荷完成实际运行 smoke；
- Android 继续采用 Kotlin/ART，本轮 ADB 未发现设备。

不可引用：

- Windows ARM64、Linux、macOS 已通过目标机运行测试；
- macOS 已完成签名或公证；
- Android 已完成真机运行或真机速度测试；
- 头部检查通过等同于系统兼容性、性能或稳定性验收。

性能与回归证据见
[engineering/maintenance/AB-0.5.0.3.md](engineering/maintenance/AB-0.5.0.3.md)。
