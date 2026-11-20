using System.Diagnostics;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using System.Text.Json;
using System.Windows.Forms;
using System.Windows.Forms.VisualStyles;

namespace RemoteHost;

/// <summary>Visible desktop capture and interactive-session Win32 input. Not Windows login/UAC desktop.</summary>
public static class Desktop
{
    private static bool leftHeld, rightHeld;
    public static Screen[] Monitors() => Screen.AllScreens;

    public static byte[] CaptureJpeg(int index, int quality)
    {
        var screens = Monitors();
        if (screens.Length == 0) return [];
        var bounds = screens[Math.Clamp(index, 0, screens.Length - 1)].Bounds;
        using var image = new Bitmap(bounds.Width, bounds.Height, PixelFormat.Format24bppRgb);
        using (var g = Graphics.FromImage(image))
            g.CopyFromScreen(bounds.Left, bounds.Top, 0, 0, bounds.Size, CopyPixelOperation.SourceCopy);
        using var output = new MemoryStream();
        var encoder = ImageCodecInfo.GetImageEncoders().First(x => x.MimeType == "image/jpeg");
        using var p = new EncoderParameters(1);
        p.Param[0] = new EncoderParameter(System.Drawing.Imaging.Encoder.Quality, (long)Math.Clamp(quality, 30, 90));
        image.Save(output, encoder, p);
        return output.ToArray();
    }

    public static void EnsureCursorOnMonitor(int index)
    {
        var screens = Monitors();
        if (index < 0 || index >= screens.Length) return;
        var rect = screens[index].Bounds;
        if (!GetCursorPos(out var point) || !rect.Contains(point.X, point.Y))
            SetCursorPos(rect.Left + rect.Width / 2, rect.Top + rect.Height / 2);
    }

    public static void MoveRelative(int dx, int dy, int monitor)
    {
        EnsureCursorOnMonitor(monitor);
        MouseInput(Math.Clamp(dx, -160, 160), Math.Clamp(dy, -160, 160), 0x0001);
    }

    private static void MouseInput(int x, int y, uint flags, uint mouseData = 0)
    {
        var input = new INPUT { Type = 0, Union = new INPUTUNION { Mouse = new MOUSEINPUT { dx = x, dy = y, dwFlags = flags, mouseData = mouseData } } };
        SendInput(1, [input], Marshal.SizeOf<INPUT>());
    }

    private static void SetPosition(JsonElement data, int monitor)
    {
        if (!data.TryGetProperty("x", out var xx) || !data.TryGetProperty("y", out var yy) ||
            !xx.TryGetDouble(out var x) || !yy.TryGetDouble(out var y) ||
            !double.IsFinite(x) || !double.IsFinite(y)) return;
        var screens = Monitors();
        if (monitor < 0 || monitor >= screens.Length) return;
        var r = screens[monitor].Bounds;
        SetCursorPos(r.Left + (int)Math.Round(Math.Clamp(x, 0, 1) * Math.Max(0, r.Width - 1)),
                     r.Top + (int)Math.Round(Math.Clamp(y, 0, 1) * Math.Max(0, r.Height - 1)));
    }

    private static void Button(string which, bool pressed)
    {
        if (which == "left")
        {
            if (pressed == leftHeld) return;
            leftHeld = pressed;
            MouseInput(0, 0, pressed ? 0x0002u : 0x0004u);
        }
        else if (which == "right")
        {
            if (pressed == rightHeld) return;
            rightHeld = pressed;
            MouseInput(0, 0, pressed ? 0x0008u : 0x0010u);
        }
    }

    public static void ReleaseButtons()
    {
        Button("left", false); Button("right", false);
    }

    public static void HandleEvent(JsonElement data, int monitor)
    {
        if (!data.TryGetProperty("type", out var typeProp)) return;
        string type = typeProp.GetString() ?? "";
        switch (type)
        {
            case "move": SetPosition(data, monitor); break;
            case "mouse-relative":
                if (data.TryGetProperty("dx", out var dx) && dx.TryGetInt32(out var x) &&
                    data.TryGetProperty("dy", out var dy) && dy.TryGetInt32(out var y)) MoveRelative(x, y, monitor);
                break;
            case "click":
                SetPosition(data, monitor);
                var name = data.TryGetProperty("button", out var b) ? b.GetString() ?? "left" : "left";
                Button(name, true); Button(name, false);
                break;
            case "button":
                if (data.TryGetProperty("button", out var button) && data.TryGetProperty("action", out var action))
                    Button(button.GetString() ?? "", action.GetString() == "down");
                break;
            case "scroll":
                if (data.TryGetProperty("delta", out var d) && d.TryGetInt32(out int v))
                    MouseInput(0, 0, 0x0800, unchecked((uint)Math.Clamp(v, -1200, 1200)));
                break;
            case "shortcut":
                if (!data.TryGetProperty("name", out var shortName)) break;
                var combo = shortName.GetString() switch
                {
                    "alt-tab" => new[] { "LALT", "TAB" },
                    "win-d" => new[] { "LWIN", "D" },
                    "enter" => new[] { "ENTER" },
                    "backspace" => new[] { "BACKSPACE" },
                    _ => []
                };
                foreach (string key in combo) PcKeyboardBridge.HandleSynthetic(key, true);
                foreach (string key in combo.Reverse()) PcKeyboardBridge.HandleSynthetic(key, false);
                break;
            case "app":
                if (data.TryGetProperty("name", out var app))
                {
                    string? exe = app.GetString() switch { "notepad" => "notepad.exe", "explorer" => "explorer.exe", "calculator" => "calc.exe", _ => null };
                    if (exe is not null) try { Process.Start(new ProcessStartInfo(exe) { UseShellExecute = true }); } catch { }
                }
                break;
            case "text":
                if (data.TryGetProperty("text", out var text) && text.ValueKind == JsonValueKind.String)
                {
                    string value = text.GetString() ?? "";
                    if (value.Length <= 256) foreach (char c in value) SendUnicode(c);
                }
                break;
        }
    }

    private static void SendUnicode(char c)
    {
        var down = new INPUT { Type = 1, Union = new INPUTUNION { Keyboard = new KEYBDINPUT { Scan = c, Flags = 0x0004 } } };
        var up = new INPUT { Type = 1, Union = new INPUTUNION { Keyboard = new KEYBDINPUT { Scan = c, Flags = 0x0004 | 0x0002 } } };
        SendInput(2, [down, up], Marshal.SizeOf<INPUT>());
    }
    [StructLayout(LayoutKind.Sequential)] private struct POINT { public int X, Y; }
    [StructLayout(LayoutKind.Sequential)] private struct INPUT { public uint Type; public INPUTUNION Union; }
    [StructLayout(LayoutKind.Explicit)] private struct INPUTUNION
    {
        [FieldOffset(0)] public MOUSEINPUT Mouse;
        [FieldOffset(0)] public KEYBDINPUT Keyboard;
    }
    [StructLayout(LayoutKind.Sequential)] private struct MOUSEINPUT
    { public int dx, dy; public uint mouseData, dwFlags, time; public IntPtr extraInfo; }
    [StructLayout(LayoutKind.Sequential)] private struct KEYBDINPUT
    { public ushort Key, Scan; public uint Flags, Time; public IntPtr ExtraInfo; }
    [DllImport("user32.dll")] private static extern bool GetCursorPos(out POINT p);
    [DllImport("user32.dll")] private static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll", SetLastError = true)] private static extern uint SendInput(uint count, INPUT[] input, int cbSize);
}
