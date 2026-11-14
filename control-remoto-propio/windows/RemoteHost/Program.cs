using System.Diagnostics;
using System.Net;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using RemoteHost;

const int port = 8443;
var identity = new AppState();
var builder = WebApplication.CreateBuilder(args);
builder.WebHost.ConfigureKestrel(server =>
    server.ListenAnyIP(port, listen => listen.UseHttps(identity.Certificate)));
var app = builder.Build();
var attemptLimit = new ConnectionAttemptLimiter();
var activeDesktop = new SemaphoreSlim(1, 1);
app.UseWebSockets(new WebSocketOptions { KeepAliveInterval = TimeSpan.FromSeconds(20) });

// Pair requests must be from the LAN and are additionally protected by a one-time
// token issued only after the operator types 'pair' at the host's local console.
app.MapPost("/api/v1/pair", async (HttpContext context) =>
{
    if (!IsLocalClient(context.Connection.RemoteIpAddress)) return Results.StatusCode(403);
    if (!attemptLimit.Allow(context.Connection.RemoteIpAddress, "pair", 10, TimeSpan.FromMinutes(1)))
        return Results.StatusCode(429);
    var body = await ReadLimited(context.Request, 4096);
    if (body is null) return Results.BadRequest();
    ClientRegistration? registration;
    try { registration = JsonSerializer.Deserialize<ClientRegistration>(body,
        new JsonSerializerOptions { PropertyNameCaseInsensitive = true }); }
    catch { return Results.BadRequest(); }
    if (registration is null || string.IsNullOrEmpty(registration.Token) ||
        string.IsNullOrEmpty(registration.ClientId) ||
        string.IsNullOrEmpty(registration.PublicKeyBase64)) return Results.BadRequest();
    if (!identity.TryPair(registration, out string reason)) return Results.BadRequest(new { error = reason });
    Console.WriteLine("Paired: " + registration.ClientId);
    return Results.Ok(new { paired = true, deviceId = identity.DeviceId });
});

app.MapGet("/api/v1/auth/challenge", (HttpContext context, string? clientId) =>
{
    if (!attemptLimit.Allow(context.Connection.RemoteIpAddress, "challenge", 120, TimeSpan.FromMinutes(1)))
        return Results.StatusCode(429);
    if (string.IsNullOrEmpty(clientId)) return Results.BadRequest();
    var challenge = identity.CreateChallenge(clientId);
    return challenge is null ? Results.Unauthorized() : Results.Ok(challenge);
});

app.MapGet("/api/v1/status", async (HttpContext context) =>
{
    if (!identity.Authorize(ReadHeader(context, "X-Client-Id"), ReadHeader(context, "X-Nonce"),
        ReadHeader(context, "X-Signature"), "GET", "/api/v1/status", []))
        return Results.Unauthorized();
    return Results.Ok(new { online = true, deviceId = identity.DeviceId,
        interactiveDesktop = Environment.UserInteractive, monitors = Desktop.Monitors().Length });
});

app.MapPost("/api/v1/shutdown", async (HttpContext context) =>
{
    byte[]? bytes = await ReadLimited(context.Request, 2048);
    if (bytes is null) return Results.BadRequest();
    if (!identity.Authorize(ReadHeader(context, "X-Client-Id"), ReadHeader(context, "X-Nonce"),
        ReadHeader(context, "X-Signature"), "POST", "/api/v1/shutdown", bytes))
        return Results.Unauthorized();
    try
    {
        using var doc = JsonDocument.Parse(bytes);
        if (!doc.RootElement.TryGetProperty("confirm", out var confirmed) ||
            confirmed.ValueKind != JsonValueKind.True) return Results.BadRequest();
    }
    catch { return Results.BadRequest(); }
    // Standard Windows shutdown with a short cancellation window.
    var process = Process.Start(new ProcessStartInfo("shutdown.exe", "/s /t 30")
        { UseShellExecute = false, CreateNoWindow = true });
    return Results.Ok(new { accepted = process is not null, delaySeconds = 30 });
});

// The WebSocket is authenticated BEFORE it is upgraded. No public screen/status API.
app.MapGet("/api/v1/desktop", async (HttpContext context) =>
{
    if (!context.WebSockets.IsWebSocketRequest) { context.Response.StatusCode = 400; return; }
    if (!identity.Authorize(ReadHeader(context, "X-Client-Id"), ReadHeader(context, "X-Nonce"),
        ReadHeader(context, "X-Signature"), "GET", "/api/v1/desktop", []))
    { context.Response.StatusCode = 401; return; }
    if (!await activeDesktop.WaitAsync(0, context.RequestAborted))
    { context.Response.StatusCode = 429; return; }
    try
    {
        using var ws = await context.WebSockets.AcceptWebSocketAsync();
        await RunDesktop(ws, context.RequestAborted);
    }
    finally { activeDesktop.Release(); }
});

