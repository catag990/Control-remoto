using System.Diagnostics;
using System.Linq;
using System.Threading;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using RemoteHost;

internal static class Program
{
    private static readonly SemaphoreSlim SessionGate = new(1, 1);

    private static async Task Main(string[] args)
    {
        var state = new HostState();
        Console.WriteLine("RemoteHost: " + state.DeviceId);
        Console.WriteLine("TLS SPKI pin: " + state.PublicPin);
        var builder = WebApplication.CreateBuilder(args);
        builder.Logging.ClearProviders();

        builder.Logging.AddSimpleConsole(options =>
        {
            options.SingleLine = true;
            options.TimestampFormat = "HH:mm:ss ";
        });

        builder.Logging.SetMinimumLevel(LogLevel.Trace);

        builder.Logging.AddFilter(
            "Microsoft.AspNetCore.Server.Kestrel",
            LogLevel.Trace
        );
        builder.WebHost.UseKestrel(options => options.ListenAnyIP(8443, listen => listen.UseHttps(state.Certificate)));
        var app = builder.Build();
        app.UseWebSockets(new WebSocketOptions { KeepAliveInterval = TimeSpan.FromSeconds(20) });

        app.MapPost("/api/v1/pair", async (HttpContext ctx) =>
        {
            try
            {
                using var parsed = await JsonDocument.ParseAsync(ctx.Request.Body);
                var body = parsed.RootElement;
                string token = body.GetProperty("token").GetString() ?? "";
                string id = body.GetProperty("clientId").GetString() ?? "";
                string pub = body.GetProperty("publicKeySpki").GetString() ?? "";
                if (!state.AddPairedClient(token, id, pub, ctx.Connection.RemoteIpAddress))
                    return Results.StatusCode(403);
                return Results.Json(new { ok = true, deviceId = state.DeviceId });
            }
            catch (Exception) { return Results.BadRequest(new { error = "invalid-pair-request" }); }
        });

        app.MapGet("/api/v1/auth/challenge", (HttpContext ctx) =>
        {
            var id = ctx.Request.Query["clientId"].ToString();
            if (id.Length is < 8 or > 96) return Results.StatusCode(403);
            string? nonce = state.IssueNonce(id);
            if (nonce is null) return Results.StatusCode(403);
            ctx.Response.Headers.CacheControl = "no-store";
            return Results.Json(new { nonce, ttlSeconds = 30 });
        });

        app.MapGet("/api/v1/status", (HttpContext ctx) =>
        {
            if (!state.Verify(ctx.Request, "/api/v1/status", [])) return Results.StatusCode(401);
            return Results.Json(new { ok = true, id = state.DeviceId,
                monitorCount = Math.Min(Desktop.Monitors().Length, 3), port = 8443, version = "0.5.0" });
        });

        app.MapPost("/api/v1/shutdown", async (HttpContext ctx) =>
        {
            using var buffer = new MemoryStream();
            await ctx.Request.Body.CopyToAsync(buffer);
            if (buffer.Length > 4096 || !state.Verify(ctx.Request, "/api/v1/shutdown", buffer.ToArray()))
                return Results.StatusCode(401);
            Process.Start(new ProcessStartInfo("shutdown.exe", "/s /t 30") { UseShellExecute = false, CreateNoWindow = true });
            return Results.Json(new { ok = true, delayedSeconds = 30 });
        });

        app.Map("/api/v1/desktop", async (HttpContext ctx) =>
        {
            if (!ctx.WebSockets.IsWebSocketRequest || !state.Verify(ctx.Request, "/api/v1/desktop", []))
            { ctx.Response.StatusCode = 401; return; }
            if (!await SessionGate.WaitAsync(0)) { ctx.Response.StatusCode = 409; return; }
            try
            {
                using var ws = await ctx.WebSockets.AcceptWebSocketAsync();
                Console.WriteLine("SESSION:connected");
                try { await RunDesktop(ws, ctx.RequestAborted); }
                finally
                {
                    if (ws.State == WebSocketState.Open)
                        await ws.CloseAsync(WebSocketCloseStatus.NormalClosure, "Session closed", CancellationToken.None);
                    Console.WriteLine("SESSION:disconnected");
                }
            }
            catch (OperationCanceledException) { }
            finally { SessionGate.Release(); }
        });

        var console = Task.Run(() =>
        {
            // WPF writes commands to the same standard input; no external public API needed.
            while (true)
            {
                string? command = Console.ReadLine();
                if (command is null) break;
                command = command.Trim();
                if (command == "quit") { _ = app.StopAsync(); break; }
                if (command == "clients") { Console.WriteLine("CLIENTS:" + state.ListClients()); continue; }
                if (command.StartsWith("revoke ", StringComparison.Ordinal))
                {
                    Console.WriteLine(state.Revoke(command[7..].Trim()) ? "REVOKED" : "NOT_FOUND");
                    continue;
                }
                if (command == "pair")
                {
                    string? ip = Console.ReadLine();
                    if (ip is null) break;
                    try
                    {
                        var result = state.BeginPair(ip.Trim());
                        Console.WriteLine("QR: " + result.PngPath);
                        Console.WriteLine("Alternative manual pairing JSON: " + result.Json);
                    }
                    catch (Exception e) { Console.WriteLine("PAIR_ERROR:" + e.Message); }
                }
            }
        });
        await app.StartAsync();
        Console.WriteLine("Running in interactive Windows session on TCP 8443.");
        Console.WriteLine("Commands: pair, clients, revoke <clientId>, quit");
        await app.WaitForShutdownAsync();
        // Console.ReadLine may still be waiting in console mode, expected.
    }

static async Task RunDesktop(WebSocket ws, CancellationToken cancel)
{
    int monitor = 0;
    int requestedFps = 60;
    int jpegQuality = 62;
    int gpuBitrateKbps = 6000;
    int lowImpact = 1; // Default: protect the game/PC workload.
    int preferHardware = 0;
    int configGeneration = 0;
    int gpuUnavailable = 0;
    int framesSent = 0;
    int audioEnabled = 0; // Explicit opt-in from authenticated Android session.
    int lastReportedMonitorCount = -1;
    CancellationTokenSource? currentAudio = null;
    string activeCodec = "jpeg";
    string gpuEncoder = Environment.GetEnvironmentVariable("RCP_GPU_ENCODER") ?? "h264_nvenc";
    using var sendLock = new SemaphoreSlim(1, 1);
    using var end = CancellationTokenSource.CreateLinkedTokenSource(cancel);
    CancellationTokenSource? currentGpu = null;

    async Task SendTextAsync(object data)
    {
        if (ws.State != WebSocketState.Open || end.IsCancellationRequested) return;
        byte[] payload = JsonSerializer.SerializeToUtf8Bytes(data);
        await sendLock.WaitAsync(end.Token);
        try
        {
            if (ws.State == WebSocketState.Open)
                await ws.SendAsync(new ArraySegment<byte>(payload), WebSocketMessageType.Text, true, end.Token);
        }
        finally { sendLock.Release(); }
    }

    async Task SendBinaryAsync(byte[] frame, CancellationToken ct)
    {
        await sendLock.WaitAsync(ct);
        try
        {
            if (ws.State == WebSocketState.Open)
                await ws.SendAsync(new ArraySegment<byte>(frame), WebSocketMessageType.Binary, true, ct);
        }
        finally { sendLock.Release(); }
    }

    // Monitor order follows Screen.AllScreens, supporting up to three outputs in UI.
    // Enumeration also protects against selecting a disconnected output.
    async Task SendMonitorListAsync()
    {
        var screens = Desktop.Monitors();
        int available = Math.Min(3, screens.Length);
        var entries = screens.Take(3).Select((screen, index) => new
        {
            index, name = screen.DeviceName, width = screen.Bounds.Width,
            height = screen.Bounds.Height, primary = screen.Primary
        }).ToArray();
        await SendTextAsync(new { type = "monitor-list", count = available,
            selected = Volatile.Read(ref monitor), screens = entries });
        lastReportedMonitorCount = available;
    }

    // Only capture while an authenticated desktop session is running.
    var sender = Task.Run(async () =>
    {
        while (!end.IsCancellationRequested && ws.State == WebSocketState.Open)
        {
            if (Volatile.Read(ref preferHardware) == 1 && Volatile.Read(ref gpuUnavailable) == 0 &&
                HardwareH264Capture.IsConfigured)
            {
                int generation = Volatile.Read(ref configGeneration);
                using var gpuCts = CancellationTokenSource.CreateLinkedTokenSource(end.Token);
                Volatile.Write(ref currentGpu, gpuCts);
                try
                {
                    activeCodec = "h264";
                    await SendTextAsync(new { type = "video-format", codec = "h264", payload = "RCH3+AnnexB",
                        note = "hardware-decoder-required" });
                    int effectiveFps = Volatile.Read(ref lowImpact) == 1
                        ? Math.Min(60, Volatile.Read(ref requestedFps))
                        : Volatile.Read(ref requestedFps);
                    int effectiveBitrate = Volatile.Read(ref lowImpact) == 1
                        ? Math.Min(4500, Volatile.Read(ref gpuBitrateKbps))
                        : Volatile.Read(ref gpuBitrateKbps);
                    await HardwareH264Capture.StreamAsync(Volatile.Read(ref monitor),
                        effectiveFps, effectiveBitrate, gpuEncoder,
                        async (accessUnit, token) =>
                        {
                            if (generation != Volatile.Read(ref configGeneration)) return;
                            var packet = new byte[4 + accessUnit.Length];
                            packet[0] = (byte)'R'; packet[1] = (byte)'C'; packet[2] = (byte)'H'; packet[3] = (byte)'3';
                            Buffer.BlockCopy(accessUnit, 0, packet, 4, accessUnit.Length);
                            await SendBinaryAsync(packet, token);
                            Interlocked.Increment(ref framesSent);
                        }, gpuCts.Token);
                }
                catch (OperationCanceledException) when (gpuCts.IsCancellationRequested || end.IsCancellationRequested) { }
                catch (Exception ex)
                {
                    Console.WriteLine("GPUVIDEO:fallback " + ex.GetType().Name);
                    Volatile.Write(ref gpuUnavailable, 1);
                    Volatile.Write(ref preferHardware, 0);
                    try { await SendTextAsync(new { type = "video-fallback", codec = "jpeg", reason = "encoder-unavailable" }); }
                    catch (OperationCanceledException) { }
                }
                finally
                {
                    if (ReferenceEquals(Volatile.Read(ref currentGpu), gpuCts))
                        Volatile.Write(ref currentGpu, null);
                }
                if (end.IsCancellationRequested) break;
                continue;
            }

            // Safe fallback: GDI+ JPEG is capped at 30 FPS; never spin at 144 FPS on CPU.
            if (activeCodec != "jpeg")
            {
                activeCodec = "jpeg";
                try { await SendTextAsync(new { type = "video-format", codec = "jpeg" }); }
                catch (OperationCanceledException) { break; }
            }
            var started = Stopwatch.GetTimestamp();
            try
            {
                int fps = Math.Clamp(Volatile.Read(ref requestedFps), 10, 30);
                int frameGeneration = Volatile.Read(ref configGeneration);
                byte[] jpeg = Desktop.CaptureJpeg(Volatile.Read(ref monitor), Volatile.Read(ref jpegQuality));
                // Do not send an old display frame after monitor selection.
                if (frameGeneration != Volatile.Read(ref configGeneration)) continue;
                if (jpeg.Length > 0 && jpeg.Length < 2_000_000)
                {
                    await SendBinaryAsync(jpeg, end.Token);
                    Interlocked.Increment(ref framesSent);
                }
                int frameDelay = Math.Max(1, (int)(1000.0 / fps - Stopwatch.GetElapsedTime(started).TotalMilliseconds));
                await Task.Delay(frameDelay, end.Token);
            }
            catch (OperationCanceledException) { break; }
            catch (Exception ex)
            {
                Console.WriteLine("Capture error: " + ex.GetType().Name);
                try { await Task.Delay(500, end.Token); } catch (OperationCanceledException) { break; }
            }
        }
    }, end.Token);

    // Audio is captured from the default playback endpoint (never the microphone).
    // Keep it off the WebSocket receive thread; a bounded queue drops stale samples.
    var audioSender = Task.Run(async () =>
    {
        while (!end.IsCancellationRequested && ws.State == WebSocketState.Open)
        {
            if (Volatile.Read(ref audioEnabled) == 0)
            {
                try { await Task.Delay(200, end.Token); } catch (OperationCanceledException) { break; }
                continue;
            }
            using var audioCts = CancellationTokenSource.CreateLinkedTokenSource(end.Token);
            Volatile.Write(ref currentAudio, audioCts);
            try
            {
                await WasapiAudioStreamer.StreamAsync(SendTextAsync, SendBinaryAsync, audioCts.Token);
            }
            catch (OperationCanceledException) when (audioCts.IsCancellationRequested) { }
            catch (Exception ex)
            {
                Console.WriteLine("AUDIO:unavailable " + ex.GetType().Name);
                Volatile.Write(ref audioEnabled, 0);
                try { await SendTextAsync(new { type = "audio-unavailable", reason = ex.GetType().Name }); }
                catch (OperationCanceledException) { }
            }
            finally
            {
                if (ReferenceEquals(Volatile.Read(ref currentAudio), audioCts))
                    Volatile.Write(ref currentAudio, null);
            }
            if (!end.IsCancellationRequested)
            {
                try { await Task.Delay(150, end.Token); } catch (OperationCanceledException) { break; }
            }
        }
    }, end.Token);

    var telemetry = Task.Run(async () =>
    {
        while (!end.IsCancellationRequested && ws.State == WebSocketState.Open)
        {
            try
            {
                await Task.Delay(1000, end.Token);
                int actual = Interlocked.Exchange(ref framesSent, 0);
                int limit = Volatile.Read(ref requestedFps);
                Console.WriteLine($"STATS:{actual};{limit};{Volatile.Read(ref jpegQuality)}");
                await SendTextAsync(new { type = "telemetry", sentFps = actual,
                    requestedFps = limit, effectiveFpsCap = activeCodec == "jpeg" ? Math.Min(30, limit)
                        : Volatile.Read(ref lowImpact) == 1 ? Math.Min(60, limit) : limit,
                    lowImpact = Volatile.Read(ref lowImpact) == 1, codec = activeCodec, jpegQuality = Volatile.Read(ref jpegQuality) });
            }
            catch (OperationCanceledException) { break; }
            catch (WebSocketException) { break; }
        }
    }, end.Token);

    var buffer = new byte[4096];
    try
    {
        await SendTextAsync(new { type = "session-info", codec = "jpeg", maxSelectableFps = 144,
            hardwareCodecAvailable = HardwareH264Capture.IsConfigured,
            gamepad = "virtual-xinput-optional", audio = "wasapi-pcm16" });
        await SendMonitorListAsync();
        while (ws.State == WebSocketState.Open && !end.IsCancellationRequested)
        {
            var result = await ws.ReceiveAsync(new ArraySegment<byte>(buffer), end.Token);
            if (result.MessageType == WebSocketMessageType.Close) break;
            if (result.MessageType != WebSocketMessageType.Text || !result.EndOfMessage || result.Count > buffer.Length) break;
            try
            {
                using var doc = JsonDocument.Parse(buffer.AsMemory(0, result.Count));
                if (doc.RootElement.ValueKind != JsonValueKind.Object) continue;
                var data = doc.RootElement;
                string type = data.TryGetProperty("type", out var tag) && tag.ValueKind == JsonValueKind.String
                    ? tag.GetString() ?? "" : "";
                switch (type)
                {
                    case "stream-config":
                    {
                        int requested = data.TryGetProperty("fps", out var fps) && fps.TryGetInt32(out int v) ? v : 60;
                        int quality = data.TryGetProperty("quality", out var q) && q.TryGetInt32(out int parsed) ? parsed : 62;
                        int bitrate = data.TryGetProperty("bitrateKbps", out var b) && b.TryGetInt32(out int kbps) ? kbps : 6000;
                        bool useHardware = data.TryGetProperty("codec", out var codec) &&
                            codec.ValueKind == JsonValueKind.String && codec.GetString() == "h264-hw";
                        int newFps = requested == 0 ? 60 : Math.Clamp(requested, 15, 144);
                        int newBitrate = Math.Clamp(bitrate, 1000, 20000);
                        bool prioritizePc = !data.TryGetProperty("lowImpact", out var impact) ||
                            impact.ValueKind != JsonValueKind.False;
                        int newLowImpact = prioritizePc ? 1 : 0;
                        int newHardware = useHardware ? 1 : 0;
                        // JPEG quality changes never restart the GPU encoder.
                        bool restartHardware = newFps != Volatile.Read(ref requestedFps) ||
                            newBitrate != Volatile.Read(ref gpuBitrateKbps) ||
                            newLowImpact != Volatile.Read(ref lowImpact) ||
                            newHardware != Volatile.Read(ref preferHardware);
                        Volatile.Write(ref requestedFps, newFps);
                        Volatile.Write(ref jpegQuality, Math.Clamp(quality, 35, 85));
                        Volatile.Write(ref gpuBitrateKbps, newBitrate);
                        Volatile.Write(ref lowImpact, newLowImpact);
                        Volatile.Write(ref preferHardware, newHardware);
                        if (restartHardware)
                        {
                            Interlocked.Increment(ref configGeneration);
                            Volatile.Read(ref currentGpu)?.Cancel();
                        }
                        break;
                    }
                    case "ping":
                    {
                        if (data.TryGetProperty("id", out var pingId) && pingId.TryGetInt64(out long id))
                            await SendTextAsync(new { type = "pong", id });
                        // A hot-plugged or removed display must also update Android.
                        int available = Math.Min(3, Desktop.Monitors().Length);
                        if (available != lastReportedMonitorCount)
                        {
                            if (Volatile.Read(ref monitor) >= available)
                            {
                                Volatile.Write(ref monitor, 0);
                                Desktop.ReleaseButtons();
                                Interlocked.Increment(ref configGeneration);
                                Volatile.Read(ref currentGpu)?.Cancel();
                            }
                            await SendMonitorListAsync();
                        }
                        break;
                    }
                    case "monitor":
                    {
                        if (!data.TryGetProperty("index", out var requestedIndex) ||
                            !requestedIndex.TryGetInt32(out int index)) break;
                        int available = Math.Min(3, Desktop.Monitors().Length);
                        if (index < 0 || index >= available)
                        {
                            // Reject invalid selection and resend actual availability.
                            await SendMonitorListAsync();
                            break;
                        }
                        if (index != Volatile.Read(ref monitor))
                        {
                            Desktop.ReleaseButtons();
                            Volatile.Write(ref monitor, index);
                            Desktop.EnsureCursorOnMonitor(index);
                            // Restart GPU capture on monitor changes. JPEG reads the
                            // new monitor index automatically on its next frame.
                            Interlocked.Increment(ref configGeneration);
                            Volatile.Read(ref currentGpu)?.Cancel();
                        }
                        await SendMonitorListAsync();
                        break;
                    }
                    case "audio-config":
                    {
                        if (!data.TryGetProperty("enabled", out var enabled) ||
                            enabled.ValueKind is not (JsonValueKind.True or JsonValueKind.False)) break;
                        bool on = enabled.GetBoolean();
                        Volatile.Write(ref audioEnabled, on ? 1 : 0);
                        Console.WriteLine(on ? "AUDIO:solicitado" : "AUDIO:inactivo");
                        if (!on) Volatile.Read(ref currentAudio)?.Cancel();
                        break;
                    }
                    case "pc-key":
                        PcKeyboardBridge.Handle(data);
                        break;
                    case "pc-key-release-all":
                        PcKeyboardBridge.ReleaseAll();
                        break;
                    case "gamepad":
                        GamepadKeyboardBridge.Handle(data);
                        break;
                    case "gamepad-neutral":
                        GamepadKeyboardBridge.ReleaseAll();
                        break;
                    default:
                        Desktop.HandleEvent(data, Volatile.Read(ref monitor));
                        break;
                }
            }
            catch (Exception ex) { Console.WriteLine("Invalid control message: " + ex.GetType().Name); }
        }
    }
    catch (OperationCanceledException) { }
    catch (WebSocketException) { }
    finally
    {
        Desktop.ReleaseButtons();
        GamepadKeyboardBridge.ReleaseAll();
        PcKeyboardBridge.ReleaseAll();
        end.Cancel();
        Volatile.Read(ref currentAudio)?.Cancel();
        Volatile.Read(ref currentGpu)?.Cancel();
        try { await sender; } catch (OperationCanceledException) { }
        try { await telemetry; } catch (OperationCanceledException) { }
        try { await audioSender; } catch (OperationCanceledException) { }
        VirtualGamepadBridge.Disconnect();
    }
}

}
