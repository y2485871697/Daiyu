from pathlib import Path
import re,shlex,paramiko,base64

def connect(known_hosts):
 lines=Path('/workspace/ssh账户密码').read_text().splitlines()
 commands=[shlex.split(s.strip()) for s in lines if re.match(r'^\s*ssh\s',s)]
 passwords=[re.split(r'[:：]',s,maxsplit=1)[1].strip() for s in lines if re.match(r'^\s*(密码|password)\s*[:：]',s,re.I)]
 if len(commands)!=1 or len(passwords)!=1: raise ValueError('credential_fields_invalid')
 cmd=commands[0]
 if cmd.count('-p')!=1:raise ValueError('port_required')
 port=int(cmd[cmd.index('-p')+1]);targets=[s for s in cmd[1:] if '@' in s]
 if len(targets)!=1:raise ValueError('target_invalid')
 user,host=targets[0].split('@',1)
 c=paramiko.SSHClient();c.load_host_keys(str(known_hosts));c.set_missing_host_key_policy(paramiko.RejectPolicy())
 c.connect(host,port=port,username=user,password=passwords[0],timeout=8,auth_timeout=8,banner_timeout=8,look_for_keys=False,allow_agent=False)
 return c

def execute(c,script):
 pre="$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue'; Set-Location -LiteralPath 'E:\\daiyu'; "
 encoded=base64.b64encode((pre+script).encode('utf-16-le')).decode()
 _,o,e=c.exec_command('powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand '+encoded,timeout=600)
 out=o.read().decode('utf-8',errors='replace');err=e.read().decode('utf-8',errors='replace')
 return o.channel.recv_exit_status(),out,err
