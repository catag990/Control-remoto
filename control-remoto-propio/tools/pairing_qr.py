#!/usr/bin/env python3
"""Render QR PNG locally from PAIR_JSON line copied from ESP32 USB serial.
Requires pip install qrcode[pil]. Never upload this QR to a cloud generator.
"""
import argparse
import json
from pathlib import Path
import qrcode
parser=argparse.ArgumentParser()
parser.add_argument('--json',required=True,help='Paste only the JSON object after PAIR_JSON:')
parser.add_argument('--out',default='esp32-pairing.png')
a=parser.parse_args()
data=json.loads(a.json)
assert data.get('kind') == 'esp32'
assert all(data.get(k) for k in ('deviceId','localHost','pin','token'))
img=qrcode.make(json.dumps(data,separators=(',',':')))
img.save(a.out)
print('QR saved locally to',Path(a.out).resolve())
