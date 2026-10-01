"""Rotate known seed passwords on the main server, retaining accounts and owned classes."""
import bcrypt
import json
import pathlib
import secrets
import re
import subprocess
import sys

credentials = pathlib.Path(sys.argv[1]).resolve()
reapply = len(sys.argv) > 2 and sys.argv[2] == '--reapply'
if credentials.read_text(encoding='utf-8').strip() and not reapply:
    raise SystemExit('Credentials already exist; refusing to rotate again implicitly.')
emails = ['owner@classroom.local', 'staff@classroom.local', 'student.free@classroom.local',
          'student.pro@classroom.local', 'student.expired@classroom.local', 'admin@classroom.local']
accounts = json.loads(credentials.read_text(encoding='utf-8')) if reapply else [{'email': email, 'password': secrets.token_urlsafe(32)} for email in emails]
if {a['email'] for a in accounts} != set(emails) or any(not re.fullmatch(r'[A-Za-z0-9_-]{32,100}', a['password']) for a in accounts):
    raise SystemExit('Invalid protected account data.')
# Persist protected recovery credentials before the transaction. Do not print their contents.
if not reapply:
    credentials.write_text(json.dumps(accounts, indent=2), encoding='utf-8')
sql = ['START TRANSACTION;']
for account in accounts:
    hashed = bcrypt.hashpw(account['password'].encode(), bcrypt.gensalt(rounds=12)).decode()
    email = account['email']
    sql.append(f"UPDATE users SET password_hash='{hashed}',updated_at=UTC_TIMESTAMP() WHERE email='{email}';")
sql.append("DELETE FROM refresh_tokens WHERE user_id IN (SELECT id FROM users WHERE email IN (" + ','.join("'" + e + "'" for e in emails) + '));')
sql.append('COMMIT;')
result = subprocess.run(['docker', 'exec', '-i', 'classroom-mysql', 'sh', '-c',
                         'MYSQL_PWD=$MYSQL_PASSWORD exec mysql -u$MYSQL_USER $MYSQL_DATABASE'],
                        input='\n'.join(sql), text=True, capture_output=True)
if result.returncode:
    raise SystemExit('Main account rotation failed. Preserve the protected recovery file and inspect the database.')
print('Known seed passwords rotated on the main server; account IDs, roles and classes retained. Demo unchanged.')
