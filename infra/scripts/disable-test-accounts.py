"""Disable only identifiable E2E/load fixtures on the main server after verification.

Preview by default. --apply archives their active classes, disables login, revokes
refresh sessions and records audits. All learning/payment records are retained.
Previous account/class states are saved in the protected .artifacts/server folder.
"""
import argparse
import datetime
import json
from pathlib import Path
import subprocess
import uuid

parser = argparse.ArgumentParser()
parser.add_argument('--apply', action='store_true')
args = parser.parse_args()
repo = Path(__file__).resolve().parents[2]
container = json.loads(subprocess.check_output(['docker', 'inspect', 'classroom-mysql']))[0]
if container['Config']['Labels'].get('com.docker.compose.project') != 'online-classroom':
    raise SystemExit('Refusing a container outside the main classroom project.')

def sql(statement):
    result = subprocess.run(['docker', 'exec', '-i', 'classroom-mysql', 'sh', '-c',
        'MYSQL_PWD=$MYSQL_PASSWORD exec mysql -u$MYSQL_USER $MYSQL_DATABASE -N -B'],
        input=statement.encode('utf-8'), capture_output=True)
    if result.returncode:
        raise SystemExit('Fixture maintenance failed; no database output or credentials printed.')
    return result.stdout.decode('utf-8').strip()

# Both a reserved example.com address and the exact test naming conventions are required.
predicate = """role='USER' AND status='ACTIVE' AND (
 (email REGEXP '^lt[.](owner[0-9]+|s[0-9]+|nat[0-9]+|bf)[.][a-z0-9]+@example[.]com$'
  AND (full_name LIKE 'LoadTest %' OR full_name LIKE 'NAT %' OR full_name='Brute force target'))
 OR (email REGEXP '^e2e[.][a-zA-Z0-9.-]+@example[.]com$' AND full_name LIKE 'E2E %'))"""
users = [json.loads(line) for line in sql("SELECT JSON_OBJECT('id',id,'status',status) FROM users WHERE " + predicate + ';').splitlines()]
if any(str(uuid.UUID(record['id'])) != record['id'] for record in users):
    raise SystemExit('Refusing non-UUID fixture identifiers.')
ids = ','.join("'" + u['id'] + "'" for u in users)
classes = [json.loads(line) for line in sql("SELECT JSON_OBJECT('id',id,'status',status) FROM classrooms WHERE status='ACTIVE' AND owner_id IN (" + ids + ');').splitlines()] if ids else []
if any(str(uuid.UUID(record['id'])) != record['id'] for record in classes):
    raise SystemExit('Refusing non-UUID fixture identifiers.')
print(json.dumps({'mode': 'apply' if args.apply else 'preview', 'accounts': len(users), 'activeClasses': len(classes)}))
if not args.apply or not users:
    raise SystemExit(0)
state = repo / '.artifacts/server' / ('fixture-states-' + datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%d-%H%M%S') + '.json')
if not state.parent.is_dir():
    raise SystemExit('Prepare the protected server artifact directory first.')
with state.open('x', encoding='utf-8') as output:
    json.dump({'users': users, 'classes': classes}, output, indent=2)
class_ids = ','.join("'" + c['id'] + "'" for c in classes)
statements = ['START TRANSACTION;', "SET @fixture_actor = (SELECT id FROM users WHERE email='admin@classroom.local' AND role='PLATFORM_ADMIN');"]
if class_ids:
    statements += ["INSERT INTO audit_events(id,class_id,actor_id,action,target_type,target_id,details_json,created_at) "
        "SELECT UUID(),id,@fixture_actor,'TEST_FIXTURE_ARCHIVE','CLASS',id,JSON_OBJECT('reason','Local verification fixture archived'),UTC_TIMESTAMP() "
        "FROM classrooms WHERE id IN (" + class_ids + ") AND status='ACTIVE' AND owner_id IN (" + ids + ');',
        "UPDATE classrooms SET status='ARCHIVED',updated_at=UTC_TIMESTAMP() WHERE id IN (" + class_ids + ") AND status='ACTIVE' AND owner_id IN (" + ids + ');']
statements += ["INSERT INTO audit_events(id,actor_id,action,target_type,target_id,details_json,created_at) "
    "SELECT UUID(),@fixture_actor,'TEST_FIXTURE_DISABLE','USER',id,JSON_OBJECT('reason','Local verification fixture disabled'),UTC_TIMESTAMP() "
    "FROM users WHERE id IN (" + ids + ") AND (" + predicate + ');',
    "UPDATE users SET status='INACTIVE',updated_at=UTC_TIMESTAMP() WHERE id IN (" + ids + ") AND (" + predicate + ');',
    "DELETE FROM refresh_tokens WHERE user_id IN (SELECT id FROM users WHERE id IN (" + ids + ") AND ("
    + predicate.replace("status='ACTIVE'", "status='INACTIVE'") + '));', 'COMMIT;']
sql('\n'.join(statements))
assert sql('SELECT COUNT(*) FROM users WHERE id IN (' + ids + ") AND status='ACTIVE';") == '0'
print('Test fixtures disabled; records preserved, previous states saved, changes audited. Demo unchanged.')
