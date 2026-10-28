import sys
from termux_personal import TermuxClient

with TermuxClient.connect_adb(socket_path=sys.argv[1], bridge_path=sys.argv[2]) as client:
    assert client.describe()["protocolVersion"] == 1
    result, _ = client.request("session.write", {"id": "s1", "length": 3}, binary=b"\x00\xff\x03")
    assert result == {"ok": True}
print("Python ADB bridge to Android LocalSocket passed")
