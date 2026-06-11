MESSAGE sip:34020000002000000001@3402000000 SIP/2.0
Via: SIP/2.0/UDP 192.168.10.50:5060;rport;branch=z9hG4bKkeepalive123
From: <sip:34020000001110000001@3402000000>;tag=abc123
To: <sip:34020000002000000001@3402000000>
Call-ID: keepalive-call-id@192.168.10.50
CSeq: 12 MESSAGE
Content-Type: application/MANSCDP+xml
Max-Forwards: 70
User-Agent: UVP-Sim/0.1
Content-Length: 178

<?xml version="1.0" encoding="UTF-8"?>
<Notify>
<CmdType>Keepalive</CmdType>
<SN>12</SN>
<DeviceID>34020000001110000001</DeviceID>
<Status>OK</Status>
</Notify>
