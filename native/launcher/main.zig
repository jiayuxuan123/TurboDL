// TurboDL native fast-start launcher.
//
// Polyglot split: this tiny native binary owns process start, instant feedback
// and argument hand-off. The Kotlin/JVM engine (turbodl-cli-all.jar) remains the
// unchanged core that does the actual downloading. The launcher never touches the
// data plane; it only starts fast and hands the task to the JVM.

const std = @import("std");
const builtin = @import("builtin");

const version = "0.5.0.4";
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

    // Fully native fast path: no JVM boot for a version query.
    if (args.len >= 2 and
        (std.mem.eql(u8, args[1], "--version") or
        std.mem.eql(u8, args[1], "-V") or
        std.mem.eql(u8, args[1], "version")))
    {
        try std.io.getStdOut().writer().print(
            "turbodl {s} (native starter + Kotlin/JVM core)\n",
            .{version},
        );
        return;
    }

    const exe_path = std.fs.selfExePathAlloc(allocator) catch null;
    const exe_dir = if (exe_path) |p| std.fs.path.dirname(p) else null;

    if (args.len >= 2 and std.mem.eql(u8, args[1], "native")) {
        const native_exe = resolveNativeDataplane(allocator, exe_dir);
        var native_argv = std.ArrayList([]const u8).init(allocator);
        defer native_argv.deinit();
        try native_argv.append(native_exe);
        for (args[2..]) |arg| try native_argv.append(arg);
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
    for (args[1..]) |arg| try argv.append(arg);

    runChild(allocator, argv.items, "the JVM engine");
}
