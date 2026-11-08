const std = @import("std");
const builtin = @import("builtin");

const Piece = struct {
    start: u64,
    end: u64,
    written: std.atomic.Value(u64),

    fn init(start: u64, end: u64, written: u64) Piece {
        return .{
            .start = start,
            .end = end,
            .written = std.atomic.Value(u64).init(written),
        };
    }
};

const Options = struct {
    url: []const u8,
    output: []const u8,
    workdir: []const u8,
    expected: []const u8,
    connections: usize,
    retries: usize,
};

var cancelled = std.atomic.Value(bool).init(false);

fn signalHandler(_: c_int) callconv(.c) void {
    cancelled.store(true, .release);
}

fn usage() noreturn {
    std.debug.print(
        "usage: zig-probe --url URL --output PATH --workdir PATH --expected-sha256 HEX --connections N --retries N\n",
        .{},
    );
    std.process.exit(2);
}

fn parseOptions(allocator: std.mem.Allocator) !Options {
    const args = try std.process.argsAlloc(allocator);
    var options: Options = .{
        .url = "",
        .output = "",
        .workdir = "",
        .expected = "",
        .connections = 16,
        .retries = 3,
    };
    var index: usize = 1;
    while (index < args.len) {
        const flag = args[index];
        // Positional URL, TurboDL-CLI compatible.
        if (flag.len > 0 and flag[0] != '-') {
            if (options.url.len != 0) usage();
            options.url = try allocator.dupe(u8, flag);
            index += 1;
            continue;
        }
        // Boolean flags accepted for CLI compatibility; native JSON is always emitted.
        if (std.mem.eql(u8, flag, "--json") or
            std.mem.eql(u8, flag, "--timing") or
            std.mem.eql(u8, flag, "--quiet") or
            std.mem.eql(u8, flag, "--no-slow-start") or
            std.mem.eql(u8, flag, "--no-adaptive") or
            std.mem.eql(u8, flag, "--no-warmup"))
        {
            index += 1;
            continue;
        }
        if (index + 1 >= args.len) usage();
        const value = args[index + 1];
        if (std.mem.eql(u8, flag, "--url")) {
            options.url = try allocator.dupe(u8, value);
        } else if (std.mem.eql(u8, flag, "-o") or std.mem.eql(u8, flag, "--output")) {
            options.output = try allocator.dupe(u8, value);
        } else if (std.mem.eql(u8, flag, "--workdir")) {
            options.workdir = try allocator.dupe(u8, value);
        } else if (std.mem.eql(u8, flag, "--sha256") or
            std.mem.eql(u8, flag, "--expected-sha256"))
        {
            options.expected = try allocator.dupe(u8, value);
        } else if (std.mem.eql(u8, flag, "-c") or std.mem.eql(u8, flag, "--connections")) {
            options.connections = try std.fmt.parseInt(usize, value, 10);
        } else if (std.mem.eql(u8, flag, "-r") or
            std.mem.eql(u8, flag, "--retry") or
            std.mem.eql(u8, flag, "--retries"))
        {
            options.retries = try std.fmt.parseInt(usize, value, 10);
        } else if (std.mem.eql(u8, flag, "--known-size")) {
            // Native path probes the size; accepted for CLI compatibility.
        } else {
            usage();
        }
        index += 2;
    }
    if (options.url.len == 0 or options.output.len == 0 or
        options.expected.len == 0 or options.connections < 1 or
        options.connections > 256)
    {
        usage();
    }
    return options;
}

fn findHeader(request: *std.http.Client.Request, name: []const u8) ?[]const u8 {
    var iterator = request.response.iterateHeaders();
    while (iterator.next()) |header| {
        if (std.ascii.eqlIgnoreCase(header.name, name)) return header.value;
    }
    return null;
}

fn parseContentRange(value: []const u8) !struct { start: u64, end: u64, total: u64 } {
    if (!std.mem.startsWith(u8, value, "bytes ")) return error.InvalidContentRange;
    const dash = std.mem.indexOfScalar(u8, value, '-') orelse return error.InvalidContentRange;
    const slash = std.mem.indexOfScalarPos(u8, value, dash + 1, '/') orelse return error.InvalidContentRange;
    return .{
        .start = try std.fmt.parseInt(u64, std.mem.trim(u8, value[6..dash], " "), 10),
        .end = try std.fmt.parseInt(u64, std.mem.trim(u8, value[dash + 1 .. slash], " "), 10),
        .total = try std.fmt.parseInt(u64, std.mem.trim(u8, value[slash + 1 ..], " "), 10),
    };
}

