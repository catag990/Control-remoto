using System.Text.Json;
using Nefarius.ViGEm.Client;
using Nefarius.ViGEm.Client.Targets;
using Nefarius.ViGEm.Client.Targets.Xbox360;

namespace RemoteHost;

/// <summary>
/// Optional real XInput device. Requires the separately installed, signed ViGEmBus driver.
/// ViGEmBus and its .NET client are archived: this is a compatibility backend, not a
/// recommended long-term kernel dependency. The PC host never installs a driver silently.
/// </summary>
public static class VirtualGamepadBridge
{
    private static readonly object Sync = new();
    private static ViGEmClient? client;
    private static IXbox360Controller? pad;
    private static bool initialized;
    private static bool unavailable;

    private static bool EnsureAttached()
    {
        if (unavailable) return false;
        if (pad is not null) return true;
        try
        {
            client = new ViGEmClient();
            pad = client.CreateXbox360Controller();
            pad.AutoSubmitReport = false;
            pad.Connect();
            initialized = true;
            Console.WriteLine("GAMEPAD:virtual-xinput");
            return true;
        }
        catch (Exception ex)
        {
            unavailable = true;
            Console.WriteLine("GAMEPAD:fallback-kbm " + ex.GetType().Name);
            try { pad?.Disconnect(); } catch { }
            try { client?.Dispose(); } catch { }
            pad = null;
            client = null;
            return false;
        }
    }

    public static bool TryHandle(JsonElement data)
    {
        if (!data.TryGetProperty("control", out var prop) || prop.ValueKind != JsonValueKind.String)
            return false;
        lock (Sync)
        {
            if (!EnsureAttached()) return false;
            try
            {
                string control = prop.GetString() ?? "";
                if (control == "left-stick" || control == "right-stick")
                {
                    if (!data.TryGetProperty("x", out var x) || !data.TryGetProperty("y", out var y) ||
                        !x.TryGetDouble(out var xx) || !y.TryGetDouble(out var yy) ||
                        !double.IsFinite(xx) || !double.IsFinite(yy)) return true;
                    // Android Y is positive down; XInput Y is positive up.
                    short axisX = Axis(xx);
                    short axisY = Axis(-yy);
                    pad!.SetAxisValue(control == "left-stick" ? Xbox360Axis.LeftThumbX : Xbox360Axis.RightThumbX, axisX);
                    pad.SetAxisValue(control == "left-stick" ? Xbox360Axis.LeftThumbY : Xbox360Axis.RightThumbY, axisY);
                }
                else if (data.TryGetProperty("pressed", out var pressed) &&
                         pressed.ValueKind is JsonValueKind.True or JsonValueKind.False)
                {
                    bool down = pressed.GetBoolean();
                    if (control is "LT" or "RT")
                        pad!.SetSliderValue(control == "LT" ? Xbox360Slider.LeftTrigger : Xbox360Slider.RightTrigger,
                            down ? byte.MaxValue : (byte)0);
                    else if (Buttons.TryGetValue(control, out var button))
                        pad!.SetButtonState(button, down);
                }
                pad!.SubmitReport();
                return true;
            }
            catch (Exception ex)
            {
                Console.WriteLine("GAMEPAD:virtual-error " + ex.GetType().Name);
                ReleaseCore();
                unavailable = true;
                return false;
            }
        }
    }

    private static short Axis(double value)
    {
        value = Math.Clamp(value, -1.0, 1.0);
        // Dead zone eliminates stick drift caused by touch jitter.
        return Math.Abs(value) < 0.06 ? (short)0 : (short)Math.Round(value * (value < 0 ? 32768 : 32767));
    }

    private static readonly Dictionary<string, Xbox360Button> Buttons = new(StringComparer.OrdinalIgnoreCase)
    {
        ["A"] = Xbox360Button.A, ["B"] = Xbox360Button.B,
        ["X"] = Xbox360Button.X, ["Y"] = Xbox360Button.Y,
        ["LB"] = Xbox360Button.LeftShoulder, ["RB"] = Xbox360Button.RightShoulder,
        ["START"] = Xbox360Button.Start, ["SELECT"] = Xbox360Button.Back,
        ["DUP"] = Xbox360Button.Up, ["DDOWN"] = Xbox360Button.Down,
        ["DLEFT"] = Xbox360Button.Left, ["DRIGHT"] = Xbox360Button.Right
    };

    public static void Neutral()
    {
        lock (Sync)
        {
            if (pad is null) return;
            try { pad.ResetReport(); pad.SubmitReport(); }
            catch { ReleaseCore(); unavailable = true; }
        }
    }

    public static void Disconnect()
    {
        lock (Sync) { NeutralCore(); ReleaseCore(); }
    }

    private static void NeutralCore()
    {
        try { pad?.ResetReport(); pad?.SubmitReport(); } catch { }
    }

    private static void ReleaseCore()
    {
        try { pad?.Disconnect(); } catch { }
        try { client?.Dispose(); } catch { }
        pad = null;
        client = null;
    }
}
