#!/usr/bin/env python3
"""Generate tiny synthetic comic fixtures. Host libarchive is used for 7z creation only; not a build dependency."""
from pathlib import Path
import struct,zlib,zipfile,tarfile,io,bz2,lzma,ctypes
p=Path(__file__).resolve().parents[2]/'app/src/androidTest/assets/comic-fixtures'
p.mkdir(parents=True, exist_ok=True)
def chunk(t,b):return struct.pack('>I',len(b))+t+b+struct.pack('>I',zlib.crc32(t+b))
def png(rgb):return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',2,2,8,2,0,0,0))+chunk(b'IDAT',zlib.compress((b'\0'+bytes(rgb)*2)*2))+chunk(b'IEND',b'')
files=[('book/page10.png',png((0,0,255))),('book/page2.png',png((255,0,0)))]
def rar4(items):
 def h(b):return struct.pack('<H',zlib.crc32(b)&65535)+b
 out=b'Rar!\x1a\x07\x00'+h(struct.pack('<BHHHI',0x73,0,13,0,0))
 for n,b in items:
  n=n.encode();body=struct.pack('<BHHIIBIIBBHI',0x74,0x8000,32+len(n),len(b),len(b),3,zlib.crc32(b),0,20,0x30,len(n),0o100600)+n
  out+=h(body)+b
 return out+h(struct.pack('<BHH',0x7b,0,7))
def vint(n):
 b=bytearray()
 while n>=128:b.append((n&127)|128);n>>=7
 return bytes(b)+bytes([n])
def rar5(items):
 def h(b):
  b=vint(len(b))+b;return struct.pack('<I',zlib.crc32(b))+b
 out=b'Rar!\x1a\x07\x01\0'+h(b'\x01\x00\x00')
 for n,b in items:
  n=n.encode();body=b'\x02\x02'+vint(len(b))+vint(4)+vint(len(b))+vint(0o100600)+struct.pack('<I',zlib.crc32(b))+vint(0)+vint(1)+vint(len(n))+n
  out+=h(body)+b
 return out+h(b'\x05\x00\x00')
(p/'rar4.cbr').write_bytes(rar4(files));(p/'rar5.cbr').write_bytes(rar5(files));(p/'unsafe.cbr').write_bytes(rar5([('../outside.png',files[0][1])]))
b=io.BytesIO()
with tarfile.open(fileobj=b,mode='w') as t:
 for n,data in files:
  info=tarfile.TarInfo(n);info.size=len(data);info.mode=0o600;t.addfile(info,io.BytesIO(data))
(p/'book.cbt').write_bytes(b.getvalue())
import gzip
(p/'book.tgz').write_bytes(gzip.compress(b.getvalue(),mtime=0));(p/'book.tar.bz2').write_bytes(bz2.compress(b.getvalue()));(p/'book.tar.xz').write_bytes(lzma.compress(b.getvalue()))
with zipfile.ZipFile(p/'book.cbz','w',zipfile.ZIP_DEFLATED) as z:
 for n,data in files:
  info=zipfile.ZipInfo(n,(1980,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;z.writestr(info,data)
b=io.BytesIO()
with tarfile.open(fileobj=b,mode='w') as t:
 i=tarfile.TarInfo('link.png');i.type=tarfile.SYMTYPE;i.linkname='../outside.png';t.addfile(i)
(p/'symlink.cbt').write_bytes(b.getvalue())
b=io.BytesIO()
with tarfile.open(fileobj=b,mode='w') as t:
 for _ in range(2):
  i=tarfile.TarInfo('page.png');i.size=len(files[0][1]);t.addfile(i,io.BytesIO(files[0][1]))
(p/'duplicate.cbt').write_bytes(b.getvalue())
# libarchive is used only by this local fixture generator, never by the Android build.
a=ctypes.CDLL('libarchive.so.13')
for n,rest,args in [('archive_write_new',ctypes.c_void_p,[]),('archive_entry_new',ctypes.c_void_p,[]),('archive_write_set_format_7zip',ctypes.c_int,[ctypes.c_void_p]),('archive_write_open_filename',ctypes.c_int,[ctypes.c_void_p,ctypes.c_char_p]),('archive_entry_set_pathname',None,[ctypes.c_void_p,ctypes.c_char_p]),('archive_entry_set_size',None,[ctypes.c_void_p,ctypes.c_longlong]),('archive_entry_set_filetype',None,[ctypes.c_void_p,ctypes.c_uint]),('archive_entry_set_perm',None,[ctypes.c_void_p,ctypes.c_int]),('archive_write_header',ctypes.c_int,[ctypes.c_void_p,ctypes.c_void_p]),('archive_write_data',ctypes.c_longlong,[ctypes.c_void_p,ctypes.c_char_p,ctypes.c_size_t]),('archive_entry_free',None,[ctypes.c_void_p]),('archive_write_close',ctypes.c_int,[ctypes.c_void_p]),('archive_write_free',ctypes.c_int,[ctypes.c_void_p])]:
 f=getattr(a,n);f.restype=rest;f.argtypes=args
w=a.archive_write_new();assert a.archive_write_set_format_7zip(w)==0;assert a.archive_write_open_filename(w,str(p/'book.cb7').encode())==0
for n,data in files:
 e=a.archive_entry_new();a.archive_entry_set_pathname(e,n.encode());a.archive_entry_set_size(e,len(data));a.archive_entry_set_filetype(e,0o100000);a.archive_entry_set_perm(e,0o600);assert a.archive_write_header(w,e)==0;assert a.archive_write_data(w,data,len(data))==len(data);a.archive_entry_free(e)
a.archive_write_close(w);a.archive_write_free(w)
for f in p.iterdir():print(f.name,f.stat().st_size)

# Additional bounded-compression fixture and an intentionally unsupported solid RAR4.
from compression import zstd
(p/'book.tar.zst').write_bytes(zstd.compress((p/'book.cbt').read_bytes()))
b=bytearray((p/'rar4.cbr').read_bytes());offset=20;index=0
while offset+7<=len(b):
 _,kind,flags,size=struct.unpack_from('<HBHH',b,offset)
 packed=struct.unpack_from('<I',b,offset+7)[0] if flags&0x8000 else 0
 if kind==0x74:
  if index==1:
   struct.pack_into('<H',b,offset+3,flags|0x10)
   struct.pack_into('<H',b,offset,zlib.crc32(b[offset+2:offset+size])&65535)
  index+=1
 offset+=size+packed
(p/'solid-rar4.cbr').write_bytes(b)
