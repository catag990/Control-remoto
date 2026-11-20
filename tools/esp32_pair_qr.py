#!/usr/bin/env python3
"""Convierte una línea PAIR_JSON: del monitor serie en PNG QR local. No compartir.
Uso: python tools/esp32_pair_qr.py --json '{"kind":"esp32",...}' --out esp32-qr.png
"""
import argparse
import json
from pathlib import Path
import qrcode

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--json',required=True,help='JSON completo del monitor serie, sin el prefijo PAIR_JSON:')
    p.add_argument('--out',default='esp32-pair.png')
    args=p.parse_args()
    data=json.loads(args.json)
    if data.get('kind') != 'esp32' or not data.get('token'):
        p.error('Debe ser el JSON temporal de emparejamiento ESP32')
    qrcode.make(json.dumps(data,separators=(',',':'))).save(args.out)
    print('QR temporal creado en',Path(args.out).resolve(),'— caduca según la consola ESP32')
if __name__=='__main__':main()
