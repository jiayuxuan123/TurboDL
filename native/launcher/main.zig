// TurboDL native fast-start launcher.
//
// Select the bundled C++ core before submitting a task. Legacy CLI options
// and plugins continue to use the JVM entry point.

const std = @import("std");
const builtin = @import("builtin");

const version = "0.5.0.7";
const jar_name = "turbodl-cli-all.jar";

fn fail(allocator: std.mem.Allocator, comptime fmt: []const u8, args: anytype) noreturn {
    const msg = std.fmt.allocPrint(allocator, fmt, args) catch "turbodl launcher error";
    std.io.getStdErr().writer().print("{s}\n", .{msg}) catch {};
    std.process.exit(1);
}

fn exists(path: []const u8) bool {
    std.fs.cwd().access(path, .{}) catch return false;
    return true;
}

fn envOwned(allocator: std.mem.Allocator, name: []const u8) ?[]const u8 {
    return std.process.getEnvVarOwned(allocator, name) catch null;
}

fn resolveJar(allocator: std.mem.Allocator, exe_dir: ?[]const u8) []const u8 {
    if (envOwned(allocator, "TURBODL_JAR")) |override| {
        if (exists(override)) return override;
    }
    if (exe_dir) |dir| {
        const beside = std.fs.path.join(allocator, &.{ dir, jar_name }) catch unreachable;
        if (exists(beside)) return beside;
        const lib = std.fs.path.join(allocator, &.{ dir, "lib", jar_name }) catch unreachable;
        if (exists(lib)) return lib;
    }
    if (exists(jar_name)) return jar_name;
    fail(allocator, "cannot find {s}; place it next to the launcher or set TURBODL_JAR", .{jar_name});
}

fn resolveJava(allocator: std.mem.Allocator) []const u8 {
    if (envOwned(allocator, "TURBODL_JAVA")) |override| {
        if (exists(override)) return override;
    }
    if (envOwned(allocator, "JAVA_HOME")) |home| {
        const exe_name = if (builtin.os.tag == .windows) "java.exe" else "java";
        const candidate = std.fs.path.join(allocator, &.{ home, "bin", exe_name }) catch unreachable;
        if (exists(candidate)) return candidate;
    }
    return "java";
}

fn resolveNativeDataplane(allocator: std.mem.Allocator, exe_dir: ?[]const u8) []const u8 {
    const name = if (builtin.os.tag == .windows) "tdl-native.exe" else "tdl-native";
    if (envOwned(allocator, "TURBODL_NATIVE")) |override| {
        if (exists(override)) return override;
    }
    if (exe_dir) |dir| {
        const beside = std.fs.path.join(allocator, &.{ dir, name }) catch unreachable;
        if (exists(beside)) return beside;
        const nested = std.fs.path.join(allocator, &.{ dir, "native", name }) catch unreachable;
        if (exists(nested)) return nested;
        const dataplane = std.fs.path.join(allocator, &.{ dir, "native-dataplane", name }) catch unreachable;
        if (exists(dataplane)) return dataplane;
    }
    if (exists(name)) return name;
    fail(allocator, "cannot find {s}; place it next to the launcher or set TURBODL_NATIVE", .{name});
}

fn sharedCore(allocator: std.mem.Allocator, exe_dir: ?[]const u8) ?[]const u8 {
    const dir = exe_dir orelse return null;
    const name = if (builtin.os.tag == .windows) "tdl-vnext.exe" else "tdl-vnext";
    const beside = std.fs.path.join(allocator, &.{ dir, name }) catch unreachable;
    if (exists(beside)) return beside;
    allocator.free(beside);
    return null;
}

