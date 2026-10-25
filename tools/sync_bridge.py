"""Vendor the identical platform-only bridge source into the three adapter repositories."""
from pathlib import Path
import argparse, hashlib, shutil
root=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--check',action='store_true');args=p.parse_args()
for name in ('QAuxiliary','ono','QFun'):
    for source in sorted((root/'bridge/src/main/java/io/github/qqmodulemanager/bridge').glob('*.java')):
        target=root.parent/name/'app/src/main/java/io/github/qqmodulemanager/bridge'/source.name
        if not args.check:
            target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(source,target)
        if not target.exists() or source.read_bytes()!=target.read_bytes():raise SystemExit(f'{name}: {source.name} differs')
        print(name,source.name,hashlib.sha256(target.read_bytes()).hexdigest())
