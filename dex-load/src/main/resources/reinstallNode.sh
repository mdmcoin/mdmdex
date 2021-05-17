systemctl stop TN-devnet.service || true
dpkg -P TN-dex-extension-devnet || true
dpkg -P TN-devnet || true
dpkg -i /home/buildagent-matcher/TN-devnet*.deb
systemctl start TN-devnet
rm -rf /home/buildagent-matcher/*
