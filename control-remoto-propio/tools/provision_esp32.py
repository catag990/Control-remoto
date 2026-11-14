#!/usr/bin/env python3
"""One-time OFFLINE ESP32 provisioning for ESP-IDF.
Requires: pip install cryptography qrcode[pil]
Never commit main/config_local.h or main/server_key.pem.
"""
import argparse
import base64
import datetime as dt
import hashlib
import ipaddress
import json
import os
import pathlib

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID

ROOT = pathlib.Path(__file__).resolve().parent.parent
MAIN = ROOT / 'esp32' / 'main'

def c_escape(s: str) -> str:
    return s.replace('\\', '\\\\').replace('"', '\\"')

def run(args):
    for label, value in [('SSID',args.ssid),('Wi-Fi password',args.password),('Device ID',args.device_id)]:
        if not value or '\n' in value or '\r' in value:
            raise SystemExit(f'Invalid {label}')
    if not all(c.isalnum() or c == '-' for c in args.device_id):
        raise SystemExit('Device ID must be ASCII alphanumeric/hyphen')
    if not (500 <= args.release_us <= 2500 and 500 <= args.press_us <= 2500):
        raise SystemExit('Servo positions must be within 500..2500 us')
    MAIN.mkdir(parents=True,exist_ok=True)
    for filename in ('server_key.pem', 'server_cert.pem', 'config_local.h'):
        if (MAIN / filename).exists() and not args.force:
            raise SystemExit(f'{filename} already exists; use --force ONLY to re-provision (new pairing required).')
    key = ec.generate_private_key(ec.SECP256R1())
    now = dt.datetime.now(dt.timezone.utc)
    subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, args.device_id)])
    cert = (x509.CertificateBuilder().subject_name(subject).issuer_name(subject)
        .public_key(key.public_key()).serial_number(x509.random_serial_number())
        .not_valid_before(now-dt.timedelta(days=1))
        .not_valid_after(now+dt.timedelta(days=365))
        .add_extension(x509.BasicConstraints(ca=False,path_length=None),critical=True)
        .add_extension(x509.KeyUsage(digital_signature=True,key_encipherment=False,
           content_commitment=False,data_encipherment=False,key_agreement=False,
           key_cert_sign=False,crl_sign=False,encipher_only=False,decipher_only=False),critical=True)
        .sign(key, hashes.SHA256()))
    (MAIN/'server_key.pem').write_bytes(key.private_bytes(
        serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption()))
    os.chmod(MAIN/'server_key.pem', 0o600)
    (MAIN/'server_cert.pem').write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    spki = key.public_key().public_bytes(serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo)
    pin = base64.b64encode(hashlib.sha256(spki).digest()).decode('ascii')
    header = f'''#pragma once
#define WIFI_SSID "{c_escape(args.ssid)}"
#define WIFI_PASSWORD "{c_escape(args.password)}"
#define DEVICE_ID "{c_escape(args.device_id)}"
#define DEVICE_PIN "{pin}"
#define SERVO_GPIO {args.servo_gpio}
#define SERVO_RELEASE_US {args.release_us}
#define SERVO_PRESS_US {args.press_us}
#define PRESS_HOLD_MS 350
#define PRESS_COOLDOWN_MS 20000
'''
    (MAIN/'config_local.h').write_text(header,encoding='utf-8')
    os.chmod(MAIN/'config_local.h', 0o600)
    info = {'deviceId':args.device_id,'pin':pin,
            'note':'To pair, hold BOOT 2 seconds and read the PAIR_JSON line on serial'}
    (ROOT/'esp32'/'device-public-info.json').write_text(json.dumps(info,indent=2))
    print(json.dumps(info,indent=2))
    print('Generated local-only secrets: esp32/main/config_local.h, server_key.pem')

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--ssid',required=True)
    parser.add_argument('--password',required=True)
    parser.add_argument('--device-id',default='ESP-001')
    parser.add_argument('--servo-gpio',type=int,default=18)
    parser.add_argument('--release-us',type=int,default=1000)
    parser.add_argument('--press-us',type=int,default=1750)
    parser.add_argument('--force',action='store_true')
    run(parser.parse_args())
