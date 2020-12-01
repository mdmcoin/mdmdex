systemctl stop TN-devnet.service || true
rm -rf /var/lib/TN-devnet/data || true
dpkg -P tn-dex-extension-devnet || true
dpkg -P TN-devnet || true
dpkg -i /home/buildagent-matcher/TN-devnet*.deb
systemctl start TN-devnet
rm -rf /home/buildagent-matcher/*
