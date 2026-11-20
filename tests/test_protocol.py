import hashlib
from pathlib import Path
import subprocess
import sys

ROOT=Path(__file__).resolve().parents[1]


def canonical(client_id, method, path, body, nonce):
    return ('RCP1\n%s\n%s\n%s\n%s\n%s' % (client_id, method.upper(), path,
         hashlib.sha256(body).hexdigest().upper(), nonce)).encode()


def test_empty_body_vector():
    expected = b'RCP1\nclient-1\nGET\n/api/v1/status\nE3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855\nN1'
    assert canonical('client-1','GET','/api/v1/status',b'','N1') == expected


def test_signature_bound_to_request_method():
    assert canonical('client-1','GET','/api/v1/status',b'','N1') != canonical('client-1','POST','/api/v1/status',b'','N1')


def test_signature_bound_to_body_and_nonce():
    a=canonical('client-1','POST','/api/v1/shutdown',b'','N1')
    assert a != canonical('client-1','POST','/api/v1/shutdown',b'abc','N1')
    assert a != canonical('client-1','POST','/api/v1/shutdown',b'','N2')


def test_public_pin_vector():
    assert hashlib.sha256(b'example').hexdigest().upper() == '50D858E0985ECC7F60418AAF0CC5AB587F42C2570A884095A9E8CCACD0F6545C'


def test_package_is_complete():
    subprocess.run([sys.executable, str(ROOT/'tools/check_sources.py')], check=True)


def test_esp32_firmware_is_not_a_patch():
    text=(ROOT/'esp32/main/main.c').read_text()
    assert 'app_main' in text and 'httpd_ssl_start' in text
    assert 'auth(' in text and 'servo' in text


def test_v05_is_self_contained():
    assert not list(ROOT.glob('apply_v0*.py'))
    assert 'git clone' not in (ROOT/'windows/ControlRemoto.UI/build_windows.ps1').read_text()
    assert not (ROOT/'base_v03').exists()
