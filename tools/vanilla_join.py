"""A client with no loader and no mod: can it join a modded server?

Goes further than a login check. A NeoForge server that insists on a modded client says so in the
CONFIGURATION phase (after login_success), so this walks handshake -> login -> configuration -> play
and only reports success once the server has moved the connection into play and kept it there.

Packet ids read from ConfigurationProtocols / LoginProtocols of both 1.21.1 and 26.2: the ones used
here are identical on both lines (26.2 only appends dialog / code-of-conduct packets at the end).

usage: vanilla_join.py <host> <port> <protocol>
"""
import socket, struct, sys, time, uuid, zlib

HOST, PORT, PROTO = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])


def varint(n):
    out = b""
    n &= 0xFFFFFFFF
    while True:
        b = n & 0x7F
        n >>= 7
        out += bytes([b | (0x80 if n else 0)])
        if not n:
            return out


def read_varint_buf(buf, i):
    n = shift = 0
    while True:
        b = buf[i]; i += 1
        n |= (b & 0x7F) << shift
        if not b & 0x80:
            break
        shift += 7
    if n & (1 << 31):
        n -= 1 << 32
    return n, i


def mcstr(s):
    b = s.encode("utf-8")
    return varint(len(b)) + b


def read_str(buf, i):
    n, i = read_varint_buf(buf, i)
    return buf[i:i + n].decode("utf-8", "replace"), i + n


class Conn:
    def __init__(self):
        self.s = socket.create_connection((HOST, PORT), timeout=15)
        self.threshold = -1

    def _recv_exact(self, n):
        data = b""
        while len(data) < n:
            chunk = self.s.recv(n - len(data))
            if not chunk:
                raise ConnectionError("server closed the connection")
            data += chunk
        return data

    def _recv_varint(self):
        n = shift = 0
        while True:
            b = self._recv_exact(1)[0]
            n |= (b & 0x7F) << shift
            if not b & 0x80:
                return n
            shift += 7

    def send(self, pid, payload=b""):
        body = varint(pid) + payload
        if self.threshold >= 0:
            body = varint(0) + body          # always send uncompressed
        self.s.sendall(varint(len(body)) + body)

    def recv(self):
        frame = self._recv_exact(self._recv_varint())
        i = 0
        if self.threshold >= 0:
            dlen, i = read_varint_buf(frame, 0)
            frame = zlib.decompress(frame[i:]) if dlen else frame[i:]
            i = 0
        pid, i = read_varint_buf(frame, i)
        return pid, frame[i:]


def main():
    c = Conn()
    # --- handshake -> login
    c.send(0x00, varint(PROTO) + mcstr(HOST) + struct.pack(">H", PORT) + varint(2))
    c.send(0x00, mcstr("VanillaProbe") + uuid.uuid4().bytes)

    stage = "login"
    while stage == "login":
        pid, d = c.recv()
        if pid == 0x03:                                   # set compression
            c.threshold, _ = read_varint_buf(d, 0)
        elif pid == 0x02:                                 # login finished / game profile
            print("login_success")
            c.send(0x03)                                  # login acknowledged -> configuration
            stage = "config"
        elif pid == 0x04:                                 # custom query: "not understood", as vanilla does
            mid, _ = read_varint_buf(d, 0)
            c.send(0x02, varint(mid) + b"\x00")
        elif pid == 0x05:                                 # cookie request
            key, _ = read_str(d, 0)
            c.send(0x04, mcstr(key) + b"\x00")
        elif pid == 0x01:
            print("FAIL: server asked for encryption (online-mode is on)"); return 2
        elif pid == 0x00:
            print("FAIL at login, disconnected:", d[:300]); return 1

    seen = {}
    while stage == "config":
        pid, d = c.recv()
        seen[pid] = seen.get(pid, 0) + 1
        if pid == 0x0E:                                   # select known packs -> echo them back
            n, i = read_varint_buf(d, 0)
            out = varint(n)
            for _ in range(n):
                ns, i = read_str(d, i); pk, i = read_str(d, i); ver, i = read_str(d, i)
                out += mcstr(ns) + mcstr(pk) + mcstr(ver)
            c.send(0x07, out)
        elif pid == 0x04:                                 # keep alive
            c.send(0x04, d[:8])
        elif pid == 0x05:                                 # ping
            c.send(0x05, d[:4])
        elif pid == 0x00:                                 # cookie request
            key, _ = read_str(d, 0)
            c.send(0x01, mcstr(key) + b"\x00")
        elif pid == 0x13 and PROTO >= 770:                # code of conduct (26.x only)
            c.send(0x09)
        elif pid == 0x02:                                 # disconnect during configuration
            txt = d.decode("utf-8", "replace")
            print("FAIL in configuration, server disconnected us:", "".join(ch for ch in txt if ch.isprintable())[:400])
            return 1
        elif pid == 0x03:                                 # finish configuration
            print("configuration_finished (packets seen:", dict(sorted(seen.items())), ")")
            c.send(0x03)
            stage = "play"

    # --- play: the server has to keep us here, not just let us in
    c.s.settimeout(1.0)
    count, t0 = 0, time.time()
    try:
        while time.time() - t0 < 4.0:
            try:
                c.recv(); count += 1
            except socket.timeout:
                pass
    except ConnectionError as e:
        print(f"FAIL in play after {count} packets: {e}"); return 1
    print(f"JOINED: in play state, {count} play packets received over 4 s, still connected")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as e:
        print("FAIL:", type(e).__name__, e)
        sys.exit(1)
