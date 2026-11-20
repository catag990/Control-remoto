using System.Diagnostics;
using System.Net.WebSockets;

namespace RemoteHost;

/// <summary>
/// Experimental D3D11 Desktop Duplication + GPU H.264 encoder transport.
/// Requires a reviewed local FFmpeg 7+ executable and a compatible GPU driver.
/// Captures only while a remote session is active. The stdout parser delivers
/// whole H.264 access units, delimited by AUD NALs inserted by FFmpeg.
/// </summary>
public static class HardwareH264Capture
{
    private static string? FfmpegPath()
    {
        var configured = Environment.GetEnvironmentVariable("RCP_FFMPEG_PATH");
        if (!string.IsNullOrEmpty(configured) && Path.IsPathFullyQualified(configured) && File.Exists(configured))
            return configured;
        string bundled = Path.Combine(AppContext.BaseDirectory, "ffmpeg.exe");
        return File.Exists(bundled) ? bundled : null;
    }

    public static bool IsConfigured => FfmpegPath() is not null;

    public static async Task StreamAsync(
        int monitor, int fps, int kbps, string encoder,
        Func<byte[], CancellationToken, Task> sendAccessUnit,
        CancellationToken cancellation)
    {
        string exe = FfmpegPath() ?? throw new FileNotFoundException("ffmpeg.exe no configurado");
        if (encoder is not ("h264_nvenc" or "h264_amf"))
            throw new NotSupportedException("Codificador no habilitado: " + encoder);
        fps = Math.Clamp(fps, 15, 144);
        kbps = Math.Clamp(kbps, 1000, 20000);
        monitor = Math.Clamp(monitor, 0, 7);
        var start = new ProcessStartInfo(exe)
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            RedirectStandardInput = true
        };
        var args = start.ArgumentList;
        foreach (string arg in new[] { "-hide_banner", "-nostdin", "-loglevel", "error", "-f", "lavfi",
            "-i", $"ddagrab=output_idx={monitor}:framerate={fps}:dup_frames=0", "-an", "-c:v", encoder })
            args.Add(arg);
        if (encoder == "h264_nvenc")
            foreach (var arg in new[] { "-preset", "p1", "-tune", "ll", "-rc", "cbr" }) args.Add(arg);
        else
            foreach (var arg in new[] { "-quality", "speed", "-usage", "ultralowlatency" }) args.Add(arg);
        foreach (var arg in new[] { "-b:v", $"{kbps}k", "-maxrate", $"{kbps}k", "-bufsize", $"{Math.Max(kbps/2,500)}k",
            "-g", fps.ToString(), "-bf", "0", "-bsf:v", "h264_metadata=aud=insert", "-f", "h264", "pipe:1" }) args.Add(arg);

        using var process = new Process { StartInfo = start };
        if (!process.Start()) throw new IOException("FFmpeg no inicio");
        // Never let stderr fill its OS pipe: that would block the encoder process.
        Task stderr = Task.Run(async () =>
        {
            try
            {
                string error = await process.StandardError.ReadToEndAsync(cancellation);
                if (!string.IsNullOrWhiteSpace(error)) Console.WriteLine("GPUVIDEO:" + error[..Math.Min(300,error.Length)].Replace('\n',' '));
            }
            catch (OperationCanceledException) { }
        }, CancellationToken.None);

        try
        {
            var reader = new AnnexBAccessUnitReader(sendAccessUnit);
            var input = process.StandardOutput.BaseStream;
            var bytes = new byte[16 * 1024];
            while (!cancellation.IsCancellationRequested)
            {
                int n = await input.ReadAsync(bytes.AsMemory(), cancellation);
                if (n <= 0) break;
                await reader.AddAsync(bytes.AsMemory(0, n), cancellation);
            }
            await reader.FlushAsync(cancellation);
            if (!cancellation.IsCancellationRequested)
                throw new IOException("Codificador de hardware finalizo. Codigo: " + (process.HasExited ? process.ExitCode : -1));
        }
        finally
        {
            if (!process.HasExited)
            {
                try { process.Kill(entireProcessTree: true); } catch (InvalidOperationException) { }
            }
            try { await stderr.WaitAsync(TimeSpan.FromSeconds(1)); } catch { }
            try { await process.WaitForExitAsync(CancellationToken.None).WaitAsync(TimeSpan.FromSeconds(2)); } catch { }
        }
    }
}

/// <summary>
/// Streaming Annex B parser: waits for AUD (NAL type 9), so each outbound WSS
/// packet contains exactly one access unit. Maximum packet size 2 MiB.
/// Works across arbitrary stdout read boundaries (including split start codes).
/// </summary>
public sealed class AnnexBAccessUnitReader
{
    private readonly List<byte> pending = new(128 * 1024);
    private readonly Func<byte[], CancellationToken, Task> send;
    private bool seenAud;
    public AnnexBAccessUnitReader(Func<byte[], CancellationToken, Task> callback) => send = callback;

    public async Task AddAsync(ReadOnlyMemory<byte> bytes, CancellationToken cancel)
    {
        pending.AddRange(bytes.ToArray());
        while (true)
        {
            int first = FindAud(0);
            if (first < 0) break;
            if (!seenAud)
            {
                // Keep SPS/PPS that may precede the first AUD.
                seenAud = true;
            }
            int next = FindAud(first + 4);
            if (next < 0) break;
            if (next > 0)
            {
                var au = pending.GetRange(0, next).ToArray();
                if (au.Length <= 2 * 1024 * 1024) await send(au, cancel);
                pending.RemoveRange(0, next);
            }
        }
        if (pending.Count > 4 * 1024 * 1024)
            throw new InvalidDataException("Flujo H.264 sin AUD o unidad demasiado grande");
    }

    public async Task FlushAsync(CancellationToken cancel)
    {
        if (pending.Count > 0 && pending.Count <= 2 * 1024 * 1024 && seenAud)
            await send(pending.ToArray(), cancel);
        pending.Clear();
    }

    private int FindAud(int from)
    {
        for (int i = from; i + 4 < pending.Count; i++)
        {
            if (pending[i] != 0 || pending[i + 1] != 0) continue;
            int nalAt = -1;
            if (pending[i + 2] == 1) nalAt = i + 3;
            else if (i + 4 < pending.Count && pending[i + 2] == 0 && pending[i + 3] == 1) nalAt = i + 4;
            if (nalAt >= 0 && (pending[nalAt] & 0x1f) == 9) return i;
        }
        return -1;
    }
}