fn canUseSharedCore(args: []const [:0]u8) bool {
    var has_output = false;
    var has_hash = false;
    var has_mirror = false;
    var index: usize = 0;
    while (index < args.len) : (index += 1) {
        const arg = args[index];
        if (!std.mem.startsWith(u8, arg, "-")) continue;
        if (std.mem.eql(u8, arg, "--capabilities") or
            std.mem.eql(u8, arg, "--help") or std.mem.eql(u8, arg, "-h")) return true;
        if (std.mem.eql(u8, arg, "--json") or std.mem.eql(u8, arg, "--force-http1") or
            std.mem.eql(u8, arg, "--allow-loopback-fixture")) continue;
        const value_options = [_][]const u8{
            "-o", "--output", "-c", "--connections", "--threads", "--sha256", "--known-size",
            "--stable-key", "--checkpoint-dir", "--window-bytes", "--deadline", "--connect-timeout-ms",
            "--read-timeout-ms", "--speed-limit", "--retry", "-r", "--http-policy",
            "--username", "--password", "--proxy", "-p", "--doh", "--ca-bundle",
            "--ssh-known-hosts", "--ssh-public-key", "--ssh-private-key",
            "--header", "-H", "--user-agent", "-A", "--mirror", "-M", "--mirror-header",
            "--hls", "--dash", "--segments", "--record-seconds", "--max-bandwidth",
            "--max-segment-bytes", "--audio-language", "--video-representation", "--audio-representation",
            "--torrent", "--metalink", "--base-url", "--file-name", "--select-file",
            "--seed-seconds", "--upload-limit",
        };
        var recognized = false;
        for (value_options) |option| {
            if (std.mem.eql(u8, arg, option)) {
                recognized = true;
                break;
            }
        }
        // --timeout has different units in the compatibility CLI.
        if (!recognized or index + 1 >= args.len) return false;
        index += 1;
        if (std.mem.eql(u8, arg, "-o") or std.mem.eql(u8, arg, "--output")) has_output = true;
        if (std.mem.eql(u8, arg, "--sha256")) has_hash = true;
        if (std.mem.eql(u8, arg, "--mirror") or std.mem.eql(u8, arg, "-M")) has_mirror = true;
        if (std.mem.eql(u8, arg, "--speed-limit")) {
            _ = std.fmt.parseUnsigned(u64, args[index], 10) catch return false;
        }
    }
    return has_output and (!has_mirror or has_hash);
}

fn runChild(allocator: std.mem.Allocator, argv: []const []const u8, label: []const u8) noreturn {
    var child = std.process.Child.init(argv, allocator);
    child.stdin_behavior = .Inherit;
    child.stdout_behavior = .Inherit;
    child.stderr_behavior = .Inherit;
    child.spawn() catch |err| {
        fail(allocator, "failed to start {s}: {s}", .{ label, @errorName(err) });
    };
    const term = child.wait() catch {
        fail(allocator, "failed while waiting for {s}", .{label});
    };
    switch (term) {
        .Exited => |code| std.process.exit(code),
        else => std.process.exit(1),
    }
}

pub fn main() !void {
    var gpa = std.heap.GeneralPurposeAllocator(.{}){};
    defer _ = gpa.deinit();
    const allocator = gpa.allocator();

    const args = try std.process.argsAlloc(allocator);
    defer std.process.argsFree(allocator, args);
    const exe_path = std.fs.selfExePathAlloc(allocator) catch null;
    defer if (exe_path) |path| allocator.free(path);
    const exe_dir = if (exe_path) |p| std.fs.path.dirname(p) else null;
    const shared = sharedCore(allocator, exe_dir);
    defer if (shared) |path| allocator.free(path);

    // Fully native fast path: no JVM boot for a version query.
    if (args.len >= 2 and
        (std.mem.eql(u8, args[1], "--version") or
        std.mem.eql(u8, args[1], "-V") or
        std.mem.eql(u8, args[1], "version")))
    {
        try std.io.getStdOut().writer().print(
            "turbodl {s} ({s})\n",
            .{ version, if (shared != null) "C++ core + JVM compatibility" else "Kotlin/JVM core" },
        );
        return;
    }

    const explicit_native = args.len >= 2 and std.mem.eql(u8, args[1], "native");
    const explicit_jvm = args.len >= 2 and std.mem.eql(u8, args[1], "jvm");
    if (explicit_native or (!explicit_jvm and shared != null and canUseSharedCore(args[1..]))) {
        const native_exe = shared orelse resolveNativeDataplane(allocator, exe_dir);
        const start: usize = if (explicit_native) 2 else 1;
        var native_argv = std.ArrayList([]const u8).init(allocator);
        defer native_argv.deinit();
        try native_argv.append(native_exe);
        for (args[start..]) |arg| try native_argv.append(arg);
        runChild(allocator, native_argv.items, "the native data-plane engine");
    }

    const jar = resolveJar(allocator, exe_dir);
    const java = resolveJava(allocator);

    // Hand off: java -jar <jar> <original args...>, inheriting stdio so the
    // engine talks to the terminal directly and the exit code is forwarded.
    var argv = std.ArrayList([]const u8).init(allocator);
    defer argv.deinit();
    try argv.append(java);
    try argv.append("-jar");
    try argv.append(jar);
    const start: usize = if (explicit_jvm) 2 else 1;
    for (args[start..]) |arg| try argv.append(arg);

    runChild(allocator, argv.items, "the JVM engine");
}
