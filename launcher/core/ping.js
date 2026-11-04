// 서버 상태 핑 (Server List Ping). 접속자 수와 MOTD를 가져온다.
const net = require('net');

function varint(n) {
  const out = [];
  do { let b = n & 0x7f; n >>>= 7; if (n) b |= 0x80; out.push(b); } while (n);
  return Buffer.from(out);
}

function ping(address, timeoutMs = 4000) {
  const [host, portStr] = address.split(':');
  const port = Number(portStr || 25565);
  return new Promise((resolve, reject) => {
    const sock = net.createConnection({ host, port });
    const timer = setTimeout(() => { sock.destroy(); reject(new Error('timeout')); }, timeoutMs);
    let buf = Buffer.alloc(0);
    sock.on('connect', () => {
      const hostBuf = Buffer.from(host);
      const h = Buffer.concat([
        Buffer.from([0x00]), varint(767), varint(hostBuf.length), hostBuf,
        Buffer.from([port >> 8, port & 0xff]), Buffer.from([0x01])
      ]);
      sock.write(Buffer.concat([varint(h.length), h, Buffer.from([0x01, 0x00])]));
    });
    sock.on('data', d => {
      buf = Buffer.concat([buf, d]);
      let len = 0, shift = 0, i = 0;
      while (i < buf.length) { const b = buf[i++]; len |= (b & 0x7f) << shift; shift += 7; if (!(b & 0x80)) break; }
      if (buf.length < i + len) return;
      let p = i + 1; // packet id 0x00 건너뜀
      let slen = 0; shift = 0;
      while (true) { const b = buf[p++]; slen |= (b & 0x7f) << shift; shift += 7; if (!(b & 0x80)) break; }
      clearTimeout(timer); sock.destroy();
      try { resolve(JSON.parse(buf.slice(p, p + slen).toString('utf8'))); } catch (e) { reject(e); }
    });
    sock.on('error', e => { clearTimeout(timer); reject(e); });
  });
}

module.exports = { ping };
