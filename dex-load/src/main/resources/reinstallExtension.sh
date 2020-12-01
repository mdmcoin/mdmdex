systemctl stop TN-devnet.service || true
dpkg -P tn-dex-extension-devnet || true
dpkg -i /home/buildagent-matcher/tn-dex-extension-devnet*.deb
systemctl start TN-devnet
rm -rf /home/buildagent-matcher/*
