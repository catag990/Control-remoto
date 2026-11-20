using System.Collections.Concurrent;
using System.Net;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using QRCoder;

namespace RemoteHost;

public sealed record ClientRecord(string ClientId, string PublicKey);
public sealed record NonceRecord(string Nonce, DateTimeOffset Expires);

/// <summary>Persisted identity and one-time physical pairing policy for the Windows host.</summary>
public sealed class HostState
{
    private readonly object gate = new();
    private readonly string dataDirectory;
    private readonly string clientsFile;
    private readonly ConcurrentDictionary<string, NonceRecord> nonces = new(StringComparer.Ordinal);
    private readonly Dictionary<string, ClientRecord> clients = new(StringComparer.Ordinal);
    private string? pairingToken;
    private DateTimeOffset pairingExpires;
    public X509Certificate2 Certificate { get; }
    public string DeviceId { get; }
    public string PublicPin { get; }

    public HostState()
    {
        dataDirectory = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "ControlRemotoPropio");
        Directory.CreateDirectory(dataDirectory);
        clientsFile = Path.Combine(dataDirectory, "clients.json");
        string certFile = Path.Combine(dataDirectory, "host.pfx");
        if (!File.Exists(certFile))
        {
            using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
            var req = new CertificateRequest("CN=ControlRemotoPropio", key, HashAlgorithmName.SHA256);
            req.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, true));
            req.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature, true));
            using var generated = req.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddYears(5));
            File.WriteAllBytes(certFile, generated.Export(X509ContentType.Pfx));
        }
        Certificate = X509CertificateLoader.LoadPkcs12FromFile(
            certFile,
            password: null,
            keyStorageFlags: X509KeyStorageFlags.UserKeySet |
                            X509KeyStorageFlags.PersistKeySet
        );
        PublicPin = Convert.ToHexString(SHA256.HashData(Certificate.GetECDsaPublicKey()!.ExportSubjectPublicKeyInfo()));
        DeviceId = "PC-" + PublicPin[..16];
        if (File.Exists(clientsFile))
        {
            try
            {
                foreach (var item in JsonSerializer.Deserialize<ClientRecord[]>(File.ReadAllText(clientsFile)) ?? [])
                    if (item.ClientId.Length is > 0 and <= 96) clients[item.ClientId] = item;
            }
            catch (JsonException) { Console.Error.WriteLine("ERROR:clients.json no es JSON valido"); }
        }
    }

    public string ListClients()
    {
        lock (gate) return string.Join(';', clients.Keys.OrderBy(x => x));
    }

    public bool Revoke(string id)
    {
        lock (gate)
        {
            if (!clients.Remove(id)) return false;
            nonces.TryRemove(id, out _);
            SaveClients();
            return true;
        }
    }

    private void SaveClients()
    {
        var temp = clientsFile + ".tmp";
        File.WriteAllText(temp, JsonSerializer.Serialize(clients.Values.ToArray()));
        File.Move(temp, clientsFile, true);
    }

    public (string Json, string PngPath) BeginPair(string lanIp)
    {
        if (!IPAddress.TryParse(lanIp, out var ip) || ip.AddressFamily != System.Net.Sockets.AddressFamily.InterNetwork)
            throw new ArgumentException("IPv4 LAN invalida");
        string token = Convert.ToHexString(RandomNumberGenerator.GetBytes(24));
        lock (gate) { pairingToken = token; pairingExpires = DateTimeOffset.UtcNow.AddMinutes(3); }
        var data = new { kind = "pc", deviceId = DeviceId, lanHost = lanIp,
            lanPort = 8443, wanPort = 8443, spkiSha256 = PublicPin, token };
        string json = JsonSerializer.Serialize(data);
        using var generator = new QRCodeGenerator();
        using var qr = generator.CreateQrCode(json, QRCodeGenerator.ECCLevel.M);
        using var encoded = new PngByteQRCode(qr);
        string png = Path.Combine(Path.GetTempPath(), "remotehost-pairing-" + Environment.ProcessId + ".png");
        File.WriteAllBytes(png, encoded.GetGraphic(5));
        return (json, png);
    }

    public bool AddPairedClient(string token, string id, string spki, IPAddress? remote)
    {
        // Physical one-time QR is essential; never accept a remote pairing without this token.
        if (remote is null || !IsLanAddress(remote) || id.Length is < 8 or > 96 || spki.Length > 2048) return false;
        try
        {
            using var key = ECDsa.Create();
            key.ImportSubjectPublicKeyInfo(Convert.FromBase64String(spki), out int read);
            if (read <= 0 || key.KeySize != 256) return false;
        }
        catch (Exception) { return false; }
        lock (gate)
        {
            if (pairingToken is null || DateTimeOffset.UtcNow >= pairingExpires ||
                !CryptographicOperations.FixedTimeEquals(Encoding.ASCII.GetBytes(pairingToken), Encoding.ASCII.GetBytes(token))) return false;
            pairingToken = null;
            clients[id] = new ClientRecord(id, spki);
            SaveClients();
            Console.WriteLine("PAIRED:" + id);
            return true;
        }
    }

    private static bool IsLanAddress(IPAddress input)
    {
        if (IPAddress.IsLoopback(input)) return true;
        var address = input.IsIPv4MappedToIPv6 ? input.MapToIPv4() : input;
        byte[] b = address.GetAddressBytes();
        return b.Length == 4 && (b[0] == 10 || (b[0] == 172 && b[1] is >= 16 and <= 31) ||
            (b[0] == 192 && b[1] == 168));
    }

    public string? IssueNonce(string id)
    {
        lock (gate) if (!clients.ContainsKey(id)) return null;
        string nonce = Convert.ToHexString(RandomNumberGenerator.GetBytes(24));
        nonces[id] = new NonceRecord(nonce, DateTimeOffset.UtcNow.AddSeconds(30));
        return nonce;
    }

    public bool Verify(HttpRequest req, string path, ReadOnlySpan<byte> body)
    {
        if (!req.Headers.TryGetValue("X-Client-Id", out var cid) ||
            !req.Headers.TryGetValue("X-Nonce", out var nonce) ||
            !req.Headers.TryGetValue("X-Signature", out var signature)) return false;
        string id = cid.ToString(), requestNonce = nonce.ToString();
        if (id.Length > 96 || requestNonce.Length > 96) return false;
        ClientRecord? record;
        lock (gate) clients.TryGetValue(id, out record);
        if (record is null || !nonces.TryRemove(id, out var issued)) return false;
        if (DateTimeOffset.UtcNow > issued.Expires ||
            !CryptographicOperations.FixedTimeEquals(Encoding.ASCII.GetBytes(requestNonce), Encoding.ASCII.GetBytes(issued.Nonce))) return false;
        try
        {
            string canonical = string.Join("\n", "RCP1", id, req.Method.ToUpperInvariant(), path,
                Convert.ToHexString(SHA256.HashData(body)), requestNonce);
            using var key = ECDsa.Create();
            key.ImportSubjectPublicKeyInfo(Convert.FromBase64String(record.PublicKey), out _);
            return key.VerifyData(Encoding.UTF8.GetBytes(canonical), Convert.FromBase64String(signature.ToString()),
                HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence);
        }
        catch (Exception) { return false; }
    }
}
