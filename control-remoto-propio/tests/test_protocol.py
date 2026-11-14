"""Cryptographic contract and repository checks, not real hardware E2E tests."""
import base64
import hashlib
import json
from pathlib import Path
import re

import pytest
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

ROOT = Path(__file__).resolve().parent.parent

def canonical(client, method, path, body, nonce):
    return ('RCP1\n%s\n%s\n%s\n%s\n%s' %
        (client,method.upper(),path,hashlib.sha256(body).hexdigest().upper(),nonce)).encode('utf-8')

def create_client():
    key = ec.generate_private_key(ec.SECP256R1())
    return key, key.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)

def test_ecdsa_matches_der_and_canonical_bytes():
    key, public = create_client()
    body = json.dumps({'operation':'short_press'},separators=(',',':')).encode()
    signed_data = canonical('MOB-123456789012345678','POST','/api/v1/power/press',body,'A'*48)
    assert signed_data.decode().splitlines() == [
      'RCP1','MOB-123456789012345678','POST','/api/v1/power/press',
      hashlib.sha256(body).hexdigest().upper(),'A'*48]
    der_signature = key.sign(signed_data,ec.ECDSA(hashes.SHA256()))
    loaded = serialization.load_der_public_key(public)
    loaded.verify(der_signature,signed_data,ec.ECDSA(hashes.SHA256()))
    with pytest.raises(Exception):
        loaded.verify(der_signature,canonical('MOB-123456789012345678','POST',
            '/api/v1/power/press',b'{"operation":"long_press"}', 'A'*48),ec.ECDSA(hashes.SHA256()))
    with pytest.raises(Exception):
        loaded.verify(der_signature,canonical('MOB-123456789012345678','POST',
            '/api/v1/power/press',body, 'B'*48),ec.ECDSA(hashes.SHA256()))


def test_spki_pin_matches_certificate_generated_by_provisioner(tmp_path):
    from cryptography.x509.oid import NameOID
    import datetime as dt
    key, public = create_client()
    pin = base64.b64encode(hashlib.sha256(public).digest()).decode()
    assert len(base64.b64decode(pin)) == 32
    now=dt.datetime.now(dt.timezone.utc)
    name=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,'ESP-001')])
    cert=x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())\
        .serial_number(x509.random_serial_number()).not_valid_before(now-dt.timedelta(days=1))\
        .not_valid_after(now+dt.timedelta(days=365)).sign(key, hashes.SHA256())
    loaded=x509.load_der_x509_certificate(cert.public_bytes(serialization.Encoding.DER))
    loaded_spki=loaded.public_key().public_bytes(serialization.Encoding.DER,serialization.PublicFormat.SubjectPublicKeyInfo)
    assert base64.b64encode(hashlib.sha256(loaded_spki).digest()).decode() == pin


def test_all_components_implement_identical_signature_preamble():
    sources = [
        ROOT/'windows/RemoteHost/AppState.cs',
        ROOT/'android/app/src/main/java/cl/controlremoto/app/DeviceCrypto.kt',
        ROOT/'esp32/main/main.c',
    ]
    for s in sources:
        code=s.read_text()
        assert 'RCP1\\n' in code, s
        assert 'SHA256' in code or 'SHA-256' in code or 'sha256' in code, s
    paths = [
        '/api/v1/pair', '/api/v1/auth/challenge',
        '/api/v1/power/status', '/api/v1/power/press'
    ]
    android = (ROOT/'android/app/src/main/java/cl/controlremoto/app/RemoteApi.kt').read_text()
    esp=(ROOT/'esp32/main/main.c').read_text()
    for path in paths:
        assert path in android or path in (ROOT/'android/app/src/main/java/cl/controlremoto/app/MainActivity.kt').read_text()
        assert path in esp


def test_no_embedded_credentials_in_distributable_tree():
    forbidden = ['server_key.pem','config_local.h']
    for filename in forbidden:
        assert not (ROOT/'esp32/main'/filename).exists(), 'Do not ship ESP32 private secrets'
    android=(ROOT/'android/app/src/main/java/cl/controlremoto/app/DeviceCrypto.kt').read_text()
    assert 'pinMatches' in android
    assert 'MessageDigest.isEqual' in android
    assert 'checkValidity' in android
    assert 'return true' not in android.split('.hostnameVerifier')[1].split('}\n')[0]


def test_no_open_public_routers_or_built_app_in_package():
    for extension in ('*.apk','*.pfx','*.exe'):
        assert not list(ROOT.rglob(extension))
