"""Rotate persisted datastore credentials in one verified Classroom Compose project.

Preview by default. Apply requires a verified four-store backup for the persistent
server; the explicit disposable QA project can run a drill. Application images,
JWT/webhook keys, ports, volumes and other runtime settings are preserved.
Recovery overlays and credentials inherit a private Windows ACL under .artifacts.
"""
import argparse
import datetime
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
from urllib.parse import quote, urlsplit

root = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--project-name', required=True, choices=['classroom-demo', 'classroom-assessment-qa'])
parser.add_argument('--env-file', type=Path, required=True)
parser.add_argument('--sync-env-file', type=Path, action='append', default=[])
parser.add_argument('--compose-file', type=Path, action='append', required=True)
parser.add_argument('--backup-dir', type=Path)
parser.add_argument('--disposable-drill', action='store_true')
parser.add_argument('--apply', action='store_true')
args = parser.parse_args()

def command(argv, payload=None, label='operation'):
    result = subprocess.run(argv, cwd=root, input=payload, capture_output=True)
    if result.returncode:
        raise RuntimeError(label + ' failed; credentials and database output withheld')
    return result.stdout

def inspect(service):
    ids = command(['docker', 'ps', '-aq', '--filter', 'label=com.docker.compose.project=' + args.project_name,
                   '--filter', 'label=com.docker.compose.service=' + service], label='container lookup').split()
    if len(ids) != 1:
        raise RuntimeError('Expected one container for ' + service)
    data = json.loads(command(['docker', 'inspect', ids[0].decode()]))[0]
    if data['Config']['Labels']['com.docker.compose.project'] != args.project_name:
        raise RuntimeError('Container project guard failed')
    return data

services = ('mysql', 'mongodb', 'neo4j', 'minio', 'backend', 'frontend')
before = {service: inspect(service) for service in services}
envs = {service: dict(entry.split('=', 1) for entry in data['Config']['Env']) for service, data in before.items()}
app = envs['backend']
old = {
    'MYSQL_ROOT_PASSWORD': envs['mysql']['MYSQL_ROOT_PASSWORD'],
    'MYSQL_PASSWORD': envs['mysql']['MYSQL_PASSWORD'],
    'MONGO_INITDB_ROOT_PASSWORD': envs['mongodb']['MONGO_INITDB_ROOT_PASSWORD'],
    'NEO4J_PASSWORD': envs['neo4j']['NEO4J_AUTH'].partition('/')[2],
    'MINIO_ROOT_PASSWORD': envs['minio']['MINIO_ROOT_PASSWORD'],
}
if any('\n' in value or '\r' in value for value in old.values()):
    raise RuntimeError('Multiline datastore passwords are unsupported')
if app['SPRING_DATASOURCE_PASSWORD'] != old['MYSQL_PASSWORD'] or app['MINIO_SECRET_KEY'] != old['MINIO_ROOT_PASSWORD']:
    raise RuntimeError('Application/datastore credentials differ before rotation')
mongo_uri = urlsplit(app['SPRING_DATA_MONGODB_URI'])
if mongo_uri.password != old['MONGO_INITDB_ROOT_PASSWORD'] or app['SPRING_NEO4J_AUTHENTICATION_PASSWORD'] != old['NEO4J_PASSWORD']:
    raise RuntimeError('Application/document/graph credentials differ before rotation')
env_file = args.env_file.resolve(strict=True)
compose_files = [path.resolve(strict=True) for path in args.compose_file]
sync_files = [path.resolve(strict=True) for path in args.sync_env_file]
if len({env_file, *sync_files}) != 1 + len(sync_files):
    raise RuntimeError('Duplicate environment file targets')
print(json.dumps({'mode': 'apply' if args.apply else 'preview', 'project': args.project_name,
                  'datastoreSecrets': 5, 'datastoreVolumes': sum(len(before[s]['Mounts']) for s in services[:4]),
                  'backendImage': before['backend']['Config']['Image'], 'jwtAndWebhookPreserved': True}), flush=True)
if not args.apply:
    raise SystemExit(0)
if args.disposable_drill and args.project_name != 'classroom-assessment-qa':
    raise RuntimeError('Disposable mode is only permitted for the explicit QA project')
if not args.disposable_drill:
    if not args.backup_dir:
        raise RuntimeError('A verified four-store backup is required')
    backup = args.backup_dir.resolve(strict=True)
    manifest = json.loads((backup / 'backup.json').read_text(encoding='utf-8-sig'))
    if manifest['project'] != args.project_name or len(manifest['components']) != 4:
        raise RuntimeError('Backup must belong to the target project and contain all four stores')
    command(['powershell', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
             str(root / 'infra/scripts/restore.ps1'), '-BackupDir', str(backup), '-VerifyOnly'], label='backup verification')

stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%d-%H%M%S')
private = root / '.artifacts' / ('datastore-rotation-' + args.project_name + '-' + stamp)
private.mkdir(mode=0o700)
if os.name == 'nt':
    identity = command(['whoami']).decode().strip()
    command(['icacls', str(private), '/inheritance:r', '/grant:r', identity + ':(OI)(CI)F',
             '*S-1-5-18:(OI)(CI)F'], label='private recovery ACL')
new = {key: secrets.token_hex(32) for key in old}
new_envs = {key: dict(value) for key, value in envs.items()}
new_envs['mysql'].update({key: new[key] for key in ('MYSQL_ROOT_PASSWORD', 'MYSQL_PASSWORD')})
new_envs['mongodb']['MONGO_INITDB_ROOT_PASSWORD'] = new['MONGO_INITDB_ROOT_PASSWORD']
new_envs['neo4j']['NEO4J_AUTH'] = 'neo4j/' + new['NEO4J_PASSWORD']
new_envs['minio']['MINIO_ROOT_PASSWORD'] = new['MINIO_ROOT_PASSWORD']
new_envs['backend'].update({
    'SPRING_DATASOURCE_PASSWORD': new['MYSQL_PASSWORD'],
    'SPRING_NEO4J_AUTHENTICATION_PASSWORD': new['NEO4J_PASSWORD'],
    'MINIO_SECRET_KEY': new['MINIO_ROOT_PASSWORD'],
    'SPRING_DATA_MONGODB_URI': 'mongodb://' + quote(mongo_uri.username, safe='') + ':'
        + new['MONGO_INITDB_ROOT_PASSWORD'] + '@' + app['SPRING_DATA_MONGODB_URI'].split('@', 1)[1],
})
base_values = dict(entry.split('=', 1) for entry in env_file.read_text(encoding='utf-8-sig').splitlines()
                   if '=' in entry and not entry.lstrip().startswith('#'))
runtime_values = {
    'MYSQL_DATABASE': envs['mysql']['MYSQL_DATABASE'], 'MYSQL_USER': envs['mysql']['MYSQL_USER'],
    'MONGO_INITDB_ROOT_USERNAME': envs['mongodb']['MONGO_INITDB_ROOT_USERNAME'],
    'MONGO_INITDB_DATABASE': envs['mongodb']['MONGO_INITDB_DATABASE'],
    'MINIO_ROOT_USER': envs['minio']['MINIO_ROOT_USER'],
    'MINIO_EXTERNAL_ENDPOINT': app['MINIO_EXTERNAL_ENDPOINT'], 'MINIO_BUCKET': app['MINIO_BUCKET'],
}
for key in ('JWT_SECRET', 'MOCK_PAYMENT_WEBHOOK_SECRET', 'ASSESSMENT_SANDBOX_TOKEN', 'APP_COOKIE_SECURE',
            'DEMO_SEED_ENABLED', 'PAYMENT_SANDBOX_ENABLED', 'MOCK_PAYMENT_CHECKOUT_ENABLED', 'APP_CORS_ALLOWED_ORIGINS'):
    if key in app:
        runtime_values[key] = app[key]
port_keys = {'mysql': {'3306/tcp': 'MYSQL_PORT'}, 'mongodb': {'27017/tcp': 'MONGO_PORT'},
             'neo4j': {'7474/tcp': 'NEO4J_HTTP_PORT', '7687/tcp': 'NEO4J_BOLT_PORT'},
             'minio': {'9000/tcp': 'MINIO_PORT', '9001/tcp': 'MINIO_CONSOLE_PORT'},
             'backend': {'8080/tcp': 'BACKEND_PORT'}, 'frontend': {'80/tcp': 'FRONTEND_PORT'}}
for service, ports in port_keys.items():
    for container_port, key in ports.items():
        runtime_values[key] = before[service]['HostConfig']['PortBindings'][container_port][0]['HostPort']

def save_env(path, secret_values):
    values = {**base_values, **runtime_values, **secret_values}
    path.write_text('\n'.join(key + '=' + value for key, value in values.items()) + '\n', encoding='utf-8')

save_env(private / 'new.env', new)
save_env(private / 'old.env', old)
for index, path in enumerate([env_file, *sync_files]):
    shutil.copyfile(path, private / ('original-' + str(index) + '.env'))