Console.WriteLine("RemoteHost: " + identity.DeviceId);
Console.WriteLine("TLS SPKI pin: " + identity.Pin);
Console.WriteLine("Running in interactive Windows session on TCP 8443.");
Console.WriteLine("Commands: pair, clients, revoke <clientId>, quit");
var cts = new CancellationTokenSource();
var consoleTask = Task.Run(() =>
{
    while (!cts.IsCancellationRequested)
    {
        string? command = Console.ReadLine();
        if (command is null) break;
        if (command.Trim() == "pair")
        {
            Console.Write("LAN IPv4 of this PC (e.g. 192.168.1.100): ");
            string ip = Console.ReadLine()?.Trim() ?? "";
            if (!IPAddress.TryParse(ip, out var address) ||
                address.AddressFamily != System.Net.Sockets.AddressFamily.InterNetwork ||
                !IsPrivateIPv4(address)) { Console.WriteLine("Enter a private LAN IPv4."); continue; }
            AppState.SavePairingQr(identity.BeginPairing(ip, port));
            Console.WriteLine("Pairing open for 3 minutes; do not share the QR over the Internet.");
        }
        else if (command.Trim() == "clients")
            Console.WriteLine(string.Join(", ", identity.PairedClients()));
        else if (command.StartsWith("revoke ", StringComparison.Ordinal))
            Console.WriteLine(identity.Revoke(command[7..].Trim()) ? "Revoked" : "Not found");
        else if (command.Trim() == "quit") { cts.Cancel(); _ = app.StopAsync(); break; }
    }
});
await app.RunAsync();
cts.Cancel();

static string ReadHeader(HttpContext ctx, string name) => ctx.Request.Headers[name].ToString();
static bool IsPrivateIPv4(IPAddress ip)
{
    var b = ip.GetAddressBytes();
    return b.Length == 4 && (b[0] == 10 || b[0] == 192 && b[1] == 168 ||
        b[0] == 172 && b[1] is >= 16 and <= 31);
}
static bool IsLocalClient(IPAddress? ip)
{
    if (ip is null) return false;
    if (IPAddress.IsLoopback(ip)) return true;
    if (ip.IsIPv4MappedToIPv6) ip = ip.MapToIPv4();
    return IsPrivateIPv4(ip);
}
static async Task<byte[]?> ReadLimited(HttpRequest request, int max)
{
    if (request.ContentLength > max) return null;
    using var ms = new MemoryStream();
    byte[] buffer = new byte[1024];
    while (true)
    {
        int n = await request.Body.ReadAsync(buffer);
        if (n == 0) return ms.ToArray();
        if (ms.Length + n > max) return null;
        ms.Write(buffer, 0, n);
    }
}
static async Task RunDesktop(WebSocket ws, CancellationToken cancel)
{
    int monitor = 0;
    var sendLock = new SemaphoreSlim(1, 1);
    var end = CancellationTokenSource.CreateLinkedTokenSource(cancel);
    var sender = Task.Run(async () =>
    {
        while (!end.IsCancellationRequested && ws.State == WebSocketState.Open)
        {
            try
            {
                // JPEG frames, v1 baseline: 5 fps and bounded output per frame.
                byte[] frame = Desktop.CaptureJpeg(Volatile.Read(ref monitor));
                if (frame.Length < 2_000_000)
                {
                    await sendLock.WaitAsync(end.Token);
                    try { await ws.SendAsync(new ArraySegment<byte>(frame), WebSocketMessageType.Binary,
                        true, end.Token); }
                    finally { sendLock.Release(); }
                }
                await Task.Delay(200, end.Token);
            }
            catch (OperationCanceledException) { break; }
            catch (Exception ex)
            {
                Console.WriteLine("Capture error: " + ex.GetType().Name);
                await Task.Delay(1000, end.Token);
            }
        }
    }, end.Token);
    var buffer = new byte[4096];
    try
    {
        while (ws.State == WebSocketState.Open && !end.IsCancellationRequested)
        {
            var result = await ws.ReceiveAsync(new ArraySegment<byte>(buffer), end.Token);
            if (result.MessageType == WebSocketMessageType.Close) break;
            if (result.MessageType != WebSocketMessageType.Text || !result.EndOfMessage ||
                result.Count > 4096) break;
            try
            {
                using var doc = JsonDocument.Parse(buffer.AsMemory(0, result.Count));
                if (doc.RootElement.ValueKind != JsonValueKind.Object) continue;
                Desktop.HandleEvent(doc.RootElement, ref monitor);
            }
            catch (Exception ex) { Console.WriteLine("Invalid control message: " + ex.GetType().Name); }
        }
    }
    catch (OperationCanceledException) { }
    catch (WebSocketException) { }
    finally
    {
        Desktop.ReleaseButtons();
        end.Cancel();
        try { await sender; } catch (OperationCanceledException) { }
        if (ws.State == WebSocketState.Open)
            await ws.CloseAsync(WebSocketCloseStatus.NormalClosure, "Session ended", CancellationToken.None);
        end.Dispose();
        sendLock.Dispose();
    }
}

public sealed class ConnectionAttemptLimiter
{
    private readonly System.Collections.Concurrent.ConcurrentDictionary<string, (DateTimeOffset Started, int Count)>
        _entries = new();
    public bool Allow(IPAddress? address, string path, int maximum, TimeSpan window)
    {
        string key = (address?.ToString() ?? "missing") + ":" + path;
        DateTimeOffset now = DateTimeOffset.UtcNow;
        var state = _entries.AddOrUpdate(key, _ => (now, 1), (_, prior) =>
            now - prior.Started > window ? (now, 1) : (prior.Started, prior.Count + 1));
        return state.Count <= maximum;
    }
}
