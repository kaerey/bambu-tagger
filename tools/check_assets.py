#!/usr/bin/env python3
"""Offline validation of the packaged Bambu identities and public HKDF implementation."""
import json, pathlib, hmac, hashlib
root=pathlib.Path(__file__).resolve().parents[1]
assets=root/'app/src/main/assets/starter'
entries=json.loads((assets/'index.json').read_text())
master=bytes.fromhex('9A759CF2C4F7CAFF222CB9769B41BC96')
def derive(uid,k):
 prk=hmac.new(master,uid,hashlib.sha256).digest()
 info=('RFID-'+k+'\0').encode()
 result=b'';prev=b'';idx=1
 while len(result)<96:
  prev=hmac.new(prk,prev+info+bytes([idx]),hashlib.sha256).digest();result+=prev;idx+=1
 return [result[x*6:(x+1)*6] for x in range(16)]
for entry in entries:
 d=(assets/entry['filename']).read_bytes()
 assert len(d)==1024,entry
 uid=d[:4]
 assert uid.hex().upper()==entry['uid'],entry
 assert d[4]==uid[0]^uid[1]^uid[2]^uid[3],entry
 key_a=derive(uid,'A');key_b=derive(uid,'B')
 for i in range(16):
  trailer=d[i*64+48:i*64+64]
  assert trailer[:6]==key_a[i],(entry['alias'],i,'Key A mismatch')
  assert trailer[10:16]==key_b[i],(entry['alias'],i,'Key B mismatch')
 color='#'+d[5*16:5*16+3].hex().upper()
 assert color==entry['color'],(entry['alias'],color,entry['color'])
 print('PASS',entry['alias'],'->',entry['bambu'],uid.hex().upper(),color)
print(f'Validated {len(entries)} signed-data layouts, BCCs, colors and Bambu sector keys.')
