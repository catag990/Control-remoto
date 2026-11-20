#!/usr/bin/env python3
"""Validación estática del paquete autocontenido (NO sustituye compilar SDK)."""
from pathlib import Path
import hashlib
import json
import sys
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
MANDATORY = [
    'README.md', 'android/settings.gradle.kts', 'android/build.gradle.kts',
    'android/gradlew.bat', 'android/gradlew', 'android/gradle/wrapper/gradle-wrapper.jar',
    'android/app/build.gradle.kts', 'android/app/src/main/AndroidManifest.xml',
    'android/app/src/main/java/cl/controlremoto/app/MainActivity.kt',
    'android/app/src/main/java/cl/controlremoto/app/RemoteApi.kt',
    'android/app/src/main/java/cl/controlremoto/app/DeviceCrypto.kt',
    'android/app/src/main/java/cl/controlremoto/app/RemoteSessionScreen.kt',
    'windows/RemoteHost/Program.cs', 'windows/RemoteHost/RemoteHost.csproj',
    'windows/RemoteHost/HostState.cs', 'windows/RemoteHost/Desktop.cs',
    'windows/ControlRemoto.UI/App.xaml', 'windows/ControlRemoto.UI/MainWindow.xaml',
    'windows/ControlRemoto.UI/MainWindow.xaml.cs',
    'windows/ControlRemoto.UI/build_windows.ps1',
    'esp32/CMakeLists.txt', 'esp32/main/CMakeLists.txt', 'esp32/main/main.c',
    'esp32/main/config_local.h.example', 'tools/provision_esp32.py',
    'docs/COMPILAR_EXE_WINDOWS_DESDE_CERO.md', 'docs/INSTALACION_Y_PRUEBAS.md',
]


def check():
    missing = [rel for rel in MANDATORY if not (ROOT / rel).is_file()]
    if missing: raise AssertionError('Archivos faltantes: ' + ', '.join(missing))
    for rel in ('windows/ControlRemoto.UI/App.xaml', 'windows/ControlRemoto.UI/MainWindow.xaml',
                'android/app/src/main/AndroidManifest.xml',
                'windows/RemoteHost/RemoteHost.csproj', 'windows/ControlRemoto.UI/ControlRemoto.UI.csproj'):
        ET.parse(ROOT / rel)
    app = (ROOT / 'android/app/src/main/java/cl/controlremoto/app/RemoteSessionScreen.kt').read_text()
    for token in ['PointerMode.CURSOR', 'PointerMode.DRAG', 'PointerMode.RELATIVE',
                  'GamepadPortraitPanel', 'PcKeyboardPanel', 'MonitorSelector', '144']:
        assert token in app, 'Funcionalidad Android ausente: ' + token
    host = (ROOT / 'windows/RemoteHost/Program.cs').read_text()
    for token in ['"monitor-list"', '"audio-config"', '"stream-config"',
                  '"video-format"', 'HardwareH264Capture', '"/api/v1/desktop"']:
        assert token in host, 'Contrato Windows ausente: ' + token
    android = (ROOT / 'android/app/src/main/java/cl/controlremoto/app/RemoteApi.kt').read_text()
    assert '"RCP1"' in (ROOT / 'android/app/src/main/java/cl/controlremoto/app/DeviceCrypto.kt').read_text()
    assert 'DSASignatureFormat.Rfc3279DerSequence' in (ROOT / 'windows/RemoteHost/HostState.cs').read_text()
    assert 'sslSocketFactory' in android and 'X-Signature' in android
    assert not list(ROOT.rglob('server.key')), 'Clave privada incluida en archivo distribuible'
    assert not list(ROOT.rglob('config_local.h')), 'Contraseña Wi-Fi incluida en archivo distribuible'
    assert not list(ROOT.rglob('host.pfx')), 'Certificado privado incluido en archivo distribuible'
    wrapper = ROOT / 'android/gradle/wrapper/gradle-wrapper.jar'
    assert wrapper.read_bytes()[:4] == b'PK\x03\x04'
    print(f'OK: {len(MANDATORY)} archivos obligatorios, XML, contrato y secretos')


if __name__ == '__main__':
    try: check()
    except Exception as e:
        print('FALLÓ:', e, file=sys.stderr)
        sys.exit(1)
