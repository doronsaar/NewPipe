#!/usr/bin/env bash
# Build the release APK and publish only the latest copy at public_url.
set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$DEPLOY_DIR/.." && pwd)"
CONFIG="$DEPLOY_DIR/deployConfig.json"
KEYSTORE="$DEPLOY_DIR/newpipe-release.keystore"
KEYSTORE_PROPS="$DEPLOY_DIR/keystore.properties"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_HOME
export ANDROID_SDK_ROOT="$ANDROID_HOME"

if [[ ! -f "$CONFIG" ]]; then
  echo "deploy.sh: missing $CONFIG" >&2
  exit 1
fi

eval "$(python3 - "$CONFIG" <<'PY'
import json, shlex, sys
cfg = json.load(open(sys.argv[1], encoding="utf-8"))

def emit(name, value):
    print(f"{name}={shlex.quote(str(value))}")

for key in ("host", "user", "password", "publicUrl"):
    if not str(cfg.get(key) or "").strip():
        raise SystemExit(f"deployConfig.json is missing {key}")
emit("host", cfg["host"])
emit("user", cfg["user"])
emit("password", cfg["password"])
emit("public_url", str(cfg["publicUrl"]).rstrip("/"))
emit("ssh_port", cfg.get("sshPort") or 22)
if cfg.get("directory"):
    emit("directory", cfg["directory"])
PY
)"

if [[ ! -d "$ANDROID_HOME" ]]; then
  echo "deploy.sh: Android SDK not found at $ANDROID_HOME" >&2
  exit 1
fi

if [[ ! -f "$KEYSTORE" ]]; then
  echo "Creating a release keystore ..."
  store_password="$(python3 -c 'import secrets,string; a=string.ascii_letters+string.digits; print("".join(secrets.choice(a) for _ in range(24)))')"
  keytool -genkeypair -noprompt \
    -keystore "$KEYSTORE" \
    -alias newpipe \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$store_password" \
    -keypass "$store_password" \
    -dname "CN=NewPipe, O=NewPipe, C=US"
  umask 077
  cat >"$KEYSTORE_PROPS" <<EOF
storePassword=${store_password}
keyPassword=${store_password}
keyAlias=newpipe
EOF
  chmod 600 "$KEYSTORE" "$KEYSTORE_PROPS"
  unset store_password
fi

# shellcheck disable=SC1090
source "$KEYSTORE_PROPS"

if [[ ! -f "$ROOT/local.properties" ]]; then
  printf 'sdk.dir=%s\n' "$ANDROID_HOME" >"$ROOT/local.properties"
fi

echo "Building release APK ..."
(
  cd "$ROOT"
  ./gradlew :app:assembleRelease \
    -PnewpipeStoreFile="$KEYSTORE" \
    -PnewpipeStorePassword="$storePassword" \
    -PnewpipeKeyAlias="$keyAlias" \
    -PnewpipeKeyPassword="$keyPassword"
)

METADATA="$ROOT/app/build/outputs/apk/release/output-metadata.json"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

python3 - "$METADATA" "$STAGE" "$public_url" <<'PY'
import json, pathlib, shutil, sys
metadata_path, stage, public_url = sys.argv[1:]
metadata = json.loads(pathlib.Path(metadata_path).read_text(encoding="utf-8"))
element = metadata["elements"][0]
apk_src = pathlib.Path(metadata_path).parent / element["outputFile"]
if not apk_src.is_file():
    raise SystemExit(f"missing apk {apk_src}")
version_name = element["versionName"]
version_code = int(element["versionCode"])
shutil.copyfile(apk_src, pathlib.Path(stage) / "NewPipe.apk")
version = {
    "version": version_name,
    "version_code": version_code,
    "apk": f"{public_url}/NewPipe.apk",
}
pathlib.Path(stage, "version.json").write_text(
    json.dumps(version, indent=2) + "\n", encoding="utf-8"
)
page = f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>NewPipe {version_name}</title>
</head>
<body>
<h1>NewPipe {version_name}</h1>
<p><a href="NewPipe.apk">Download NewPipe.apk</a> (version code {version_code})</p>
</body>
</html>
"""
pathlib.Path(stage, "index.html").write_text(page, encoding="utf-8")
print(f"{version_name} {version_code} {apk_src.stat().st_size}")
PY

APKSIGNER="$(find "$ANDROID_HOME/build-tools" -name apksigner -type f | sort | tail -n 1)"
if [[ -z "$APKSIGNER" ]]; then
  echo "deploy.sh: apksigner not found under $ANDROID_HOME/build-tools" >&2
  exit 1
fi
"$APKSIGNER" verify "$STAGE/NewPipe.apk" >/dev/null

echo "Publishing latest APK to $public_url ..."
export SSHPASS="$password"
# sshpass treats -P as its own option unless the remote command is after --.
sftp_to_host() {
  sshpass -e -- sftp \
    -P "$ssh_port" \
    -o StrictHostKeyChecking=accept-new \
    -o PreferredAuthentications=password \
    -o PubkeyAuthentication=no \
    "$@" \
    "${user}@${host}"
}

listing="$(sftp_to_host <<'EOF'
ls -l
EOF
)"

batch="$STAGE/upload.batch"
{
  printf '%s\n' "$listing" | awk '
    $1 ~ /^-/ {
      name = $NF
      if (name ~ /\.apk$/) print "rm " name
    }
  '
  cat <<EOF
put $STAGE/NewPipe.apk NewPipe.apk
put $STAGE/version.json version.json
put $STAGE/index.html index.html
EOF
} >"$batch"

# Feed commands on stdin. sshpass does not authenticate sftp -b reliably.
sftp_to_host <"$batch"

echo "Checking the public URL ..."
downloaded="$(mktemp)"
curl -fsS --max-time 60 "$public_url/version.json" -o "$STAGE/remote-version.json"
python3 - "$STAGE/version.json" "$STAGE/remote-version.json" <<'PY'
import json, pathlib, sys
local = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
remote = json.loads(pathlib.Path(sys.argv[2]).read_text(encoding="utf-8"))
if local != remote:
    raise SystemExit(f"published version.json does not match the build:\n{local}\n{remote}")
print(f"version.json {local['version']} ({local['version_code']})")
PY
curl -fsS --max-time 180 "$public_url/NewPipe.apk" -o "$downloaded"
cmp -s "$STAGE/NewPipe.apk" "$downloaded"
rm -f "$downloaded"
page="$(curl -fsS --max-time 30 "$public_url/")"
if [[ "$page" != *'href="NewPipe.apk"'* ]]; then
  echo "deploy.sh: public page does not link to NewPipe.apk" >&2
  exit 1
fi

unset SSHPASS
echo "Published $public_url/NewPipe.apk"
