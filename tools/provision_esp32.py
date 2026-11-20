#!/usr/bin/env python3
"""Genera credenciales TLS privadas por dispositivo y el archivo local del firmware.
Uso: python tools/provision_esp32.py --ssid MI_WIFI --password CLAVE --device-id ESP-001
Los archivos .key, .crt y config_local.h son locales y no deben subirse a GitHub.
"""
import argparse
import datetime
import hashlib
import ipaddress
from pathlib import Path
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID

ROOT = Path(__file__).resolve().parents[1] / 'esp32' / 'main'

def cstring(value: str) -> str:
    return '"' + value.replace('\\', '\\\\').replace('"', '\\"').replace('\n', '\\n') + '"'

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--ssid', required=True)
    p.add_argument('--password', required=True)
    p.add_argument('--device-id', default='ESP-001')
    p.add_argument('--release-us', type=int, default=1000)
    p.add_argument('--press-us', type=int, default=1750)
    args = p.parse_args()
    if not (1 <= len(args.ssid.encode('utf-8')) <= 32): p.error('SSID inválido')
    if not (8 <= len(args.password.encode('utf-8')) <= 63): p.error('Clave Wi-Fi WPA2: 8 a 63 bytes')
    if not (600 <= args.release_us <= 2400 and 600 <= args.press_us <= 2400): p.error('Pulso servo fuera de rango')
    for filename in ('server.key','server.crt','config_local.h'):
        if (ROOT / filename).exists(): p.error('Archivo ya existe: ' + filename + '. Guarda las claves previas antes de reprovisionar.')
    key = ec.generate_private_key(ec.SECP256R1())
    now = datetime.datetime.now(datetime.timezone.utc)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, args.device_id)])
    cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name)
        .public_key(key.public_key()).serial_number(x509.random_serial_number())
        .not_valid_before(now - datetime.timedelta(days=1))
        .not_valid_after(now + datetime.timedelta(days=3650))
        .sign(key, hashes.SHA256()))
    spki = key.public_key().public_bytes(serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo)
    pin = hashlib.sha256(spki).hexdigest().upper()
    ROOT.mkdir(parents=True, exist_ok=True)
    (ROOT / 'server.crt').write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    (ROOT / 'server.key').write_bytes(key.private_bytes(serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    config = f'''#pragma once
#define RCP_WIFI_SSID {cstring(args.ssid)}
#define RCP_WIFI_PASSWORD {cstring(args.password)}
#define RCP_DEVICE_ID {cstring(args.device_id)}
#define RCP_SPKI_SHA256 "{pin}"
#define RCP_SERVO_SIGNAL_GPIO 18
#define RCP_SERVO_RELEASE_US {args.release_us}
#define RCP_SERVO_PRESS_US {args.press_us}
'''
    (ROOT / 'config_local.h').write_text(config, encoding='utf-8')
    print('Aprovisionamiento completado. TLS SPKI SHA256:', pin)
    print('No compartas esp32/main/server.key ni config_local.h. Guarda una copia segura de la identidad.')

if __name__ == '__main__': main()