fn makePieces(allocator: std.mem.Allocator, total: u64, connections: usize) ![]Piece {
    var size: u64 = total / @max(@as(u64, 1), @as(u64, @intCast(connections * 4)));
    size = @max(@as(u64, 64 * 1024), @min(@as(u64, 16 * 1024 * 1024), size));
    var list = std.ArrayList(Piece).init(allocator);
    var start: u64 = 0;
    while (start < total) : (start += size) {
        try list.append(Piece.init(start, @min(total - 1, start + size - 1), 0));
    }
    return list.toOwnedSlice();
}

fn persist(
    allocator: std.mem.Allocator,
    state_path: []const u8,
    total: u64,
    pieces: []const Piece,
) !void {
    const temp_path = try std.fmt.allocPrint(allocator, "{s}.tmp", .{state_path});
    defer allocator.free(temp_path);
    var file = try std.fs.cwd().createFile(temp_path, .{ .truncate = true });
    defer file.close();
    var writer = file.writer();
    try writer.print("tdlprobe1 {d} {d}\n", .{ total, pieces.len });
    for (pieces) |piece| {
        try writer.print("{d} {d} {d}\n", .{
            piece.start,
            piece.end,
            piece.written.load(.acquire),
        });
    }
    try file.sync();
    std.fs.cwd().rename(temp_path, state_path) catch |rename_error| {
        if (rename_error != error.PathAlreadyExists) return rename_error;
        try std.fs.cwd().deleteFile(state_path);
        try std.fs.cwd().rename(temp_path, state_path);
    };
}

fn loadPieces(
    allocator: std.mem.Allocator,
    state_path: []const u8,
    total: u64,
) !?struct { pieces: []Piece, resumed: usize } {
    const content = std.fs.cwd().readFileAlloc(allocator, state_path, 512 * 1024 * 1024) catch return null;
    defer allocator.free(content);
    var lines = std.mem.splitScalar(u8, content, '\n');
    const header = lines.next() orelse return null;
    var header_iter = std.mem.tokenizeScalar(u8, header, ' ');
    if (!std.mem.eql(u8, header_iter.next() orelse "", "tdlprobe1")) return null;
    const saved_total = std.fmt.parseInt(u64, header_iter.next() orelse return null, 10) catch return null;
    const count = std.fmt.parseInt(usize, header_iter.next() orelse return null, 10) catch return null;
    if (saved_total != total or count == 0 or count > 2_000_000) return null;
    const list = try allocator.alloc(Piece, count);
    var expected: u64 = 0;
    var resumed: usize = 0;
    for (list) |*piece| {
        const line = lines.next() orelse return null;
        var iter = std.mem.tokenizeScalar(u8, line, ' ');
        piece.start = std.fmt.parseInt(u64, iter.next() orelse return null, 10) catch return null;
        piece.end = std.fmt.parseInt(u64, iter.next() orelse return null, 10) catch return null;
        const written = std.fmt.parseInt(u64, iter.next() orelse return null, 10) catch return null;
        piece.* = Piece.init(piece.start, piece.end, written);
        if (piece.start != expected or piece.end < piece.start or
            piece.written.load(.acquire) > piece.end - piece.start + 1)
        {
            return null;
        }
        if (piece.written.load(.acquire) > 0) resumed += 1;
        expected = piece.end + 1;
    }
    if (expected != total) return null;
    return .{ .pieces = list, .resumed = resumed };
}

fn sha256File(allocator: std.mem.Allocator, path: []const u8) ![64]u8 {
    _ = allocator;
    var file = try std.fs.cwd().openFile(path, .{ .mode = .read_only });
    defer file.close();
    var hash = std.crypto.hash.sha2.Sha256.init(.{});
    var buffer: [1024 * 1024]u8 = undefined;
    while (true) {
        const count = try file.read(&buffer);
        if (count == 0) break;
        hash.update(buffer[0..count]);
    }
    var digest: [32]u8 = undefined;
    hash.final(&digest);
    var output: [64]u8 = undefined;
    _ = std.fmt.bufPrint(&output, "{}", .{std.fmt.fmtSliceHexLower(&digest)}) catch unreachable;
    return output;
}

