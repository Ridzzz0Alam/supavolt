#!/bin/sh
# Writes the API's local secrets file, outside the repository, with fresh random values.
# The API reads it from $SUPAVOLT_SECRETS_FILE, default ~/.config/supavolt/secrets.yml.
# Refuses to overwrite: rotating these signs everyone out and orphans encrypted project secrets.
set -eu

target="${SUPAVOLT_SECRETS_FILE:-$HOME/.config/supavolt/secrets.yml}"

if [ -e "$target" ]; then
  echo "$target already exists; delete it first if you really want new secrets." >&2
  exit 1
fi

mkdir -p "$(dirname "$target")"
umask 077
access_secret="$(openssl rand -hex 32)"

cat > "$target" <<YAML
supavolt:
  jwt:
    access-secret: $access_secret
    refresh-secret: $(openssl rand -hex 32)
  project-keys:
    secret: $(openssl rand -hex 32)
  invites:
    secret: $(openssl rand -hex 32)
  secrets:
    encryption-key: $(openssl rand -hex 32)
  # The local storage container's credentials (docker-compose.yml), not real secrets.
  storage:
    access-key-id: supavolt
    secret-access-key: supavolt123
YAML

echo "Wrote $target"
echo
echo "Put this in frontend/.env.local so the dashboard middleware can verify sessions:"
echo "JWT_ACCESS_SECRET=$access_secret"
