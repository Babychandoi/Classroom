"""Authenticated broker. Requests cannot choose Docker configuration, images or mounts."""
import base64, hashlib, hmac, http.client, json, os, socket, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TOKEN = os.environ.get('ASSESSMENT_SANDBOX_TOKEN', '')
if len(TOKEN) < 32:
    raise RuntimeError('A separate sandbox token of at least 32 characters is required')
SLOTS = threading.BoundedSemaphore(2)
IMAGES = {'python': 'python:3.13-alpine', 'javascript': 'node:22-alpine'}

class DockerConnection(http.client.HTTPConnection):
    def __init__(self):
        super().__init__('localhost', timeout=5)
    def connect(self):
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.settimeout(self.timeout)
        self.sock.connect('/var/run/docker.sock')

def docker(method, path, body=None):
    connection = DockerConnection()
    try:
        connection.request(method, '/v1.45' + path, None if body is None else json.dumps(body), {'Content-Type': 'application/json'})
        response = connection.getresponse()
        data = response.read(131072)
        if response.status >= 400:
            raise RuntimeError('Sandbox container operation failed')
        return data
    finally:
        connection.close()

PYTHON_WRAPPER = '''import base64,json,subprocess,resource,os
p=json.loads(base64.b64decode(os.environ['TASK']))
open('/tmp/main.py','w').write(p['code'])
def limits():
 resource.setrlimit(resource.RLIMIT_FSIZE,(65536,65536))
with open('/tmp/output','wb') as out:
 try:
  r=subprocess.run(['python','-I','/tmp/main.py'],input=p['input'].encode(),stdout=out,stderr=subprocess.STDOUT,timeout=2,preexec_fn=limits)
  result={'output':open('/tmp/output').read()[:65536],'exitCode':r.returncode}
 except subprocess.TimeoutExpired: result={'output':'Time limit exceeded','exitCode':124}
print(json.dumps(result))
'''
NODE_WRAPPER = '''const fs=require('fs'),cp=require('child_process');
const p=JSON.parse(Buffer.from(process.env.TASK,'base64').toString());
fs.writeFileSync('/tmp/main.js',p.code);
const r=cp.spawnSync('node',['--max-old-space-size=64','/tmp/main.js'],{input:p.input,encoding:'utf8',timeout:2000,maxBuffer:65536,env:{PATH:'/usr/local/bin:/usr/bin:/bin'}});
console.log(JSON.stringify({output:(r.stdout||'')+(r.stderr||''),exitCode:r.error?124:r.status}));
'''

def run_test(language, code, input_text):
    task = base64.b64encode(json.dumps({'code': code, 'input': input_text}).encode()).decode()
    command = ['python', '-I', '-c', PYTHON_WRAPPER] if language == 'python' else ['node', '--max-old-space-size=64', '-e', NODE_WRAPPER]
    config = {'Image': IMAGES[language], 'Cmd': command, 'Env': ['TASK=' + task], 'User': '65534:65534',
              'WorkingDir': '/tmp', 'NetworkDisabled': True, 'Labels': {'classroom.assessment.sandbox': 'true'},
              'HostConfig': {'NetworkMode': 'none', 'ReadonlyRootfs': True, 'Memory': 134217728,
                'MemorySwap': 134217728, 'NanoCpus': 500000000, 'PidsLimit': 32, 'CapDrop': ['ALL'],
                'SecurityOpt': ['no-new-privileges:true'], 'Tmpfs': {'/tmp': 'rw,noexec,nosuid,size=16m,mode=1777'},
                'LogConfig': {'Type': 'json-file', 'Config': {'max-size': '128k', 'max-file': '1'}}}}
    ident = json.loads(docker('POST', '/containers/create', config))['Id']
    try:
        docker('POST', '/containers/' + ident + '/start')
        deadline = time.monotonic() + 4
        while time.monotonic() < deadline:
            state = json.loads(docker('GET', '/containers/' + ident + '/json'))['State']
            if not state['Running']:
                break
            time.sleep(.05)
        else:
            docker('POST', '/containers/' + ident + '/kill')
            return {'output': 'Time limit exceeded', 'exitCode': 124}
        raw = docker('GET', '/containers/' + ident + '/logs?stdout=1&stderr=1')
        chunks = []
        while len(raw) >= 8:
            size = int.from_bytes(raw[4:8], 'big'); chunks.append(raw[8:8+size]); raw = raw[8+size:]
        output = b''.join(chunks).decode('utf-8', 'replace')[-65536:]
        try:
            result = json.loads(output.strip().splitlines()[-1])
            return {'output': str(result['output'])[:65536], 'exitCode': result['exitCode']}
        except (ValueError, IndexError, KeyError):
            return {'output': 'Execution failed or resource limit exceeded', 'exitCode': state['ExitCode'] or 1}
    finally:
        docker('DELETE', '/containers/' + ident + '?force=1&v=1')

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass
    def reply(self, status, value):
        data = json.dumps(value).encode(); self.send_response(status)
        self.send_header('Content-Type', 'application/json'); self.send_header('Content-Length', str(len(data)))
        self.end_headers(); self.wfile.write(data)
    def do_GET(self):
        self.reply(200 if self.path == '/health' else 404, {'ready': self.path == '/health'})
    def do_POST(self):
        if self.path != '/execute' or not hmac.compare_digest(self.headers.get('Authorization',''), 'Bearer ' + TOKEN):
            return self.reply(403, {'error': 'Forbidden'})
        try:
            length = int(self.headers.get('Content-Length', '0'))
            if not 0 < length <= 500000: raise ValueError()
            self.connection.settimeout(5)
            body = json.loads(self.rfile.read(length))
            language, code, tests = body['language'], body['code'], body['tests']
            if language not in IMAGES or not isinstance(code,str) or not 0 < len(code) <= 20000 or not isinstance(tests,list) or not 1 <= len(tests) <= 20: raise ValueError()
            if any(not isinstance(t,dict) or not isinstance(t.get('input'),str) or not isinstance(t.get('output'),str) or len(t['input'])>10000 or len(t['output'])>10000 for t in tests): raise ValueError()
        except (ValueError, KeyError, TypeError):
            return self.reply(400, {'error': 'Invalid task'})
        if not SLOTS.acquire(blocking=False): return self.reply(429, {'error': 'Sandbox busy'})
        try:
            results = []
            for test in tests:
                result = run_test(language,code,test['input'])
                results.append({'passed':result['exitCode']==0 and result['output'].replace('\r\n','\n').strip()==test['output'].replace('\r\n','\n').strip(), **result})
            self.reply(200, {'results':results})
        except Exception:
            self.reply(503, {'error':'Sandbox unavailable'})
        finally:
            SLOTS.release()

ThreadingHTTPServer(('0.0.0.0',8081),Handler).serve_forever()
