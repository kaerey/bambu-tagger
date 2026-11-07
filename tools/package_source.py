#!/usr/bin/env python3
"""Build deterministic ZIP of source; no SDK/APK required."""
from pathlib import Path
import zipfile
root=Path(__file__).resolve().parents[1]
target=root.parent/'BambuTagger_Android_v0.1_source.zip'
with zipfile.ZipFile(target,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
 for f in sorted(root.rglob('*')):
  if not f.is_file() or '.gradle' in f.parts or 'build' in f.parts:continue
  relative=Path(root.name)/f.relative_to(root)
  info=zipfile.ZipInfo(str(relative).replace('\\','/'),date_time=(2026,11,6,0,0,0))
  info.external_attr=(0o644 & 0xFFFF)<<16
  info.compress_type=zipfile.ZIP_DEFLATED
  z.writestr(info,f.read_bytes(),compress_type=zipfile.ZIP_DEFLATED,compresslevel=9)
print(target,target.stat().st_size,'bytes')
