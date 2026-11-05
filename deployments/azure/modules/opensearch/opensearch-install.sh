#!/bin/bash
# Provisions a new OpenSearch node. Rendered by Terraform's templatefile: dollar-brace placeholders
# are Terraform variables, shell variables are written without braces. Existing nodes are not re-provisioned by Terraform (lifecycle ignore_changes):
# change them one at a time with scripts/migrate-node.sh, which writes the same unit and settings.
set -euo pipefail

DATA_PATH="${data_path}"

# /dev/sdX naming isn't guaranteed by Azure (it depends on SCSI enumeration
# order). LUN 11 is, so address the data disk via the stable udev symlink.
DATA_DISK=/dev/disk/azure/scsi1/lun11

# The symlink is created asynchronously by udev after attach; wait for it.
for i in $(seq 1 30); do
  [ -e "$DATA_DISK" ] && break
  sleep 1
done

# Only format the data disk the first time it's provisioned: never wipe an
# already-formatted, already-populated disk.
if ! blkid "$DATA_DISK" > /dev/null 2>&1; then
  mkfs.ext4 "$DATA_DISK"
fi

# Mount by UUID (device names change across reboots and resizes), outside /mnt:
# /mnt is Azure's temporary disk, wiped on resize or deallocation, which took
# the mount point away with it. nofail keeps boot from hanging if the disk is
# slow to attach; the service below refuses to start without the mount.
DATA_DISK_UUID=$(blkid -s UUID -o value "$DATA_DISK")
FSTAB_ENTRY="UUID=$DATA_DISK_UUID $DATA_PATH ext4 defaults,nofail 0 2"
grep -qF "$FSTAB_ENTRY" /etc/fstab || echo "$FSTAB_ENTRY" >> /etc/fstab

mkdir -p "$DATA_PATH"
if ! mountpoint -q "$DATA_PATH"; then
  mount "$DATA_PATH"
fi
# After mounting: these must be on the data disk, not on the directory it hides.
mkdir -p "$DATA_PATH/data" "$DATA_PATH/logs"
chown ${user}:${user} "$DATA_PATH" "$DATA_PATH/data" "$DATA_PATH/logs"
chmod 755 "$DATA_PATH"

if [ ! -x /opensearch/opensearch-3.2.0/bin/opensearch ]; then
  mkdir -p /opensearch/
  cd /opensearch/
  wget -q https://artifacts.opensearch.org/releases/bundle/opensearch/3.2.0/opensearch-3.2.0-linux-x64.tar.gz
  tar -xf opensearch-3.2.0-linux-x64.tar.gz
  rm -f opensearch-3.2.0-linux-x64.tar.gz
fi
ln -sfn opensearch-3.2.0 /opensearch/opensearch

swapoff -a
echo "vm.max_map_count=262144" > /etc/sysctl.d/99-opensearch.conf
sysctl --system > /dev/null

cat <<EOF > /opensearch/opensearch/config/opensearch.yml
plugins.security.disabled: true
cluster.name: kockpit
node.name: opensearch-${index}-${env}
path.data: $DATA_PATH/data
path.logs: $DATA_PATH/logs
discovery.seed_hosts: [${seeds}]
network.host: opensearch-${index}-${env}
cluster.initial_cluster_manager_nodes: [${seeds}]
EOF

cat <<EOF > /opensearch/start-opensearch.sh
#!/bin/bash
exec /opensearch/opensearch/bin/opensearch
EOF
chmod +x /opensearch/start-opensearch.sh

# RequiresMountsFor: never start on an empty directory if the data disk is not
# mounted (it would join as a new, empty node). Type=simple without
# RemainAfterExit: a crash or OOM kill leaves the unit failed and restarts it,
# instead of showing "active (exited)". 143 is the JVM's exit code on SIGTERM.
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
User=${user}
Group=${user}
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

# Heap: about 50% of the VM's RAM, the rest for the OS page cache Lucene relies on.
mkdir -p /opensearch/opensearch/config/jvm.options.d
cat <<EOF > /opensearch/opensearch/config/jvm.options.d/heap.options
-Xms${heap_size}
-Xmx${heap_size}
EOF

# Match ownership of the surrounding files
chown -R "$(stat -c '%U:%G' /opensearch/opensearch/config/jvm.options)" /opensearch/opensearch/config/jvm.options.d
chmod 644 /opensearch/opensearch/config/jvm.options.d/heap.options

systemctl daemon-reload
systemctl enable opensearch.service
systemctl start opensearch.service
