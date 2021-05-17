systemctl stop tn-dex.service || true
dpkg -P tn-dex || true
dpkg -i /home/buildagent-matcher/tn-dex*.deb
systemctl start tn-dex
rm -rf /home/buildagent-matcher/*
