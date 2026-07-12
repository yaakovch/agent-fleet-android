#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

files_root="${TERMUX_FILES_ROOT:-${PREFIX%/usr}}"
home_dir="$files_root/home"
usr_dir="$files_root/usr"
output_root="${AGENT_FLEET_BACKUP_OUTPUT:-$HOME/storage/downloads/AgentFleetBackup}"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
backup_dir="$output_root/$timestamp"

[[ -d "$home_dir" && -d "$usr_dir" ]] || { echo "Termux home or prefix is missing under $files_root" >&2; exit 1; }
command -v openssl >/dev/null || { echo "install openssl-tool first: pkg install openssl-tool" >&2; exit 1; }
command -v tar >/dev/null || { echo "tar is required" >&2; exit 1; }
mkdir -p "$backup_dir"
chmod 700 "$backup_dir" 2>/dev/null || true

if [[ -z "${AGENT_FLEET_BACKUP_PASSWORD:-}" ]]; then
  read -r -s -p "Backup password: " AGENT_FLEET_BACKUP_PASSWORD
  echo
  read -r -s -p "Repeat backup password: " confirmation
  echo
  [[ "$AGENT_FLEET_BACKUP_PASSWORD" == "$confirmation" ]] || { echo "passwords do not match" >&2; exit 1; }
fi
[[ ${#AGENT_FLEET_BACKUP_PASSWORD} -ge 16 ]] || { echo "backup password must be at least 16 characters" >&2; exit 1; }
export AGENT_FLEET_BACKUP_PASSWORD

archive() {
  local output="$1"; shift
  tar -C "$files_root" -czpf - "$@" |
    openssl enc -aes-256-cbc -salt -pbkdf2 -iter 200000 \
      -pass env:AGENT_FLEET_BACKUP_PASSWORD -out "$output"
}

echo "creating complete encrypted Termux backup"
archive "$backup_dir/termux-complete.tar.gz.enc" home usr
echo "creating portable encrypted home backup"
archive "$backup_dir/termux-home.tar.gz.enc" home

{
  printf 'createdAt=%s\n' "$timestamp"
  printf 'device=%s\n' "$(getprop ro.product.model 2>/dev/null || uname -n)"
  printf 'android=%s\n' "$(getprop ro.build.version.release 2>/dev/null || printf 'host-rehearsal')"
  printf 'prefix=%s\n' "$PREFIX"
} >"$backup_dir/backup.properties"
(command -v termux-info >/dev/null && termux-info || true) >"$backup_dir/termux-info.txt" 2>&1
(command -v pkg >/dev/null && pkg list-installed || true) >"$backup_dir/packages.txt" 2>&1
(cd "$backup_dir" && sha256sum backup.properties packages.txt termux-info.txt termux-complete.tar.gz.enc termux-home.tar.gz.enc >SHA256SUMS)

echo "backup complete: $backup_dir"
echo "keep the password separately; the encrypted archives cannot be recovered without it"
