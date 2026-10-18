import os,select,struct,subprocess,time,json,pathlib,hashlib,sys,shlex
if len(sys.argv)!=3: raise SystemExit('usage: verify_host.py /absolute/helper /absolute/report-directory')
helper=pathlib.Path(sys.argv[1]).resolve()
out=pathlib.Path(sys.argv[2]).resolve();out.mkdir(parents=True,exist_ok=True)
source=pathlib.Path(__file__).with_name('daiyu_pty.c')
p=subprocess.Popen([str(helper),'24','80','--','/bin/sh'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
buffer=bytearray(); output=bytearray(); events=[]
def send(t,data=b''):
    p.stdin.write(t+struct.pack('>I',len(data))+data);p.stdin.flush()
def collect(predicate,seconds=3):
    deadline=time.monotonic()+seconds
    while time.monotonic()<deadline:
        if predicate():return
        r,_,_=select.select([p.stdout],[],[],max(0,deadline-time.monotonic()))
        if not r:break
        chunk=os.read(p.stdout.fileno(),8192)
        if not chunk:break
        buffer.extend(chunk)
        while len(buffer)>=5:
            n=struct.unpack('>I',buffer[1:5])[0]
            assert n<=65536
            if len(buffer)<5+n:break
            t=bytes(buffer[:1]);payload=bytes(buffer[5:5+n]);del buffer[:5+n]
            events.append((t,payload))
            if t==b'O':output.extend(payload)
    assert predicate(),(bytes(output),events)
try:
    collect(lambda:any(t==b'H' and d==b'DPTY1' for t,d in events))
    send(b'W',b"test -t 0 && test -t 1 && test -t 2 && printf 'TTY_OK\\n'\n")
    collect(lambda:b'TTY_OK\r\n' in output)
    send(b'W',b"cd /tmp; export DAIYU_PTY_TEST=persisted\n")
    send(b'W',b"printf '\\nSTATE:%s:%s\\n' \"$PWD\" \"$DAIYU_PTY_TEST\"\n")
    collect(lambda:b'STATE:/tmp:persisted\r\n' in output)
    send(b'R',struct.pack('>HH',31,99));send(b'W',b'stty size\n')
    collect(lambda:b'31 99\r\n' in output)
    send(b'W',("printf '\\nNATIVE_PROBE:'; " + shlex.quote(str(helper)) + " --probe-tty\n").encode())
    collect(lambda:b'NATIVE_PROBE:31 99\r\n' in output)
    send(b'W',b'sleep 20\n');time.sleep(.15);send(b'I');time.sleep(.1)
    send(b'W',b"printf '\\nAFTER_INTERRUPT\\n'\n")
    collect(lambda:b'\r\nAFTER_INTERRUPT\r\n' in output)
    send(b'W',b'exit 7\n')
    collect(lambda:any(t==b'X' for t,d in events))
    statuses=[struct.unpack('>I',d)[0] for t,d in events if t==b'X']
    assert statuses==[7<<8],statuses
    assert p.wait(timeout=3)==0
    tests=['isatty 0/1/2','persistent cwd+export across writes','resize 31x99','native inherited slave dimensions 31x99','foreground interrupt shell survives','exit status exact']
finally:
    if p.poll() is None:p.kill()
    p.wait(timeout=3)
probe=subprocess.run([str(helper),'--probe-tty'],input=b'',capture_output=True,timeout=3)
assert probe.returncode==69 and not probe.stdout,probe
assert subprocess.run([str(helper),'--probe-tty','unexpected'],input=b'',capture_output=True,timeout=3).returncode==64
tests.extend(['native TTY probe refuses pipes','native TTY probe rejects extra arguments'])
# Reject oversized frames before allocation/dispatch, terminate the owned shell.
p=subprocess.Popen([str(helper),'24','80','--','/bin/sh'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
try:
    p.stdin.write(b'W'+struct.pack('>I',65537));p.stdin.flush()
    assert p.wait(timeout=3)==74
    tests.append('oversized frame rejected with cleanup')
finally:
    if p.poll() is None:p.kill()
    p.wait(timeout=3)
record={'CHECK_ID':'terminal-r09-pty-host','environment':'Debian cc/Linux PTY; not Android ABI evidence','source_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'root_scope_header_sha256':hashlib.sha256(source.with_name('daiyu_root_scope.h').read_bytes()).hexdigest(),'tests':tests,'exit_code':0,'output':output.decode(errors='replace')}

# Child exits while a background job retains the slave: wait status must stay bounded.
p=subprocess.Popen([str(helper),'24','80','--','/bin/sh','-c','sleep 20 & exit 4'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
try:
    p.wait(timeout=3); data=p.stdout.read()
    assert p.returncode==0
    frames=[]
    while data:
        assert len(data)>=5
        n=struct.unpack('>I',data[1:5])[0]; assert len(data)>=5+n
        frames.append((data[:1],data[5:5+n]));data=data[5+n:]
    assert [struct.unpack('>I',v)[0] for t,v in frames if t==b'X']==[4<<8]
    tests.append('background slave holder cannot withhold exit status')
finally:
    if p.poll() is None:p.kill()
    p.wait(timeout=3)
# Fill PTY input while foreground does not read it, then interrupt via independent control parsing.
p=subprocess.Popen([str(helper),'24','80','--','/bin/sh','-c','stty -echo -icanon; sleep 20'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
try:
    import threading
    drained=[]
    def drain():
        while True:
            chunk=p.stdout.read(8192)
            if not chunk:break
            drained.append(chunk)
    reader=threading.Thread(target=drain,daemon=True); reader.start()
    time.sleep(.1)
    p.stdin.write(b'W'+struct.pack('>I',65536)+b'x'*65536+b'I'+struct.pack('>I',0));p.stdin.flush()
    assert p.wait(timeout=3)==0
    reader.join(1)
    tests.append('interrupt parsed while PTY input is backpressured')
finally:
    if p.poll() is None:p.kill()
    p.wait(timeout=3)
faults=subprocess.run([str(helper.with_name('root_scope_faults'))],check=True,capture_output=True,text=True,timeout=5)
assert faults.stdout.strip()=='root_scope_faults: 13 passed',faults.stdout
tests.extend(['pre-scope uid refusal emits only F with exit 77',
    'pre-scope invalid arguments emit only F with exit 64',
    'pre-scope invalid dimensions emit only F with exit 64',
    'pre-scope PTY open failure emits only F with exit 70',
    'pre-scope window sizing failure emits only F with exit 70',
    'non-root invalid arguments preserve empty stdout and exit 64',
    'failed F flush preserves failure and cannot produce a cleanup receipt',
    'closed pipe reader cannot terminate pre-scope failure via SIGPIPE',
    'root scope open failure removes its empty group','root scope fstat failure removes its empty group',
    'root scope removal retries EINTR','failed scope removal reports S/E without drain',
    'pre-hello launch failure sends S/E/D and removes scope'])
record['tests']=tests
(out/'check.json').write_text(json.dumps(record,indent=2));print(json.dumps({k:record[k] for k in ['CHECK_ID','tests','exit_code']},indent=2))
