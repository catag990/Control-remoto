# RCP1 - Wire protocol, version 1

## Transport and identity

Windows HTTPS/WSS `tcp/8443`; ESP32 HTTPS `tcp/443` (public NAT example 9443).
Each device has a self-signed ECDSA P-256 certificate and an out-of-band SPKI SHA-256 pin.
Android pins the certificate public key from the *physical/local* pairing package.
Client authorization uses a distinct non-exportable Android Keystore ECDSA P-256 key for each target.
Only ECDSA P-256 DER SubjectPublicKeyInfo is accepted for clients.

## Initial physical pairing

`POST /api/v1/pair` under pinned TLS with compact JSON:

```json
{"clientId":"MOB-123456789012345678", "publicKeyBase64":"DER_SPKI_BASE64", "token":"RANDOM_HEX_TOKEN"}
```

PC enables pairing for 3 min only after the local operator types `pair` in the PC console.
ESP32 enables pairing for 90 sec after holding its BOOT button for 2 sec, prints JSON over USB serial.
The pairing QR text is a JSON object containing:

```json
{"kind":"pc|esp32","deviceId":"PC-...","localHost":"192.168.x.x","localPort":8443,"pin":"SHA256_SPKI_BASE64","token":"40_HEX_CHARACTERS"}
```

Do not forward a pairing QR over Internet. Client must scan it at the physical device.
Each pairing token is one-use, unpredictable 160-bit, and expires.

## Every authorized HTTPS request

1. `GET /api/v1/auth/challenge?clientId=MOB-...` -> `{"nonce":"48_UPPER_HEX","ttlSeconds":30}`.
2. Construct *the exact bytes* in UTF-8 (no trailing newline):

```text
RCP1\n<clientId>\n<UPPER_METHOD>\n<pathWithoutQuery>\n<UPPER_HEX_SHA256_EXACT_BODY_BYTES>\n<nonce>
```

3. ECDSA P-256 + SHA-256 sign those bytes. Java `SHA256withECDSA` -> ASN.1 DER signature.
4. Include `X-Client-Id`, `X-Nonce`, `X-Signature` (base64 DER signature).
5. The receiving device consumes the nonce BEFORE verifying the signature. It rejects all replays.

`GET /api/v1/status`: Windows only. Signed SHA256 of empty body.
`POST /api/v1/shutdown`: Windows only. Signed exact body `{"confirm":true}`. Shuts down in 30 sec.
`GET /api/v1/power/status`: ESP32 only. Reports `pcPowerState: unknown` unless sensor added.
`POST /api/v1/power/press`: ESP32 only. Signed exact JSON body `{"operation":"short_press"}`.

## Desktop control

`GET /api/v1/desktop` + authenticated WebSocket upgrade over TLS.
Binary frames: complete JPEG image bytes (v1: ~5 fps, quality 62).
Text messages after auth, each <4 KiB:

- `{ "type":"click", "x":0.5, "y":0.5, "button":"left|right" }`
- `{ "type":"move", "x":0.5, "y":0.5 }`
- `{ "type":"scroll", "delta":120 }`
- `{ "type":"button", "button":"left|right", "action":"down|up" }` for drags.
- `{ "type":"text", "text":"hello" }` (up to 256 UTF-16 code units)
- `{ "type":"shortcut", "name":"alt-tab|win-d|ctrl-esc|ctrl-c|ctrl-v|enter|backspace" }`
- `{ "type":"monitor", "index":0 }` (screen array index)
- `{ "type":"app", "name":"notepad|calculator|explorer" }` (allowlist only)

The Windows server only supports interactive already-signed-in desktop in v1.
No arbitrary command execution, file transfer or unattended unlock.

## Upgrade versioning

Breaking changes MUST change `RCP1` prefix and the client/server implementation together.
Never disable certificate pin checking. Re-pair explicitly on identity rotation.
