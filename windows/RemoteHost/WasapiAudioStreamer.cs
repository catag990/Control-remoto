using System.Buffers.Binary;
using System.Threading.Channels;
using NAudio.Wave;

namespace RemoteHost;

/// <summary>
/// Captures the audio currently played by the default Windows playback endpoint.
/// Does not capture microphone audio. Audio is forwarded only during an authenticated
/// session and only while the mobile user has opted in.
///
/// Wire protocol: text {type:"audio-format",codec:"pcm16",sampleRate,channels:2}
/// followed by binary "RCA3" + interleaved stereo PCM little-endian signed 16-bit.
/// This deliberately avoids a software audio encoder to minimize CPU usage; Opus
/// compression is a future enhancement to reduce mobile data consumption.
/// </summary>
public static class WasapiAudioStreamer
{
    private static readonly Guid FloatSubFormat = new("00000003-0000-0010-8000-00aa00389b71");
    private static readonly Guid PcmSubFormat = new("00000001-0000-0010-8000-00aa00389b71");

    public static async Task StreamAsync(
        Func<object, Task> sendStatus,
        Func<byte[], CancellationToken, Task> sendPacket,
        CancellationToken cancellation)
    {
        using var capture = new WasapiLoopbackCapture();
        WaveFormat format = capture.WaveFormat;
        int channels = format.Channels;
        int sampleRate = format.SampleRate;
        if (channels < 1 || channels > 8 || sampleRate < 8000 || sampleRate > 192000)
            throw new NotSupportedException("Formato WASAPI no soportado");

        bool floating = format.BitsPerSample == 32 &&
            (format.Encoding == WaveFormatEncoding.IeeeFloat ||
             format is WaveFormatExtensible extFloat && extFloat.SubFormat == FloatSubFormat);
        bool signedPcm = format.BitsPerSample == 16 &&
            (format.Encoding == WaveFormatEncoding.Pcm ||
             format is WaveFormatExtensible extPcm && extPcm.SubFormat == PcmSubFormat);
        if (!floating && !signedPcm)
            throw new NotSupportedException($"WASAPI: {format.Encoding}/{format.BitsPerSample} bits");

        // Short bounded queue. A slow mobile connection drops old audio rather
        // than accumulating seconds of delay or blocking the WASAPI callback.
        var fifo = Channel.CreateBounded<byte[]>(new BoundedChannelOptions(8)
        {
            SingleReader = true,
            SingleWriter = true,
            FullMode = BoundedChannelFullMode.DropOldest
        });

        capture.DataAvailable += (_, args) =>
        {
            int inputFrameBytes = channels * (format.BitsPerSample / 8);
            int frames = args.BytesRecorded / inputFrameBytes;
            if (frames <= 0) return;
            // Mono input is duplicated to stereo, multi-channel uses front L/R.
            // Processing is bounded by the actual audio callback length.
            var stereo = new byte[frames * 4];
            for (int frame = 0; frame < frames; frame++)
            {
                int src = frame * inputFrameBytes;
                short left = ReadSample(args.Buffer, src, floating);
                short right = channels == 1 ? left :
                    ReadSample(args.Buffer, src + (format.BitsPerSample / 8), floating);
                BinaryPrimitives.WriteInt16LittleEndian(stereo.AsSpan(frame * 4), left);
                BinaryPrimitives.WriteInt16LittleEndian(stereo.AsSpan(frame * 4 + 2), right);
            }
            fifo.Writer.TryWrite(stereo);
        };

        capture.RecordingStopped += (_, args) => fifo.Writer.TryComplete(args.Exception);
        await sendStatus(new { type = "audio-format", codec = "pcm16", sampleRate, channels = 2 });
        capture.StartRecording();
        Console.WriteLine("AUDIO:activo");
        try
        {
            await foreach (byte[] stereo in fifo.Reader.ReadAllAsync(cancellation))
            {
                if (stereo.Length == 0 || stereo.Length > 512_000) continue;
                byte[] packet = new byte[4 + stereo.Length];
                packet[0] = (byte)'R'; packet[1] = (byte)'C';
                packet[2] = (byte)'A'; packet[3] = (byte)'3';
                Buffer.BlockCopy(stereo, 0, packet, 4, stereo.Length);
                await sendPacket(packet, cancellation);
            }
        }
        finally
        {
            try { capture.StopRecording(); } catch { }
            fifo.Writer.TryComplete();
        }
    }

    private static short ReadSample(byte[] buffer, int offset, bool floating)
    {
        if (!floating)
            return BinaryPrimitives.ReadInt16LittleEndian(buffer.AsSpan(offset, 2));
        float value = BitConverter.ToSingle(buffer, offset);
        if (!float.IsFinite(value)) return 0;
        return (short)Math.Round(Math.Clamp(value, -1f, 1f) * (value < 0 ? 32768f : 32767f));
    }
}
