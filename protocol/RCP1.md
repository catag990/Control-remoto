# Contrato de comunicación RCP1 v0.5

Todos los destinos tienen certificado TLS distinto con pin SHA-256 de SubjectPublicKeyInfo del certificado ECDSA P-256. Los códigos de emparejamiento solo deben revelarse en presencia física o mediante botón BOOT. No existe fallback a HTTP, no hay contraseñas comunes.

## QR / JSON temporal

Windows: `{"kind":"pc","deviceId":"PC-...","lanHost":"192.168.1.100","lanPort":8443,"wanPort":8443,"spkiSha256":"HEX64","token":"HEX48"}`.

ESP32: igual con `kind:"esp32"`, puertos 443/9443 y deviceId `ESP-001`. Los campos IP del ejemplo se sustituyen por valores reales. `token` dura 180 segundos, solo se acepta una vez; nunca se almacena en Android después de emparejar.

## Pair HTTPS (solo LAN + token físico)

`POST /api/v1/pair` JSON UTF-8 `{"token":"...","clientId":"uuid","publicKeySpki":"Base64(DER SubjectPublicKeyInfo EC P256)"}`. El servidor comprueba token temporal, remoto LAN y clave EC. Guarda el cliente autorizado para posteriores autenticaciones.

## Challenge y firma

Antes de **cada** petición firmada (incluido WebSocket): `GET /api/v1/auth/challenge?clientId=<id>` → `{ "nonce": "HEX48", "ttlSeconds": 30 }`. El nonce es de uso único y caduca a los 30 s.

Cabeceras: `X-Client-Id`, `X-Nonce`, `X-Signature` (base64 ECDSA DER P-256 SHA-256).

Bytes canónicos UTF-8 sin salto final:

```
RCP1\n<clientId>\n<METHOD_UPPER>\n<pathWithoutQuery>\n<SHA256_EXACT_BODY_UPPER_HEX>\n<nonce>
```

En una petición GET con cuerpo vacío, SHA256 es `E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855`.

## API Windows

| Método | Ruta | Uso |
|---|---|---|
| POST | `/api/v1/pair` | Emparejamiento físico |
| GET | `/api/v1/auth/challenge` | Solicitud nonce para clientes autorizados |
| GET | `/api/v1/status` | Estado firmado |
| POST | `/api/v1/shutdown` | Apagado normal 30 s |
| GET → WSS | `/api/v1/desktop` | Streaming bidireccional autenticado |

## API ESP32

| Método | Ruta | Uso |
|---|---|---|
| POST | `/api/v1/pair` | Token físico de BOOT |
| GET | `/api/v1/auth/challenge` | Nonce |
| GET | `/api/v1/power/status` | Estado ESP32; potencia PC desconocida sin sensor |
| POST | `/api/v1/power/press` | Pulso servo autorizado, enfriamiento 20 s |

## Mensajes WSS Windows

El servidor envía JPEG puro o unidad H.264 Annex B precedida por cuatro bytes ASCII `RCH3`; audio PCM16 estéreo precedido por `RCA3`. Los mensajes de texto JSON señalan `video-format`, `video-fallback`, `monitor-list`, `telemetry`, `audio-format`, `session-info` y `pong`.

Android envía `stream-config` (fps, codec, quality, bitrateKbps, lowImpact), `audio-config`, `monitor`, `pc-key` (name y down), `pc-key-release-all`, `gamepad`, `gamepad-neutral`, `move`, `click`, `button`, `scroll`, `mouse-relative`, `shortcut`, `text` y `ping`.

`stream-config` limita fps solicitado, no garantiza fps efectivos. Perfil lowImpact = máximo 60 fps H.264 y 4500 kbps; JPEG reserva máximo 30 fps.

El mando virtual solo crea XInput si el driver de kernel compatible fue instalado por el usuario. Sin él, el host traduce eventos de mando a teclado/mouse. `SendInput` no entra al desktop seguro Windows ni garantiza funcionamiento con Raw Input o antitrampas.
