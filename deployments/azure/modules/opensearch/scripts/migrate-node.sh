#!/bin/bash
# Brings an EXISTING OpenSearch node to the layout of opensearch-install.sh, on the node, as root:
#   - data disk mounted on DATA_PATH (default /data/opensearch) instead of /mnt/opensearch-data,
#     which is inside Azure's temporary disk and disappears on resize or deallocation;
#   - path.data / path.logs updated accordingly;
#   - heap set to HEAP (-Xms/-Xmx);
#   - systemd unit that refuses to start without the data disk and restarts after a crash.
#
# Usage: migrate-node.sh HEAP [DATA_PATH] [--no-start]
#   --no-start: leave OpenSearch stopped (e.g. a VM resize, which reboots, follows).
#
# Idempotent: on an already-migrated node it only rewrites the heap and the unit, then restarts.
# Run it on one node at a time (see rolling-migrate.sh): the node's shards are unavailable while it is down.
set -euo pipefail

HEAP=${1:?usage: migrate-node.sh HEAP [DATA_PATH] [--no-start]}
DATA_PATH=/data/opensearch
[ -n "${2:-}" ] && [[ "$2" != --* ]] && DATA_PATH=$2
START=yes
for arg in "$@"; do [ "$arg" = "--no-start" ] && START=no; done
[[ "$HEAP" =~ ^[0-9]+[mg]$ ]] || { echo "HEAP must look like 4g"; exit 2; }
[[ "$DATA_PATH" == /mnt* ]] && { echo "DATA_PATH must not be under /mnt"; exit 2; }

CONF=/opensearch/opensearch/config
YML=$CONF/opensearch.yml
DATA_DISK=$(readlink -f /dev/disk/azure/scsi1/lun11)
[ "$(id -u)" = 0 ] || { echo "run as root"; exit 2; }
[ -f "$YML" ] || { echo "$YML not found: not an OpenSearch node?"; exit 2; }
[ -b "$DATA_DISK" ] || { echo "data disk (LUN 11) not found"; exit 2; }

OS_USER=$(stat -c '%U' "$CONF/opensearch.yml")
UUID=$(blkid -s UUID -o value "$DATA_DISK")
CURRENT=$(findmnt -n -o TARGET -S "$DATA_DISK" | head -1 || true)
echo "node=$(hostname) disk=$DATA_DISK uuid=$UUID mounted_on=${CURRENT:-<not mounted>} user=$OS_USER"
echo "before: $(grep -E '^path\.(data|logs)' "$YML" | tr '\n' ' ') heap=$(cat "$CONF/jvm.options.d/heap.options" 2>/dev/null | tr '\n' ' ')"

# 1. Stop OpenSearch and wait for the JVM to exit (flushes on shutdown).
systemctl stop opensearch.service || true
for i in $(seq 1 180); do
  pgrep -u "$OS_USER" -f org.opensearch.bootstrap.OpenSearch > /dev/null || break
  sleep 1
done
if pgrep -u "$OS_USER" -f org.opensearch.bootstrap.OpenSearch > /dev/null; then
  echo "OpenSearch still running after 180 s: aborting, nothing changed"; exit 1
fi

# 2. Move the mount point.
mkdir -p "$DATA_PATH"
if [ "$CURRENT" != "$DATA_PATH" ]; then
  if [ -n "$CURRENT" ]; then
    umount "$CURRENT"
  fi
  # Drop any fstab line for this disk, then add the new one.
  cp /etc/fstab "/etc/fstab.bak.$(date +%Y%m%d%H%M%S)"
  sed -i "\|^UUID=$UUID[[:space:]]|d" /etc/fstab
  echo "UUID=$UUID $DATA_PATH ext4 defaults,nofail 0 2" >> /etc/fstab
  systemctl daemon-reload
  mount "$DATA_PATH"
  [ -n "$CURRENT" ] && rmdir "$CURRENT" 2> /dev/null || true
fi
mountpoint -q "$DATA_PATH" || { echo "$DATA_PATH is not mounted: aborting, OpenSearch left stopped"; exit 1; }
if [ ! -d "$DATA_PATH/data/nodes" ]; then
  echo "no $DATA_PATH/data/nodes on the data disk: aborting, OpenSearch left stopped"; exit 1
fi
mkdir -p "$DATA_PATH/logs"
chown "$OS_USER:$OS_USER" "$DATA_PATH" "$DATA_PATH/data" "$DATA_PATH/logs"

# 3. Paths, heap, sysctl, unit (same content as opensearch-install.sh).
cp "$YML" "$YML.bak.$(date +%Y%m%d%H%M%S)"
sed -i -E "s|^path\.data:.*|path.data: $DATA_PATH/data|; s|^path\.logs:.*|path.logs: $DATA_PATH/logs|" "$YML"

mkdir -p "$CONF/jvm.options.d"
printf -- '-Xms%s\n-Xmx%s\n' "$HEAP" "$HEAP" > "$CONF/jvm.options.d/heap.options"
chown -R "$(stat -c '%U:%G' "$CONF/jvm.options")" "$CONF/jvm.options.d"
chmod 644 "$CONF/jvm.options.d/heap.options"

sed -i '/^vm\.max_map_count/d' /etc/sysctl.conf
echo "vm.max_map_count=262144" > /etc/sysctl.d/99-opensearch.conf
sysctl --system > /dev/null

cat <<EOF > /opensearch/start-opensearch.sh
#!/bin/bash
exec /opensearch/opensearch/bin/opensearch
EOF
chmod +x /opensearch/start-opensearch.sh

cat <<EOF > /etc/systemd/system/opensearch.service
[Unit]
Description=OpenSearch
Wants=network-online.target
After=network-online.target
RequiresMountsFor=$DATA_PATH
StartLimitIntervalSec=900
StartLimitBurst=3

[Service]
Type=simple
User=$OS_USER
Group=$OS_USER
LimitNOFILE=102642
LimitNPROC=4096
ExecStart=/opensearch/start-opensearch.sh
StandardOutput=null
Restart=on-failure
RestartSec=30
SuccessExitStatus=143
TimeoutStopSec=180

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable opensearch.service

echo "after: $(grep -E '^path\.(data|logs)' "$YML" | tr '\n' ' ') heap=$(tr '\n' ' ' < "$CONF/jvm.options.d/heap.options")"
echo "fstab: $(grep "$UUID" /etc/fstab)"
df -h "$DATA_PATH" | tail -1

if [ "$START" = yes ]; then
  systemctl start opensearch.service
  for i in $(seq 1 120); do
    curl -s -o /dev/null "http://$(hostname):9200" && { echo "OpenSearch is up"; echo MIGRATE_OK; exit 0; }
    sleep 2
  done
  echo "OpenSearch did not answer within 4 min: check journalctl -u opensearch"; exit 1
fi
echo "OpenSearch left stopped (--no-start); it is enabled and starts on the next boot"
echo MIGRATE_OK