def save_overlay(path, environments):
    overlay = {'services': {}, 'volumes': {}}
    for service in services:
        data = before[service]
        spec = {'image': data['Config']['Image'], 'environment': {key: value.replace('$', '$$') for key, value in environments[service].items()}}
        mounts = []
        for mount in data['Mounts']:
            if mount['Type'] != 'volume':
                raise RuntimeError('Only existing volume mounts are supported')
            name = mount['Name']
            overlay['volumes'][name] = {'external': True, 'name': name}
            mounts.append(name + ':' + mount['Destination'] + (':ro' if not mount['RW'] else ''))
        if mounts:
            spec['volumes'] = mounts
        overlay['services'][service] = spec
    path.write_text(json.dumps(overlay, indent=2), encoding='utf-8')

save_overlay(private / 'new.json', new_envs)
save_overlay(private / 'old.json', envs)
changed = []
failure_reason = None

def progress(status):
    (private / 'recovery.json').write_text(json.dumps({'project': args.project_name, 'status': status,
        'databasesChanged': changed, 'failureReason': failure_reason,
        'originalContainers': {s: before[s]['Id'] for s in services}}, indent=2), encoding='utf-8')

def compose(which, extra):
    argv = ['docker', 'compose', '-p', args.project_name, '--env-file', str(private / (which + '.env'))]
    for path in compose_files:
        argv += ['-f', str(path)]
    argv += ['-f', str(private / (which + '.json'))]
    return command(argv + extra, label='Compose ' + extra[0])

compose('new', ['config', '--quiet'])
compose('old', ['config', '--quiet'])

def sql_quote(value):
    return "'" + value.replace('\\', '\\\\').replace("'", "''") + "'"

def mysql(password, sql):
    return command(['docker', 'exec', '-i', before['mysql']['Name'], 'sh', '-c',
        'IFS= read -r rotation_pwd; export MYSQL_PWD="$rotation_pwd"; exec mysql -uroot -N -B'],
        (password + '\n' + sql).encode(), 'MySQL authenticated operation')

user = envs['mysql']['MYSQL_USER']
if not re.fullmatch(r'[A-Za-z0-9_]+', user):
    raise RuntimeError('Unsupported MySQL account name')
hosts = mysql(old['MYSQL_ROOT_PASSWORD'], "SELECT Host FROM mysql.user WHERE User='root';").decode().splitlines()
if not hosts or any(not re.fullmatch(r'[A-Za-z0-9.%:_-]+', host) for host in hosts):
    raise RuntimeError('Unsupported MySQL root hosts')
database = envs['mysql']['MYSQL_DATABASE']
if not re.fullmatch(r'[A-Za-z0-9_]+', database):
    raise RuntimeError('Unsupported database name')
counts_sql = 'SELECT (SELECT COUNT(*) FROM ' + database + '.users),(SELECT COUNT(*) FROM ' + database + '.classrooms),(SELECT COUNT(*) FROM ' + database + '.assessment_attempts);'

def change_mysql(current, target):
    statements = ['ALTER USER ' + sql_quote(user) + "@'%' IDENTIFIED BY " + sql_quote(target['MYSQL_PASSWORD']) + ';']
    statements += ['ALTER USER \'root\'@' + sql_quote(host) + ' IDENTIFIED BY ' + sql_quote(target['MYSQL_ROOT_PASSWORD']) + ';' for host in hosts]
    mysql(current['MYSQL_ROOT_PASSWORD'], '\n'.join(statements))

def mongo(current, target=None):
    username = envs['mongodb']['MONGO_INITDB_ROOT_USERNAME']
    code = 'const admin=db.getSiblingDB("admin"); if(!admin.auth(' + json.dumps(username) + ',' + json.dumps(current['MONGO_INITDB_ROOT_PASSWORD']) + ')) quit(1); '
    if target:
        code += 'admin.changeUserPassword(' + json.dumps(username) + ',' + json.dumps(target['MONGO_INITDB_ROOT_PASSWORD']) + '); '
    code += 'quit(0);'
    command(['docker', 'exec', '-i', before['mongodb']['Name'], 'mongosh', '--quiet', '--file', '/dev/stdin'], code.encode(), 'Mongo authenticated operation')

def neo4j(current, target=None):
    cypher = 'RETURN 1;'
    if target:
        cypher = 'ALTER CURRENT USER SET PASSWORD FROM ' + sql_quote(current['NEO4J_PASSWORD']) + ' TO ' + sql_quote(target['NEO4J_PASSWORD']) + ';'
    command(['docker', 'exec', '-i', before['neo4j']['Name'], 'sh', '-c',
        'IFS= read -r rotation_pwd; export NEO4J_PASSWORD="$rotation_pwd"; exec cypher-shell -u neo4j --non-interactive'],
        (current['NEO4J_PASSWORD'] + '\n' + cypher).encode(), 'Neo4j authenticated operation')

