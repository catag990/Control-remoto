using System.Diagnostics;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using System.Text.Json;
using System.Windows.Forms;

namespace RemoteHost;

public static class Desktop
{
    public static Screen[] Monitors() => Screen.AllScreens;

    public static byte[] CaptureJpeg(int index, long quality = 62)
    {
        var screens = Monitors();
        if (index < 0 || index >= screens.Length) index = 0;
        var bounds = screens[index].Bounds;
        using var image = new Bitmap(bounds.Width, bounds.Height, PixelFormat.Format24bppRgb);
        using (var graphics = Graphics.FromImage(image))
            graphics.CopyFromScreen(bounds.Left, bounds.Top, 0, 0, image.Size,
                CopyPixelOperation.SourceCopy);
        using var stream = new MemoryStream();
        var encoder = ImageCodecInfo.GetImageEncoders().First(x => x.MimeType == "image/jpeg");
        using var parameters = new EncoderParameters(1);
        parameters.Param[0] = new EncoderParameter(System.Drawing.Imaging.Encoder.Quality, quality);
        image.Save(stream, encoder, parameters);
        return stream.ToArray();
    }

    public static void HandleEvent(JsonElement data, ref int monitor)
    {
        if (!data.TryGetProperty("type", out var kind)) return;
        var type = kind.GetString();
        if (type == "monitor")
        {
            if (data.TryGetProperty("index", out var target) && target.TryGetInt32(out int i) &&
                i >= 0 && i < Monitors().Length) monitor = i;
            return;
        }
        if (type == "move" || type == "click")
        {
            double x = data.GetProperty("x").GetDouble();
            double y = data.GetProperty("y").GetDouble();
            if (!double.IsFinite(x) || !double.IsFinite(y)) return;
            var screens = Monitors();
            if (monitor < 0 || monitor >= screens.Length) monitor = 0;
            Rectangle bounds = screens[monitor].Bounds;
            int absX = bounds.Left + (int)(Math.Clamp(x, 0, 1) * (bounds.Width - 1));
            int absY = bounds.Top + (int)(Math.Clamp(y, 0, 1) * (bounds.Height - 1));
            SetCursorPos(absX, absY);
            if (type == "click")
            {
                bool right = data.TryGetProperty("button", out var b) && b.GetString() == "right";
                Mouse(right ? MOUSE_RIGHTDOWN : MOUSE_LEFTDOWN, 0);
                Mouse(right ? MOUSE_RIGHTUP : MOUSE_LEFTUP, 0);
            }
        }
        else if (type == "button")
        {
            string action = data.GetProperty("action").GetString() ?? "";
            string button = data.GetProperty("button").GetString() ?? "left";
            if (action != "down" && action != "up") return;
            bool right = button == "right";
            Mouse(action == "down" ? (right ? MOUSE_RIGHTDOWN : MOUSE_LEFTDOWN) :
                (right ? MOUSE_RIGHTUP : MOUSE_LEFTUP), 0);
        }
        else if (type == "scroll")
        {
            int delta = Math.Clamp(data.GetProperty("delta").GetInt32(), -480, 480);
            Mouse(MOUSE_WHEEL, unchecked((uint)delta));
        }
        else if (type == "text")
        {
            var text = data.GetProperty("text").GetString() ?? "";
            if (text.Length > 256) return;
            foreach (char c in text)
            {
                KeyUnicode(c, false);
                KeyUnicode(c, true);
            }
        }
        else if (type == "shortcut")
        {
            string action = data.GetProperty("name").GetString() ?? "";
            switch (action)
            {
                case "alt-tab": Combo(VK_MENU, VK_TAB); break;
                case "win-d": Combo(VK_LWIN, 0x44); break;
                case "ctrl-esc": Combo(VK_CONTROL, VK_ESCAPE); break;
                case "ctrl-c": Combo(VK_CONTROL, 0x43); break;
                case "ctrl-v": Combo(VK_CONTROL, 0x56); break;
                case "enter": VirtualKey(VK_RETURN, false); VirtualKey(VK_RETURN, true); break;
                case "backspace": VirtualKey(VK_BACK, false); VirtualKey(VK_BACK, true); break;
            }
        }
        else if (type == "app")
        {
            // Explicit allowlist, never execute an arbitrary command from the network.
            string name = data.GetProperty("name").GetString() ?? "";
            string? executable = name switch
            {
                "notepad" => "notepad.exe",
                "calculator" => "calc.exe",
                "explorer" => "explorer.exe",
                _ => null
            };
            if (executable is not null)
                Process.Start(new ProcessStartInfo(executable) { UseShellExecute = true });
        }
    }

