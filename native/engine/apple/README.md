# Apple SDK build boundary

`engine.h` is the Swift-importable C interface. The module map is used for
macOS local SDK consumers and XCFramework headers. This directory is not an
iOS runtime acceptance result.

XCFramework packaging must run on a macOS host with Xcode, and must include
an arm64 iOS device library plus arm64/x86_64 simulator slices for consumers.
Simulator builds do not replace the mandatory iOS device tests.

The shared core owns foreground tasks. The host owns URLSession background
transfers and supplies completion results only after system commit. Native
BT/ED2K tasks must checkpoint on suspension. The URLSession background host
adapter and real-device validation are not implemented by this module map.