const Shared = struct {
    allocator: std.mem.Allocator,
    uri: std.Uri,
    pieces: []Piece,
    next: std.atomic.Value(usize),
    state_path: []const u8,
    total: u64,
    retries: usize,
    output: std.fs.File,
    state_lock: std.Thread.Mutex,
    failed: std.atomic.Value(bool),
    wire_bytes: std.atomic.Value(u64),
    requests: std.atomic.Value(u64),
    retry_count: std.atomic.Value(u64),
};

fn worker(shared: *Shared) void {
    var client = std.http.Client{ .allocator = shared.allocator };
    defer client.deinit();
    while (!cancelled.load(.acquire) and !shared.failed.load(.acquire)) {
        const index = shared.next.fetchAdd(1, .monotonic);
        if (index >= shared.pieces.len) return;
        const piece = &shared.pieces[index];
        const length = piece.end - piece.start + 1;
        var written = piece.written.load(.acquire);
        var attempt: usize = 0;
        while (written < length and attempt <= shared.retries and
            !cancelled.load(.acquire)) : (attempt += 1)
        {
            if (attempt > 0) {
                _ = shared.retry_count.fetchAdd(1, .monotonic);
                std.Thread.sleep(@as(u64, 100 * (@as(u64, 1) << @intCast(attempt - 1))) * std.time.ns_per_ms);
            }
            const from = piece.start + written;
            const range = std.fmt.allocPrint(
                shared.allocator,
                "bytes={d}-{d}",
                .{ from, piece.end },
            ) catch {
                shared.failed.store(true, .release);
                return;
            };
            defer shared.allocator.free(range);
            var header_buffer: [32 * 1024]u8 = undefined;
            var request = client.open(.GET, shared.uri, .{
                .redirect_behavior = .unhandled,
                .server_header_buffer = &header_buffer,
                .extra_headers = &.{
                    .{ .name = "range", .value = range },
                    .{ .name = "accept-encoding", .value = "identity" },
                },
            }) catch {
                shared.failed.store(true, .release);
                return;
            };
            defer request.deinit();
            _ = shared.requests.fetchAdd(1, .monotonic);
            request.send() catch continue;
            request.wait() catch continue;
            if (request.response.status != .partial_content) continue;
            const value = findHeader(&request, "content-range") orelse continue;
            const info = parseContentRange(value) catch continue;
            if (info.start != from or info.total != shared.total) continue;
            var reader = request.reader();
            var buffer: [256 * 1024]u8 = undefined;
            while (written < length and !cancelled.load(.acquire)) {
                const count = reader.read(&buffer) catch break;
                if (count == 0) break;
                const allowed: usize = @intCast(@min(
                    @as(u64, count),
                    length - written,
                ));
                shared.output.pwriteAll(buffer[0..allowed], piece.start + written) catch {
                    shared.failed.store(true, .release);
                    return;
                };
                written += allowed;
                piece.written.store(written, .release);
                _ = shared.wire_bytes.fetchAdd(count, .monotonic);
            }
            if (written == length) {
                shared.output.sync() catch {};
                shared.state_lock.lock();
                defer shared.state_lock.unlock();
                persist(
                    shared.allocator,
                    shared.state_path,
                    shared.total,
                    shared.pieces,
                ) catch shared.failed.store(true, .release);
                break;
            }
        }
        if (written != length) {
            shared.failed.store(true, .release);
            return;
        }
    }
}

