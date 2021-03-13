systemctl stop TN-devnet.service || true
dpkg -P TN-dex-extension-devnet || true
dpkg -i /home/buildagent-matcher/TN-dex-extension-devnet*.deb
dpkg -i /home/buildagent-matcher/grpc-server-devnet*.deb
systemctl start TN-devnet
rm -rf /home/buildagent-matcher/*
