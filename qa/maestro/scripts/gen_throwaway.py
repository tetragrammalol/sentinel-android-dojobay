#!/usr/bin/env python3
"""Generate throwaway, never-funded public test data for the Maestro suite.
Writes data/throwaway.env (KEY=value lines, public data only, no secrets).
Seeds are random and discarded, so nobody can spend to/from these keys."""
import os, hmac, hashlib, sys
p=2**256-2**32-977; n=0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141
G=(0x79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798,0x483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8)
def add(a,b):
    if a is None: return b
    if b is None: return a
    if a[0]==b[0] and (a[1]+b[1])%p==0: return None
    l=((3*a[0]*a[0])*pow(2*a[1],-1,p) if a==b else (b[1]-a[1])*pow(b[0]-a[0],-1,p))%p
    x=(l*l-a[0]-b[0])%p; return (x,(l*(a[0]-x)-a[1])%p)
def mul(k,P=G):
    R=None
    while k:
        if k&1: R=add(R,P)
        P=add(P,P); k>>=1
    return R
def ser(P): return bytes([2+(P[1]&1)])+P[0].to_bytes(32,'big')
def h160(b): return hashlib.new('ripemd160',hashlib.sha256(b).digest()).digest()
A='123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz'
def b58c(b):
    b+=hashlib.sha256(hashlib.sha256(b).digest()).digest()[:4]; x=int.from_bytes(b,'big'); s=''
    while x: x,r=divmod(x,58); s=A[r]+s
    return '1'*(len(b)-len(b.lstrip(b'\0')))+s
CH='qpzry9x8gf2tvdw0s3jn54khce6mua7l'
def polymod(v):
    g=[0x3b6a57b2,0x26508e6d,0x1ea119fa,0x3d4233dd,0x2a1462b3]; c=1
    for x in v:
        b=c>>25; c=(c&0x1ffffff)<<5^x
        for i in range(5): c^=g[i] if (b>>i)&1 else 0
    return c
def bech32(hrp,prog):
    acc=bits=0; d=[0]
    for b in prog:
        acc=acc<<8|b; bits+=8
        while bits>=5: bits-=5; d.append(acc>>bits&31)
    if bits: d.append(acc<<(5-bits)&31)
    e=[ord(x)>>5 for x in hrp]+[0]+[ord(x)&31 for x in hrp]
    pm=polymod(e+d+[0]*6)^1
    return hrp+'1'+''.join(CH[x] for x in d+[pm>>5*(5-i)&31 for i in range(6)])
def account(purpose, coin):
    I=hmac.new(b'Bitcoin seed',os.urandom(32),hashlib.sha512).digest(); k,c=int.from_bytes(I[:32],'big'),I[32:]
    fp=b'\0'*4
    for i in [purpose|1<<31, coin|1<<31, 0|1<<31]:
        fp=h160(ser(mul(k)))[:4]
        I=hmac.new(c,b'\0'+k.to_bytes(32,'big')+i.to_bytes(4,'big'),hashlib.sha512).digest()
        k=(int.from_bytes(I[:32],'big')+k)%n; c=I[32:]
    return fp,c,mul(k)
def ext(ver,acct): fp,c,K=acct; return b58c(bytes.fromhex(ver)+bytes([3])+fp+(0x80000000).to_bytes(4,'big')+c+ser(K))
def child(K,c,i):
    I=hmac.new(c,ser(K)+i.to_bytes(4,'big'),hashlib.sha512).digest(); return add(mul(int.from_bytes(I[:32],'big')),K),I[32:]
def addr84(acct,hrp='tb'):
    fp,c,K=acct; K1,c1=child(K,c,0); K2,_=child(K1,c1,0); return bech32(hrp,h160(ser(K2)))
a=account(84,1); b=account(84,1); c=account(84,1); d=account(84,1); t=account(44,1); u=account(49,1); m=account(84,0)
vals={
 'VPUB_A':ext('045f1cf6',a),'VPUB_B':ext('045f1cf6',b),'VPUB_C':ext('045f1cf6',c),'VPUB_D':ext('045f1cf6',d),
 'VPUB_A_ADDR0':addr84(a),
 'TPUB':ext('043587cf',t),'UPUB':ext('044a5262',u),
 'XPUB_MAIN':ext('0488b21e',m),'YPUB_MAIN':ext('049d7cb2',m),'ZPUB_MAIN':ext('04b24746',m),
 'TB1_ADDR':addr84(d),
 'MAIN_ADDR':addr84(m,'bc'),
}
v=vals['VPUB_A']
vals['VPUB_TRUNC']=v[:60]
vals['VPUB_BADSUM']=v[:-1]+('A' if v[-1]!='A' else 'B')
t=vals['TB1_ADDR']
vals['TB1_BADSUM']=t[:-1]+('q' if t[-1]!='q' else 'p')
vals['GARBAGE']='hello garbage not a key'
out=os.path.join(os.path.dirname(os.path.abspath(__file__)),'..','data','throwaway.env')
with open(out,'w') as f:
    f.write('# Throwaway public test data (random seeds, discarded, never funded). Regenerate: scripts/gen_throwaway.py\n')
    for k,x in vals.items(): f.write(f'{k}={x}\n')
print('wrote',os.path.normpath(out))
