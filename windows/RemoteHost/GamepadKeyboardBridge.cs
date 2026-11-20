using System.Runtime.InteropServices;
using System.Text.Json;

namespace RemoteHost;

/// <summary>
/// Interpreta controles del mando tactil como teclado/mouse de Windows.
/// No crea un dispositivo XInput: para eso se necesita un bus HID virtual firmado.
/// </summary>
public static class GamepadKeyboardBridge
{
    private static readonly object Gate = new();
    private static readonly HashSet<ushort> HeldKeys = new();
    private static bool _leftMouse;
    private static bool _rightMouse;

    private static readonly Dictionary<string, ushort> GameKeys = new(StringComparer.OrdinalIgnoreCase)
    {
        ["A"] = 0x20,       // Space
        ["B"] = 0x1B,       // Esc
        ["X"] = 0x45,       // E
        ["Y"] = 0x52,       // R
        ["LB"] = 0x10,      // Shift
        ["RB"] = 0x11,      // Control
        ["START"] = 0x0D,   // Enter
        ["SELECT"] = 0x09,  // Tab
        ["DUP"] = 0x26,
        ["DDOWN"] = 0x28,
        ["DLEFT"] = 0x25,
        ["DRIGHT"] = 0x27
    };

    public static void Handle(JsonElement data)
    {
        if (!data.TryGetProperty("control", out var controlElement) ||
            controlElement.ValueKind != JsonValueKind.String) return;
        string control = controlElement.GetString() ?? "";
        // A virtual Xbox controller is preferred when a compatible, signed bus is installed.
        // Do not inject keyboard keys in parallel with XInput (avoids double input).
        if (VirtualGamepadBridge.TryHandle(data)) return;
        lock (Gate)
        {
            if (control == "left-stick" || control == "right-stick")
            {
                if (!data.TryGetProperty("x", out var xJson) ||
                    !data.TryGetProperty("y", out var yJson) ||
                    !xJson.TryGetDouble(out double x) || !yJson.TryGetDouble(out double y) ||
                    !double.IsFinite(x) || !double.IsFinite(y)) return;
                x = Math.Clamp(x, -1d, 1d);
                y = Math.Clamp(y, -1d, 1d);
                if (control == "left-stick")
                {
                    SetKey(0x41, x < -0.35); // A
                    SetKey(0x44, x > 0.35);  // D
                    SetKey(0x57, y < -0.35); // W
                    SetKey(0x53, y > 0.35);  // S
                }
                else if (GetCursorPos(out var position))
                {
                    int deltaX = (int)Math.Round(x * 26);
                    int deltaY = (int)Math.Round(y * 26);
                    SetCursorPos(position.X + deltaX, position.Y + deltaY);
                }
            }
            else
            {
                if (!data.TryGetProperty("pressed", out var p) ||
                    p.ValueKind is not (JsonValueKind.True or JsonValueKind.False)) return;
                bool pressed = p.GetBoolean();
                if (control.Equals("RT", StringComparison.OrdinalIgnoreCase))
                    SetMouse(ref _leftMouse, pressed, 0x0002, 0x0004);
                else if (control.Equals("LT", StringComparison.OrdinalIgnoreCase))
                    SetMouse(ref _rightMouse, pressed, 0x0008, 0x0010);
                else if (GameKeys.TryGetValue(control, out var key))
                    SetKey(key, pressed);
            }
        }
    }

    public static void ReleaseAll()
    {
        VirtualGamepadBridge.Neutral();
        lock (Gate)
        {
            foreach (var key in HeldKeys.ToArray()) SetKey(key, false);
            SetMouse(ref _leftMouse, false, 0x0002, 0x0004);
            SetMouse(ref _rightMouse, false, 0x0008, 0x0010);
        }
    }

    private static void SetKey(ushort key, bool pressed)
    {
        if (pressed == HeldKeys.Contains(key)) return;
        if (pressed) HeldKeys.Add(key);
        else HeldKeys.Remove(key);
        var input = new INPUT { Type = 1, Union = new InputUnion
            { Keyboard = new KEYBDINPUT { Key = key, Flags = pressed ? 0u : 0x0002u } } };
        SendInput(1, new[] { input }, Marshal.SizeOf<INPUT>());
    }

    private static void SetMouse(ref bool state, bool pressed, uint down, uint up)
    {
        if (state == pressed) return;
        state = pressed;
        var input = new INPUT { Type = 0, Union = new InputUnion
            { Mouse = new MOUSEINPUT { Flags = pressed ? down : up } } };
        SendInput(1, new[] { input }, Marshal.SizeOf<INPUT>());
    }

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
        public int X, Y;
        public uint MouseData, Flags, Time;
        public IntPtr Extra;
    }
    [StructLayout(LayoutKind.Sequential)]
    private struct KEYBDINPUT
    {
        public ushort Key, Scan;
        public uint Flags, Time;
        public IntPtr Extra;
    }
    [StructLayout(LayoutKind.Sequential)]
    private struct POINT { public int X, Y; }
    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint SendInput(uint number, INPUT[] input, int size);
    [DllImport("user32.dll")]
    private static extern bool GetCursorPos(out POINT p);
    [DllImport("user32.dll")]
    private static extern bool SetCursorPos(int x, int y);
}