    public static void ReleaseButtons()
    {
        Mouse(MOUSE_LEFTUP, 0);
        Mouse(MOUSE_RIGHTUP, 0);
    }

    private static void Combo(ushort modifier, ushort key)
    {
        VirtualKey(modifier, false);
        VirtualKey(key, false);
        VirtualKey(key, true);
        VirtualKey(modifier, true);
    }

    private static void Mouse(uint flags, uint data)
    {
        var input = new INPUT { Type = INPUT_MOUSE, Union = new InputUnion { Mouse = new MOUSEINPUT { dwFlags = flags, mouseData = data } } };
        SendInput(1, new[] { input }, Marshal.SizeOf<INPUT>());
    }

    private static void VirtualKey(ushort virtualKey, bool up)
    {
        var input = new INPUT { Type = INPUT_KEYBOARD,
            Union = new InputUnion { Keyboard = new KEYBDINPUT { wVk = virtualKey, dwFlags = up ? KEYEVENTF_KEYUP : 0 } } };
        SendInput(1, new[] { input }, Marshal.SizeOf<INPUT>());
    }

    private static void KeyUnicode(char value, bool up)
    {
        var input = new INPUT { Type = INPUT_KEYBOARD,
            Union = new InputUnion { Keyboard = new KEYBDINPUT { wScan = value,
                dwFlags = KEYEVENTF_UNICODE | (up ? KEYEVENTF_KEYUP : 0) } } };
        SendInput(1, new[] { input }, Marshal.SizeOf<INPUT>());
    }

    private const uint INPUT_MOUSE = 0;
    private const uint INPUT_KEYBOARD = 1;
    private const uint MOUSE_LEFTDOWN = 0x0002;
    private const uint MOUSE_LEFTUP = 0x0004;
    private const uint MOUSE_RIGHTDOWN = 0x0008;
    private const uint MOUSE_RIGHTUP = 0x0010;
    private const uint MOUSE_WHEEL = 0x0800;
    private const uint KEYEVENTF_KEYUP = 0x0002;
    private const uint KEYEVENTF_UNICODE = 0x0004;
    private const ushort VK_MENU = 0x12;
    private const ushort VK_TAB = 0x09;
    private const ushort VK_LWIN = 0x5B;
    private const ushort VK_CONTROL = 0x11;
    private const ushort VK_ESCAPE = 0x1B;
    private const ushort VK_RETURN = 0x0D;
    private const ushort VK_BACK = 0x08;

    [StructLayout(LayoutKind.Sequential)]
    private struct INPUT { public uint Type; public InputUnion Union; }
    [StructLayout(LayoutKind.Explicit)]
    private struct InputUnion
    {
        [FieldOffset(0)] public MOUSEINPUT Mouse;
        [FieldOffset(0)] public KEYBDINPUT Keyboard;
    }
    [StructLayout(LayoutKind.Sequential)]
    private struct MOUSEINPUT
    {
        public int dx, dy;
        public uint mouseData, dwFlags, time;
        public IntPtr extra;
    }
    [StructLayout(LayoutKind.Sequential)]
    private struct KEYBDINPUT
    {
        public ushort wVk, wScan;
        public uint dwFlags, time;
        public IntPtr extra;
    }
    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint SendInput(uint count, INPUT[] input, int size);
    [DllImport("user32.dll")]
    private static extern bool SetCursorPos(int x, int y);
}
