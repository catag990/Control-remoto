using System.Runtime.InteropServices;
using System.Text.Json;

namespace RemoteHost;

/// <summary>
/// Remote PC keyboard with an explicit allowlist of standard Windows virtual keys.
/// Supports individual key-down/up events, held modifiers and key combinations.
/// All keys held by the session are released on mode change/disconnect.
/// Does not inject into the Windows secure desktop or bypass UIPI.
/// </summary>
public static class PcKeyboardBridge
{
    private static readonly object Gate = new();
    private static readonly Dictionary<string, ushort> DownKeys = new(StringComparer.OrdinalIgnoreCase);
    private const uint KeyUp = 0x0002;
    private const uint Extended = 0x0001;

    private static readonly Dictionary<string, ushort> Keys = BuildKeyMap();
    private static Dictionary<string, ushort> BuildKeyMap()
    {
        var keys = new Dictionary<string, ushort>(StringComparer.OrdinalIgnoreCase)
        {
            ["ESC"] = 0x1B, ["TAB"] = 0x09, ["BACKSPACE"] = 0x08,
            ["ENTER"] = 0x0D, ["NUMENTER"] = 0x0D, ["SPACE"] = 0x20,
            ["CAPSLOCK"] = 0x14, ["NUMLOCK"] = 0x90, ["SCROLLLOCK"] = 0x91,
            ["PRTSC"] = 0x2C, ["PAUSE"] = 0x13,
            ["LCTRL"] = 0xA2, ["RCTRL"] = 0xA3,
            ["LSHIFT"] = 0xA0, ["RSHIFT"] = 0xA1,
            ["LALT"] = 0xA4, ["RALT"] = 0xA5,
            ["LWIN"] = 0x5B, ["RWIN"] = 0x5C, ["APPS"] = 0x5D,
            ["LEFT"] = 0x25, ["UP"] = 0x26, ["RIGHT"] = 0x27, ["DOWN"] = 0x28,
            ["INS"] = 0x2D, ["DEL"] = 0x2E, ["HOME"] = 0x24,
            ["END"] = 0x23, ["PGUP"] = 0x21, ["PGDN"] = 0x22,
            ["GRAVE"] = 0xC0, ["MINUS"] = 0xBD, ["EQUAL"] = 0xBB,
            ["LBRACKET"] = 0xDB, ["RBRACKET"] = 0xDD, ["BACKSLASH"] = 0xDC,
            ["SEMICOLON"] = 0xBA, ["APOSTROPHE"] = 0xDE,
            ["COMMA"] = 0xBC, ["PERIOD"] = 0xBE, ["SLASH"] = 0xBF,
            ["ISO102"] = 0xE2,
            ["NUMDIV"] = 0x6F, ["NUMMUL"] = 0x6A,
            ["NUMSUB"] = 0x6D, ["NUMADD"] = 0x6B,
            ["NUMDOT"] = 0x6E, ["VOLUME_MUTE"] = 0xAD,
            ["VOLUME_DOWN"] = 0xAE, ["VOLUME_UP"] = 0xAF,
            ["MEDIA_PLAY_PAUSE"] = 0xB3, ["MEDIA_NEXT"] = 0xB0,
            ["MEDIA_PREV"] = 0xB1
        };
        for (char c = 'A'; c <= 'Z'; c++) keys[c.ToString()] = c;
        for (char c = '0'; c <= '9'; c++) keys[c.ToString()] = c;
        for (int f = 1; f <= 24; f++) keys[$"F{f}"] = (ushort)(0x6F + f);
        for (int n = 0; n <= 9; n++) keys[$"NUM{n}"] = (ushort)(0x60 + n);
        return keys;
    }

    public static void Handle(JsonElement message)
    {
        if (!message.TryGetProperty("key", out var name) || name.ValueKind != JsonValueKind.String ||
            !message.TryGetProperty("down", out var pressed) ||
            pressed.ValueKind is not (JsonValueKind.True or JsonValueKind.False)) return;
        string keyName = (name.GetString() ?? "").ToUpperInvariant();
        if (!Keys.TryGetValue(keyName, out ushort virtualKey)) return;
        lock (Gate)
        {
            bool down = pressed.GetBoolean();
            if (down == DownKeys.ContainsKey(keyName)) return;
            if (down) DownKeys[keyName] = virtualKey; else DownKeys.Remove(keyName);
            uint flags = (down ? 0u : KeyUp) | (IsExtended(keyName) ? Extended : 0);
            var input = new INPUT
            {
                Type = 1,
                Union = new InputUnion { Keyboard = new KEYBDINPUT { VirtualKey = virtualKey, Flags = flags } }
            };
            if (SendInput(1, new[] { input }, Marshal.SizeOf<INPUT>()) != 1)
            {
                // Roll back tracking if Windows rejects injection (e.g. UIPI).
                if (down) DownKeys.Remove(keyName); else DownKeys[keyName] = virtualKey;
            }
        }
    }

    public static void HandleSynthetic(string key, bool down)
    {
        using var data = System.Text.Json.JsonDocument.Parse(
            System.Text.Json.JsonSerializer.Serialize(new { key, down }));
        Handle(data.RootElement);
    }

    public static void ReleaseAll()
    {
        lock (Gate)
        {
            foreach (var (name, vk) in DownKeys.ToArray())
            {
                var input = new INPUT { Type = 1,
                    Union = new InputUnion { Keyboard = new KEYBDINPUT { VirtualKey = vk,
                        Flags = KeyUp | (IsExtended(name) ? Extended : 0) } } };
                SendInput(1, new[] { input }, Marshal.SizeOf<INPUT>());
            }
            DownKeys.Clear();
        }
    }

    private static bool IsExtended(string key) => key is
        "RCTRL" or "RALT" or "LWIN" or "RWIN" or "APPS" or
        "LEFT" or "RIGHT" or "UP" or "DOWN" or "INS" or "DEL" or
        "HOME" or "END" or "PGUP" or "PGDN" or "NUMDIV" or "NUMENTER" or "PRTSC";

    [StructLayout(LayoutKind.Sequential)]
    private struct INPUT
    {
        public uint Type;
        public InputUnion Union;
    }
    [StructLayout(LayoutKind.Explicit)]
    private struct InputUnion
    {
        [FieldOffset(0)] public MOUSEINPUT Mouse;
        [FieldOffset(0)] public KEYBDINPUT Keyboard;
    }
    [StructLayout(LayoutKind.Sequential)]
    private struct KEYBDINPUT
    {
        public ushort VirtualKey;
        public ushort Scan;
        public uint Flags;
        public uint Time;
        public IntPtr ExtraInfo;
    }
    [StructLayout(LayoutKind.Sequential)]
    private struct MOUSEINPUT
    {
        public int X, Y;
        public uint MouseData, Flags, Time;
        public IntPtr ExtraInfo;
    }
    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint SendInput(uint count, INPUT[] inputs, int cbSize);
}