pub fn main() !void {
    var arena = std.heap.ArenaAllocator.init(std.heap.page_allocator);
    defer arena.deinit();
    const allocator = arena.allocator();
    var options = try parseOptions(allocator);
    if (options.workdir.len == 0) {
        options.workdir = try std.fmt.allocPrint(allocator, "{s}.parts", .{options.output});
    }
    try std.fs.cwd().makePath(options.workdir);
    if (builtin.os.tag != .windows) {
        const action = std.posix.Sigaction{
            .handler = .{ .handler = signalHandler },
            .mask = std.posix.empty_sigset,
            .flags = 0,
        };
        _ = std.posix.sigaction(std.posix.SIG.INT, &action, null);
        _ = std.posix.sigaction(std.posix.SIG.TERM, &action, null);
    }
    const started = std.time.milliTimestamp();
    const uri = try std.Uri.parse(options.url);
    var probe_client = std.http.Client{ .allocator = allocator };
    defer probe_client.deinit();
    var probe_headers: [16 * 1024]u8 = undefined;
    const probe_started = std.time.milliTimestamp();
    var probe = try probe_client.open(.GET, uri, .{
        .redirect_behavior = .unhandled,
        .server_header_buffer = &probe_headers,
        .extra_headers = &.{
            .{ .name = "range", .value = "bytes=0-0" },
            .{ .name = "accept-encoding", .value = "identity" },
        },
    });
    defer probe.deinit();
    try probe.send();
    try probe.wait();
    if (probe.response.status != .partial_content) return error.ProbeFailed;
    const probe_range = try parseContentRange(findHeader(&probe, "content-range") orelse return error.ProbeFailed);
    { var discard: [8]u8 = undefined; _ = probe.reader().read(&discard) catch 0; }
    const probe_ms = @as(f64, @floatFromInt(std.time.milliTimestamp() - probe_started));

    const state_path = try std.fmt.allocPrint(allocator, "{s}/state.txt", .{options.workdir});
    const loaded = try loadPieces(allocator, state_path, probe_range.total);
    const pieces = if (loaded) |value| value.pieces else try makePieces(allocator, probe_range.total, options.connections);
    const resumed = if (loaded) |value| value.resumed else 0;
    var output = try std.fs.cwd().createFile(options.output, .{ .read = true, .truncate = false });
    defer output.close();
    try output.setEndPos(probe_range.total);

    var shared = Shared{
        .allocator = std.heap.smp_allocator,
        .uri = uri,
        .pieces = pieces,
        .next = std.atomic.Value(usize).init(0),
        .state_path = state_path,
        .total = probe_range.total,
        .retries = options.retries,
        .output = output,
        .state_lock = .{},
        .failed = std.atomic.Value(bool).init(false),
        .wire_bytes = std.atomic.Value(u64).init(0),
        .requests = std.atomic.Value(u64).init(0),
        .retry_count = std.atomic.Value(u64).init(0),
    };
    const transfer_started = std.time.milliTimestamp();
    const worker_count = @min(options.connections, pieces.len);
    const threads = try allocator.alloc(std.Thread, worker_count);
    for (threads) |*thread| thread.* = try std.Thread.spawn(.{}, worker, .{&shared});
    for (threads) |thread| thread.join();
    const transfer_ms = @as(f64, @floatFromInt(std.time.milliTimestamp() - transfer_started));
    if (shared.failed.load(.acquire) or cancelled.load(.acquire)) return error.TransferFailed;
    const hash_started = std.time.milliTimestamp();
    const digest = try sha256File(allocator, options.output);
    const hash_ms = @as(f64, @floatFromInt(std.time.milliTimestamp() - hash_started));
    const matched = std.ascii.eqlIgnoreCase(&digest, options.expected);
    const stdout = std.io.getStdOut().writer();
    try stdout.print(
        "{{\"engine\":\"zig\",\"exit\":{d},\"total_bytes\":{d},\"useful_bytes\":{d},\"wire_bytes\":{d},\"requests\":{d},\"retries\":{d},\"connections\":{d},\"probe_ms\":{d},\"transfer_ms\":{d},\"hash_ms\":{d},\"total_ms\":{d},\"expected_sha256\":\"{s}\",\"actual_sha256\":\"{s}\",\"hash_matched\":{s},\"resumed_pieces\":{d}}}\n",
        .{
            if (matched) @as(u8, 0) else @as(u8, 1),
            probe_range.total,
            probe_range.total,
            shared.wire_bytes.load(.monotonic),
            shared.requests.load(.monotonic),
            shared.retry_count.load(.monotonic),
            options.connections,
            probe_ms,
            transfer_ms,
            hash_ms,
            @as(f64, @floatFromInt(std.time.milliTimestamp() - started)),
            options.expected,
            &digest,
            if (matched) "true" else "false",
            resumed,
        },
    );
    if (!matched) std.process.exit(1);
}
