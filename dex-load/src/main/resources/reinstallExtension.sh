systemctl stop tn-devnet.service || true
dpkg -P tn-dex-extension-devnet || true
dpkg -i /home/buildagent-matcher/tn-dex-extension-devnet*.deb
dpkg -i /home/buildagent-matcher/tn-grpc-server-devnet*.deb
systemctl start tn-devnet
rm -rf /home/buildagent-matcher/*
