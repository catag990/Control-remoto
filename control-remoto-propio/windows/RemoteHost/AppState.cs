using System.Collections.Concurrent;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text.Json;
using System.Text.Json.Serialization;
using QRCoder;

namespace RemoteHost;

public sealed record DeviceRecord(string ClientId, string PublicKeyBase64, DateTimeOffset PairedAt);
public sealed record PairingInfo(string Kind, string DeviceId, string LocalHost,
    int LocalPort, string Pin, string Token, long ExpiresAtUnix);
public sealed record ClientRegistration(string ClientId, string PublicKeyBase64, string Token);
public sealed record ChallengeResponse(string Nonce, int TtlSeconds);

public sealed class AppState
{
    private const int MaxClients = 1; // One handset in v1; storage supports future expansion.
    private readonly object _gate = new();
    private readonly Dictionary<string, DeviceRecord> _devices = new(StringComparer.Ordinal);
    private readonly ConcurrentDictionary<string, (string Nonce, DateTimeOffset Expires)> _challenges = new();
    private readonly string _devicesPath;
    private string? _pairToken;
    private DateTimeOffset _pairExpires;

    public string DeviceId { get; }
    public X509Certificate2 Certificate { get; }
    public string Pin { get; }

    public AppState()
    {
        string dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "RemoteHostOwn");
        Directory.CreateDirectory(dir);
        _devicesPath = Path.Combine(dir, "authorized-clients.json");
        string idPath = Path.Combine(dir, "device-id.txt");
        DeviceId = File.Exists(idPath) ? File.ReadAllText(idPath).Trim() : "PC-" + Guid.NewGuid().ToString("N")[..12];
        if (!File.Exists(idPath)) File.WriteAllText(idPath, DeviceId);
        Certificate = LoadOrCreateCertificate(DeviceId);
        using var publicKey = Certificate.GetECDsaPublicKey() ?? throw new Exception("Certificate must have an EC public key");
        Pin = Convert.ToBase64String(SHA256.HashData(publicKey.ExportSubjectPublicKeyInfo()));
        if (File.Exists(_devicesPath))
        {
            foreach (var record in JsonSerializer.Deserialize<List<DeviceRecord>>(File.ReadAllText(_devicesPath)) ?? [])
                _devices[record.ClientId] = record;
        }
    }

    private static X509Certificate2 LoadOrCreateCertificate(string id)
    {
        using var store = new X509Store(StoreName.My, StoreLocation.CurrentUser);
        store.Open(OpenFlags.ReadWrite);
        string subject = "CN=" + id;
        foreach (X509Certificate2 cert in store.Certificates)
            if (cert.Subject == subject && cert.HasPrivateKey && cert.NotAfter > DateTime.Now.AddDays(7)) return cert;

        using var ec = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var request = new CertificateRequest(subject, ec, HashAlgorithmName.SHA256);
        request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, true));
        request.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature, true));
        request.CertificateExtensions.Add(new X509SubjectKeyIdentifierExtension(request.PublicKey, false));
        using var generated = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddYears(3));
        // Persist through the Windows user certificate store, not a password-less file.
        using var imported = new X509Certificate2(generated.Export(X509ContentType.Pkcs12),
            (string?)null, X509KeyStorageFlags.PersistKeySet | X509KeyStorageFlags.UserKeySet);
        store.Add(imported);
        return new X509Certificate2(imported);
    }

    public PairingInfo BeginPairing(string localHost, int port)
    {
        lock (_gate)
        {
            _pairToken = Convert.ToHexString(RandomNumberGenerator.GetBytes(20));
            _pairExpires = DateTimeOffset.UtcNow.AddMinutes(3);
            return new PairingInfo("pc", DeviceId, localHost, port, Pin, _pairToken, _pairExpires.ToUnixTimeSeconds());
        }
    }

    public bool TryPair(ClientRegistration registration, out string error)
    {
        lock (_gate)
        {
            error = "Pairing unavailable, expired or invalid";
            if (_pairToken is null || DateTimeOffset.UtcNow >= _pairExpires) return false;
            if (registration.Token.Length != _pairToken.Length ||
                !CryptographicOperations.FixedTimeEquals(
                    System.Text.Encoding.ASCII.GetBytes(registration.Token),
                    System.Text.Encoding.ASCII.GetBytes(_pairToken))) return false;
            if (!IsValidClientId(registration.ClientId)) return false;
            try
            {
                var der = Convert.FromBase64String(registration.PublicKeyBase64);
                using var key = ECDsa.Create();
                key.ImportSubjectPublicKeyInfo(der, out int used);
                if (used != der.Length || key.KeySize != 256) return false;
            }
            catch (Exception) { return false; }
            if (_devices.Count >= MaxClients && !_devices.ContainsKey(registration.ClientId))
            {
                error = "Only one paired client is allowed in v1";
                return false;
            }
            _devices[registration.ClientId] = new DeviceRecord(registration.ClientId,
                registration.PublicKeyBase64, DateTimeOffset.UtcNow);
            Persist();
            _pairToken = null; // One-time, physical approval via local console.
            error = "";
            return true;
        }
    }

    public static bool IsValidClientId(string id) => id.Length is >= 8 and <= 64 &&
        id.All(c => char.IsAsciiLetterOrDigit(c) || c == '-');

    public ChallengeResponse? CreateChallenge(string clientId)
    {
        lock (_gate) if (!_devices.ContainsKey(clientId)) return null;
        string nonce = Convert.ToHexString(RandomNumberGenerator.GetBytes(24));
        _challenges[clientId] = (nonce, DateTimeOffset.UtcNow.AddSeconds(30));
        return new ChallengeResponse(nonce, 30);
    }

    public bool Authorize(string clientId, string nonce, string signatureBase64,
        string method, string path, ReadOnlySpan<byte> body)
    {
        DeviceRecord record;
        lock (_gate)
        {
            if (!_devices.TryGetValue(clientId, out var found)) return false;
            record = found;
        }
        // Consume before verifying: a nonce cannot be used more than once, even on failure.
        if (!_challenges.TryRemove(clientId, out var challenge) ||
            challenge.Expires < DateTimeOffset.UtcNow ||
            !CryptographicOperations.FixedTimeEquals(System.Text.Encoding.ASCII.GetBytes(challenge.Nonce),
                System.Text.Encoding.ASCII.GetBytes(nonce))) return false;
        try
        {
            var signature = Convert.FromBase64String(signatureBase64);
            var spki = Convert.FromBase64String(record.PublicKeyBase64);
            using var ecdsa = ECDsa.Create();
            ecdsa.ImportSubjectPublicKeyInfo(spki, out int read);
            if (read != spki.Length) return false;
            byte[] message = Protocol.Canonical(clientId, method, path, body, nonce);
            return ecdsa.VerifyData(message, signature, HashAlgorithmName.SHA256,
                DSASignatureFormat.Rfc3279DerSequence);
        }
        catch (Exception) { return false; }
    }

    public IReadOnlyCollection<string> PairedClients()
    {
        lock (_gate) return _devices.Keys.ToArray();
    }

    public bool Revoke(string clientId)
    {
        lock (_gate)
        {
            bool removed = _devices.Remove(clientId);
            if (removed) Persist();
            return removed;
        }
    }

    private void Persist()
    {
        string tmp = _devicesPath + ".tmp";
        File.WriteAllText(tmp, JsonSerializer.Serialize(_devices.Values.ToArray(),
            new JsonSerializerOptions { WriteIndented = true }));
        File.Move(tmp, _devicesPath, true);
    }

    public static void SavePairingQr(PairingInfo info)
    {
        // A local file, never an HTTP-exposed web page.
        var json = JsonSerializer.Serialize(info, new JsonSerializerOptions { PropertyNamingPolicy = JsonNamingPolicy.CamelCase });
        using var qr = new QRCodeGenerator();
        using var data = qr.CreateQrCode(json, QRCodeGenerator.ECCLevel.Q);
        using var png = new PngByteQRCode(data);
        string path = Path.Combine(Path.GetTempPath(), "remotehost-pairing.png");
        File.WriteAllBytes(path, png.GetGraphic(5));
        Console.WriteLine("QR: " + path);
        Console.WriteLine("Alternative manual pairing JSON: " + json);
        try { System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(path) { UseShellExecute = true }); }
        catch { /* Manual JSON fallback remains available. */ }
    }
}

public static class Protocol
{
    public static byte[] Canonical(string clientId, string method, string path, ReadOnlySpan<byte> body, string nonce)
    {
        string digest = Convert.ToHexString(SHA256.HashData(body));
        string payload = $"RCP1\n{clientId}\n{method.ToUpperInvariant()}\n{path}\n{digest}\n{nonce}";
        return System.Text.Encoding.UTF8.GetBytes(payload);
    }
}