def minio(which):
    networks = list(before['minio']['NetworkSettings']['Networks'])
    if len(networks) != 1 or not networks[0].startswith(args.project_name + '_'):
        raise RuntimeError('MinIO network project guard failed')
    command(['docker', 'run', '--rm', '--network', networks[0], '--env-file', str(private / (which + '.env')),
        '--entrypoint', '/bin/sh', 'classroom/mc:RELEASE.2024-05-09T17-04-24Z', '-c',
        'mc alias set rotation http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null && mc stat "rotation/$MINIO_BUCKET" >/dev/null'],
        label='MinIO authenticated operation')

progress('prepared')
try:
    command(['docker', 'stop', before['backend']['Name']], label='stop application writer')
    counts_before = mysql(old['MYSQL_ROOT_PASSWORD'], counts_sql)
    for service, rotate in (('mysql', change_mysql), ('mongodb', mongo), ('neo4j', neo4j)):
        rotate(old, new)
        changed.append(service)
        progress('database-passwords-changing')
    compose('new', ['up', '-d', '--no-deps', '--no-build', '--force-recreate', '--wait', '--wait-timeout', '240', 'mysql', 'mongodb', 'neo4j', 'minio'])
    if mysql(new['MYSQL_ROOT_PASSWORD'], counts_sql) != counts_before:
        raise RuntimeError('Persistent row counts changed during rotation')
    mongo(new)
    neo4j(new)
    minio('new')
    for service, authenticate in (('mysql', lambda: mysql(old['MYSQL_ROOT_PASSWORD'], 'SELECT 1;')),
            ('mongodb', lambda: mongo(old)), ('neo4j', lambda: neo4j(old)), ('minio', lambda: minio('old'))):
        progress('verifying-previous-credential-rejected-' + service)
        try:
            authenticate()
        except RuntimeError:
            pass
        else:
            raise RuntimeError('Previous datastore credential remains usable: ' + service)
    for service in services[:4]:
        data = inspect(service)
        if {(m['Name'], m['Destination']) for m in data['Mounts']} != {(m['Name'], m['Destination']) for m in before[service]['Mounts']}:
            raise RuntimeError('Persistent volume preservation guard failed')
    compose('new', ['up', '-d', '--no-deps', '--no-build', '--force-recreate', '--wait', '--wait-timeout', '300', 'backend'])
    for path in [env_file, *sync_files]:
        shutil.copyfile(private / 'new.env', path)
        if os.name == 'nt':
            command(['icacls', str(path), '/inheritance:r', '/grant:r', identity + ':(F)', '*S-1-5-18:(F)'], label='environment ACL')
    progress('complete')
    summary = {'project': args.project_name, 'result': 'PASS', 'secretsRotated': 5, 'secretLength': 64,
               'imagesPreserved': True, 'volumesPreserved': True, 'rowCountsPreserved': True,
               'jwtAndWebhookPreserved': True, 'previousDatabaseCredentialsRejected': True,
               'recoveryDirectory': str(private.relative_to(root))}
    (private / 'verification.json').write_text(json.dumps(summary, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(summary), flush=True)
except Exception as rotation_error:
    failure_reason = str(rotation_error) if isinstance(rotation_error, RuntimeError) else type(rotation_error).__name__
    progress('rollback-starting')
    try:
        for service, rotate in reversed((('mysql', change_mysql), ('mongodb', mongo), ('neo4j', neo4j))):
            if service in changed:
                rotate(new, old)
        compose('old', ['up', '-d', '--no-deps', '--no-build', '--force-recreate', '--wait', '--wait-timeout', '240', 'mysql', 'mongodb', 'neo4j', 'minio'])
        compose('old', ['up', '-d', '--no-deps', '--no-build', '--force-recreate', '--wait', '--wait-timeout', '300', 'backend'])
        for index, path in enumerate([env_file, *sync_files]):
            shutil.copyfile(private / ('original-' + str(index) + '.env'), path)
        progress('rolled-back')
    except Exception:
        command(['docker', 'stop', before['backend']['Name']], label='stop application after incomplete recovery')
        progress('manual-recovery-required')
        raise RuntimeError('Rotation/recovery incomplete; backend stopped. Use protected recovery directory.') from None
    raise RuntimeError('Rotation failed and previous credentials/runtime were restored. Inspect protected recovery metadata.') from None
