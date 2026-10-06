# -*- coding: utf-8 -*-
"""程序化二次元风 BGM（numpy 加速）：钢琴琶音 + 弦乐铺底 + 轻打击"""
import numpy as np, wave, sys, time

SR = 44100

def env_adsr(n, a, d, s, r):
    e = np.zeros(n, dtype=np.float32)
    an, dn, rn = int(a*SR), int(d*SR), int(r*SR)
    sn = max(0, n-an-dn-rn)
    i = 0
    if an: e[:an] = np.linspace(0,1,an); i=an
    if dn: e[i:i+dn] = np.linspace(1,s,dn); i+=dn
    if sn: e[i:i+sn] = s; i+=sn
    if rn: e[i:i+rn] = np.linspace(s,0,rn)
    return e

def piano(freq, dur, amp=0.2):
    n = int(dur*SR)
    t = np.arange(n, dtype=np.float32)/SR
    out = np.zeros(n, dtype=np.float32)
    for h, ha, dec in [(1,1.0,3.0),(2,0.45,4.2),(3,0.22,5.4),(4,0.12,6.6),
                       (5,0.06,7.8),(6,0.03,9.0)]:
        f = freq*h
        if f > SR/2: break
        out += ha*np.sin(2*np.pi*f*t, dtype=np.float32)*np.exp(-dec*t).astype(np.float32)
    out *= env_adsr(n, 0.004, 0.25, 0.35, min(0.6, dur*0.5))*amp
    return out

def pad(freqs, dur, amp=0.16):
    n = int(dur*SR)
    t = np.arange(n, dtype=np.float32)/SR
    out = np.zeros(n, dtype=np.float32)
    for f in freqs:
        vib = 1 + 0.003*np.sin(2*np.pi*4.5*t, dtype=np.float32)
        out += np.sin(2*np.pi*f*t*vib, dtype=np.float32)
        out += 0.18*np.sin(2*np.pi*f*2*t, dtype=np.float32)
    out *= env_adsr(n, 0.6, 0.3, 0.85, 1.0)*(amp/max(1,len(freqs)))
    return out

def kick(dur=0.35, amp=0.2):
    n=int(dur*SR); t=np.arange(n,dtype=np.float32)/SR
    f = 120*np.exp(-t*18)+45
    return (np.sin(2*np.pi*f*t, dtype=np.float32)*np.exp(-t*9)*amp).astype(np.float32)

def hat(dur=0.06, amp=0.06, seed=1):
    n=int(dur*SR); rng=np.random.default_rng(seed)
    return (rng.uniform(-1,1,n).astype(np.float32)*np.exp(-np.arange(n,dtype=np.float32)/SR*60)*amp).astype(np.float32)

def mix_into(buf, sig, at):
    end = min(len(buf), at+len(sig))
    if end<=at: return
    buf[at:end] += sig[:end-at]

def f(semi): return 440.0*(2**(semi/12))

def build(total_sec, seed=42):
    n = int(total_sec*SR)
    buf = np.zeros(n, dtype=np.float32)
    prog = [[0,3,7],[-4,0,5],[3,7,10],[-2,2,7]]
    bar = 2.0; t=0.0; bi=0
    while t < total_sec:
        ch = prog[bi%len(prog)]
        pf = [f(s-12) for s in ch] + [f(s) for s in ch[:2]]
        mix_into(buf, pad(pf, bar+0.6, 0.16), int(t*SR))
        arp = [ch[0],ch[1],ch[2],ch[1]+12,ch[2],ch[1],ch[0]+12,ch[1]]
        step = bar/len(arp)
        for k,s in enumerate(arp):
            tt = t+k*step
            if tt>=total_sec: break
            oct_ = 12 if bi%2 else 0
            mix_into(buf, piano(f(s+12+oct_),0.9,0.20), int(tt*SR))
        if bi%2==0:
            mel=[ch[2]+12,ch[1]+12,ch[0]+12,ch[0]+19]
            for k,s in enumerate(mel):
                tt=t+k*(bar/len(mel))
                if tt>=total_sec: break
                mix_into(buf, piano(f(s),1.2,0.13), int(tt*SR))
        t+=bar; bi+=1
    # 节奏
    beat=0.5; tt=8.0
    while tt < total_sec-4:
        mix_into(buf, kick(0.35,0.18), int(tt*SR))
        mix_into(buf, hat(0.06,0.05), int((tt+beat/2)*SR))
        tt+=beat
    # 淡入淡出
    fi=int(1.5*SR); fo=int(3.0*SR)
    buf[:fi]*=np.linspace(0,1,fi,dtype=np.float32)
    buf[-fo:]*=np.linspace(1,0,fo,dtype=np.float32)
    peak=max(1e-6, float(np.max(np.abs(buf))))
    buf*=0.85/peak
    d=60
    L=buf.copy()
    R=np.concatenate([np.zeros(d,dtype=np.float32), buf[:-d]])*0.97 + buf*0.03
    return L,R

def write_wav(path,L,R):
    n=len(L)
    inter=np.empty(n*2, dtype=np.int16)
    inter[0::2]=np.clip(L,-1,1)*32767
    inter[1::2]=np.clip(R,-1,1)*32767
    w=wave.open(path,'w'); w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR)
    w.writeframes(inter.tobytes()); w.close()

if __name__=="__main__":
    total=float(sys.argv[1]) if len(sys.argv)>1 else 215.0
    t0=time.time(); L,R=build(total); write_wav("/workspace/.video/audio/bgm.wav",L,R)
    print("bgm.wav %.1fs built in %.1fs"%(total,time.time()-t0))
