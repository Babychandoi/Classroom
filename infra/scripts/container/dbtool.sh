#!/bin/sh
# R14-08: runs INSIDE the mysql / mongodb containers (copied in by backup/restore, executed with
# `docker exec`, removed afterwards). It exists so that database passwords never appear in any
# process argument list (`docker top`, `ps`, `/proc/<pid>/cmdline`) and never need shell quoting on
# the host:
#   - credentials come from the container's own environment (the same variables compose passed to
#     the image: MYSQL_USER/MYSQL_PASSWORD/MYSQL_DATABASE, MONGO_INITDB_ROOT_*), not from the host;
#   - MySQL clients read the password from the MYSQL_PWD environment variable;
#   - mongodump/mongorestore read the password from a 0600 --config file created here and deleted on
#     exit (it is written with printf from the environment, so no quoting of the password is needed
#     beyond YAML single-quote doubling, done below).
#
# Usage (arguments are file paths inside the container):
#   dbtool.sh mysql-dump    OUT_SQL
#   dbtool.sh mysql-restore IN_SQL
#   dbtool.sh mongo-dump    OUT_ARCHIVE_GZ
#   dbtool.sh mongo-restore IN_ARCHIVE_GZ
set -eu
umask 077

usage() {
  echo "usage: dbtool.sh {mysql-dump|mysql-restore|mongo-dump|mongo-restore} FILE" >&2
  exit 64
}

[ "$#" -eq 2 ] || usage
action="$1"
file="$2"

need() {
  # need VAR_NAME: fail clearly if the container does not have the expected variable.
  eval "value=\${$1:-}"
  if [ -z "$value" ]; then
    echo "dbtool.sh: required environment variable $1 is not set in this container" >&2
    exit 65
  fi
}

# Writes a mongo-tools --config file holding only the password, echoes its path.
mongo_config() {
  cfg="$(mktemp /tmp/dbtool-mongo.XXXXXX)"
  # YAML single-quoted scalar: a literal quote is written as two quotes; nothing else is special.
  esc="$(printf '%s' "$MONGO_INITDB_ROOT_PASSWORD" | sed "s/'/''/g")"
  printf "password: '%s'\n" "$esc" > "$cfg"
  printf '%s' "$cfg"
}

case "$action" in
  mysql-dump)
    need MYSQL_USER; need MYSQL_PASSWORD; need MYSQL_DATABASE
    MYSQL_PWD="$MYSQL_PASSWORD"; export MYSQL_PWD
    mysqldump -u"$MYSQL_USER" --single-transaction --no-tablespaces --routines --triggers \
      --default-character-set=utf8mb4 --result-file="$file" "$MYSQL_DATABASE"
    ;;

  mysql-restore)
    need MYSQL_USER; need MYSQL_PASSWORD; need MYSQL_DATABASE
    MYSQL_PWD="$MYSQL_PASSWORD"; export MYSQL_PWD
    # Full restore: drop every table/view of the target database first, so objects that exist only
    # in the live schema (e.g. created by a Flyway migration newer than the backup) cannot survive
    # and make the restored flyway_schema_history disagree with the real schema.
    objects="$(mysql -u"$MYSQL_USER" -N -B \
      -e "SELECT CONCAT('DROP ', IF(table_type='VIEW','VIEW','TABLE'), ' IF EXISTS \`', table_name, '\`;') FROM information_schema.tables WHERE table_schema = DATABASE()" \
      "$MYSQL_DATABASE")"
    if [ -n "$objects" ]; then
      { echo "SET FOREIGN_KEY_CHECKS=0;"; printf '%s\n' "$objects"; echo "SET FOREIGN_KEY_CHECKS=1;"; } \
        | mysql -u"$MYSQL_USER" --default-character-set=utf8mb4 "$MYSQL_DATABASE"
    fi
    mysql -u"$MYSQL_USER" --default-character-set=utf8mb4 "$MYSQL_DATABASE" < "$file"
    ;;

  mongo-dump)
    need MONGO_INITDB_ROOT_USERNAME; need MONGO_INITDB_ROOT_PASSWORD; need MONGO_INITDB_DATABASE
    cfg="$(mongo_config)"
    trap 'rm -f "$cfg"' EXIT
    mongodump --username="$MONGO_INITDB_ROOT_USERNAME" --authenticationDatabase=admin \
      --config="$cfg" --db="$MONGO_INITDB_DATABASE" --archive="$file" --gzip
    ;;

  mongo-restore)
    need MONGO_INITDB_ROOT_USERNAME; need MONGO_INITDB_ROOT_PASSWORD; need MONGO_INITDB_DATABASE
    cfg="$(mongo_config)"
    trap 'rm -f "$cfg"' EXIT
    mongorestore --username="$MONGO_INITDB_ROOT_USERNAME" --authenticationDatabase=admin \
      --config="$cfg" --archive="$file" --gzip --drop
    ;;

  *)
    usage
    ;;
esac
