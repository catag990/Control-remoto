using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using Microsoft.Win32;
using Forms = System.Windows.Forms;

namespace ControlRemoto.UI;

public partial class MainWindow : Window
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string RunName = "ControlRemotoPropioUI";
    private readonly ObservableCollection<string> _clients = new();
    private readonly DispatcherTimer _qrTimer = new() { Interval = TimeSpan.FromSeconds(1) };
    private Process? _host;
    private Forms.NotifyIcon? _tray;
    private DateTimeOffset? _qrDeadline;
    private string? _qrPath;
    private bool _allowExit;
    private bool _loadingAutoStart;

    public MainWindow()
    {
        InitializeComponent();
        DevicesList.ItemsSource = _clients;
        _qrTimer.Tick += (_, _) => UpdateQrCountdown();
        _qrTimer.Start();
    }

    private void Window_Loaded(object sender, RoutedEventArgs e)
    {
        IpInput.Text = GuessLocalIPv4();
        LocalIp.Text = IpInput.Text;
        _loadingAutoStart = true;
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKey, writable: false);
            AutoStartCheck.IsChecked = key?.GetValue(RunName) is string;
        }
        finally { _loadingAutoStart = false; }
        AutoStartCheck.Checked += AutoStart_Changed;
        AutoStartCheck.Unchecked += AutoStart_Changed;
        _tray = new Forms.NotifyIcon
        {
            Text = "Control remoto propio",
            Icon = System.Drawing.SystemIcons.Application,
            Visible = true
        };
        _tray.DoubleClick += (_, _) => ShowPanel();
        var menu = new Forms.ContextMenuStrip();
        menu.Items.Add("Mostrar panel", null, (_, _) => Dispatcher.Invoke(ShowPanel));
        menu.Items.Add("Salir completamente", null, (_, _) => Dispatcher.Invoke(ExitCompletely));
        _tray.ContextMenuStrip = menu;
        StartHost();
    }

    private static string GuessLocalIPv4()
    {
        foreach (var network in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (network.OperationalStatus != OperationalStatus.Up ||
                network.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
            foreach (var address in network.GetIPProperties().UnicastAddresses)
            {
                if (address.Address.AddressFamily == AddressFamily.InterNetwork &&
                    IsPrivate(address.Address)) return address.Address.ToString();
            }
        }
        return "192.168.1.100";
    }

    private static bool IsPrivate(IPAddress address)
    {
        var b = address.GetAddressBytes();
        return b.Length == 4 && (b[0] == 10 || (b[0] == 172 && b[1] >= 16 && b[1] <= 31) ||
            (b[0] == 192 && b[1] == 168));
    }

    private void Start_Click(object sender, RoutedEventArgs e) => StartHost();
    private void Stop_Click(object sender, RoutedEventArgs e) => StopHost();

    private void StartHost()
    {
        if (_host is { HasExited: false }) return;
        var exe = Path.Combine(AppContext.BaseDirectory, "Host", "RemoteHost.exe");
        if (!File.Exists(exe))
        {
            Log(@"No existe Host\RemoteHost.exe. Compila con windows\ControlRemoto.UI\build_windows.ps1.");
            ServerState.Text = "Host no instalado";
            return;
        }
        try
        {
            var process = new Process
            {
                StartInfo = new ProcessStartInfo(exe)
                {
                    WorkingDirectory = Path.GetDirectoryName(exe)!,
                    UseShellExecute = false,
                    CreateNoWindow = true,
                    RedirectStandardInput = true,
                    RedirectStandardOutput = true,
                    RedirectStandardError = true,
                    StandardOutputEncoding = Encoding.UTF8,
                    StandardErrorEncoding = Encoding.UTF8
                },
                EnableRaisingEvents = true
            };
            process.OutputDataReceived += (_, args) => ReceivedLine(args.Data);
            process.ErrorDataReceived += (_, args) => ReceivedLine(args.Data is null ? null : "ERROR: " + args.Data);
            process.Exited += (_, _) => Dispatcher.BeginInvoke(() =>
            {
                ServerState.Text = "Servidor detenido";
                SessionText.Text = "Desconectada";
                StartButton.IsEnabled = true;
                StopButton.IsEnabled = false;
                PairButton.IsEnabled = false;
                Log("Proceso de servidor finalizado.");
            });
            if (!process.Start()) throw new IOException("No se pudo iniciar el proceso host.");
            _host = process;
            process.BeginOutputReadLine();
            process.BeginErrorReadLine();
            ServerState.Text = "Iniciando...";
            StartButton.IsEnabled = false;
            StopButton.IsEnabled = true;
            PairButton.IsEnabled = true;
            Log("Iniciando servidor Windows local.");
        }
        catch (Exception ex)
        {
            ServerState.Text = "Error de inicio";
            Log("No se pudo iniciar el servidor: " + ex.Message);
        }
    }

    private void StopHost()
    {
        var process = _host;
        if (process is null) return;
        try
        {
            if (!process.HasExited)
            {
                process.StandardInput.WriteLine("quit");
                process.StandardInput.Flush();
                if (!process.WaitForExit(2200)) process.Kill(entireProcessTree: true);
            }
        }
        catch (Exception ex) { Log("Deteniendo host: " + ex.Message); }
        finally { process.Dispose(); _host = null; }
    }

    private void SendHostCommand(string command)
    {
        try
        {
            if (_host is null || _host.HasExited) { Log("El servidor esta detenido."); return; }
            _host.StandardInput.WriteLine(command);
            _host.StandardInput.Flush();
        }
        catch (Exception ex) { Log("No fue posible enviar el comando: " + ex.Message); }
    }

    private void Pair_Click(object sender, RoutedEventArgs e)
    {
        var ip = IpInput.Text.Trim();
        if (!IPAddress.TryParse(ip, out var parsed) || !IsPrivate(parsed))
        {
            Log("Indica una IPv4 LAN privada valida para el QR.");
            return;
        }
        // En la consola original, el comando 'pair' lee la IPv4 inmediatamente después.
        SendHostCommand("pair");
        SendHostCommand(ip);
        LocalIp.Text = ip;
        _qrDeadline = null;
        QrImage.Visibility = Visibility.Collapsed;
        QrPlaceholder.Visibility = Visibility.Visible;
        QrCountdown.Text = "Generando QR...";
    }

    private void ReceivedLine(string? line)
    {
        if (string.IsNullOrWhiteSpace(line)) return;
        Dispatcher.BeginInvoke(() => HandleLine(line.Trim()));
    }

    private void HandleLine(string line)
    {
        // No mostrar tokens secretos del JSON de emparejamiento en el registro gráfico.
        if (line.StartsWith("Alternative manual pairing JSON:", StringComparison.OrdinalIgnoreCase)) return;
        if (line.StartsWith("RemoteHost:", StringComparison.Ordinal))
            DeviceIdText.Text = line["RemoteHost:".Length..].Trim();
        else if (line.StartsWith("Running in interactive Windows session", StringComparison.Ordinal))
        {
            ServerState.Text = "Servidor activo";
            SendHostCommand("clients");
        }
        else if (line.StartsWith("SESSION:", StringComparison.Ordinal))
        {
            bool connected = line.StartsWith("SESSION:connected", StringComparison.Ordinal);
            SessionText.Text = connected ? "Conectada" : "Desconectada";
        }
        else if (line.StartsWith("CLIENTS:", StringComparison.Ordinal))
        {
            _clients.Clear();
            string raw = line["CLIENTS:".Length..];
            foreach (string name in raw.Split(';', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
                _clients.Add(name);
            if (_clients.Count == 0) _clients.Add("Sin celulares autorizados");
            return;
        }
        else if (line.StartsWith("QR: ", StringComparison.Ordinal))
        {
            DisplayQr(line[4..].Trim());
            return;
        }
        else if (line.StartsWith("STATS:", StringComparison.Ordinal))
        {
            string[] values = line[6..].Split(';');
            if (values.Length == 3)
            {
                ActualFps.Text = values[0];
                RequestedFps.Text = values[1];
                JpegQuality.Text = values[2];
            }
            return;
        }
        else if (line.StartsWith("PAIRED:", StringComparison.Ordinal))
            SendHostCommand("clients");
        Log(line.Length > 250 ? line[..250] : line);
    }

    private void DisplayQr(string path)
    {
        try
        {
            using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
            var bitmap = new BitmapImage();
            bitmap.BeginInit();
            bitmap.CacheOption = BitmapCacheOption.OnLoad;
            bitmap.StreamSource = stream;
            bitmap.EndInit();
            bitmap.Freeze();
            QrImage.Source = bitmap;
            QrImage.Visibility = Visibility.Visible;
            QrPlaceholder.Visibility = Visibility.Collapsed;
            _qrPath = path;
            _qrDeadline = DateTimeOffset.UtcNow.AddMinutes(3);
            UpdateQrCountdown();
            Log("QR disponible dentro del panel (3 minutos). No lo compartas por Internet.");
        }
        catch (Exception ex) { Log("No se pudo mostrar el QR: " + ex.Message); }
    }

    private void UpdateQrCountdown()
    {
        if (_qrDeadline is null) return;
        int seconds = (int)Math.Max(0, Math.Ceiling((_qrDeadline.Value - DateTimeOffset.UtcNow).TotalSeconds));
        QrCountdown.Text = seconds > 0 ? $"QR activo · {seconds} s restantes" : "QR caducado";
        if (seconds != 0) return;
        QrImage.Source = null;
        QrImage.Visibility = Visibility.Collapsed;
        QrPlaceholder.Visibility = Visibility.Visible;
        _qrDeadline = null;
        DeleteQr();
    }

    private void DeleteQr()
    {
        if (_qrPath is null) return;
        try { if (File.Exists(_qrPath)) File.Delete(_qrPath); }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
        _qrPath = null;
    }

    private void Clients_Click(object sender, RoutedEventArgs e) => SendHostCommand("clients");

    private void Revoke_Click(object sender, RoutedEventArgs e)
    {
        string? selected = DevicesList.SelectedItem as string;
        if (string.IsNullOrWhiteSpace(selected) || selected.StartsWith("Sin celulares"))
        {
            Log("Selecciona un cliente autorizado para revocarlo.");
            return;
        }
        if (System.Windows.MessageBox.Show($"¿Revocar el telefono {selected}?", "Confirmar revocacion",
            MessageBoxButton.YesNo, MessageBoxImage.Warning) != MessageBoxResult.Yes) return;
        SendHostCommand("revoke " + selected);
        SendHostCommand("clients");
    }

    private void AutoStart_Changed(object sender, RoutedEventArgs e)
    {
        if (_loadingAutoStart) return;
        try
        {
            using var key = Registry.CurrentUser.CreateSubKey(RunKey, writable: true);
            if (AutoStartCheck.IsChecked == true)
                key.SetValue(RunName, '"' + Environment.ProcessPath + '"');
            else key.DeleteValue(RunName, throwOnMissingValue: false);
        }
        catch (Exception ex) { Log("No se pudo configurar autoarranque: " + ex.Message); }
    }

    private void Log(string text)
    {
        EventList.Items.Add($"[{DateTime.Now:HH:mm:ss}] {text}");
        while (EventList.Items.Count > 150) EventList.Items.RemoveAt(0);
        if (EventList.Items.Count > 0) EventList.ScrollIntoView(EventList.Items[EventList.Items.Count - 1]);
    }

    private void Window_StateChanged(object sender, EventArgs e)
    {
        if (WindowState == WindowState.Minimized) Hide();
    }

    private void ShowPanel()
    {
        Show();
        WindowState = WindowState.Normal;
        Activate();
    }

    private void Window_Closing(object? sender, CancelEventArgs e)
    {
        if (_allowExit) return;
        e.Cancel = true;
        Hide();
        _tray?.ShowBalloonTip(1200, "Control remoto propio",
            "La sesión remota sigue disponible mientras el servidor esté activo.",
            Forms.ToolTipIcon.Info);
    }

    private void ExitCompletely()
    {
        _allowExit = true;
        _qrTimer.Stop();
        DeleteQr();
        StopHost();
        if (_tray is not null)
        {
            _tray.Visible = false;
            _tray.Dispose();
            _tray = null;
        }
        Close();
    }
}
