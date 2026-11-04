// 멀티플레이 서버 목록(servers.dat, 비압축 NBT)에 우리 서버를 미리 넣어 둔다.
// 같은 주소가 이미 있으면 이름만 맞추고, 없으면 맨 위에 추가한다. 다른 항목은 건드리지 않는다.
const fs = require('fs');
const path = require('path');

// ---- 최소 NBT 읽기 ----
function reader(buf) {
  let i = 0;
  const u8 = () => buf[i++];
  const u16 = () => { const v = buf.readUInt16BE(i); i += 2; return v; };
  const str = () => { const n = u16(); const s = buf.toString('utf8', i, i + n); i += n; return s; };
  function payload(t) {
    switch (t) {
      case 1: return { t, v: buf.readInt8(i++) };
      case 2: { const v = buf.readInt16BE(i); i += 2; return { t, v }; }
      case 3: { const v = buf.readInt32BE(i); i += 4; return { t, v }; }
      case 4: { const v = buf.readBigInt64BE(i); i += 8; return { t, v }; }
      case 5: { const v = buf.readFloatBE(i); i += 4; return { t, v }; }
      case 6: { const v = buf.readDoubleBE(i); i += 8; return { t, v }; }
      case 7: { const n = buf.readInt32BE(i); i += 4; const v = buf.subarray(i, i + n); i += n; return { t, v }; }
      case 8: return { t, v: str() };
      case 9: { const et = u8(); const n = buf.readInt32BE(i); i += 4; const v = []; for (let k = 0; k < n; k++) v.push(payload(et)); return { t, et, v }; }
      case 10: { const v = {}; for (;;) { const tt = u8(); if (tt === 0) break; const name = str(); v[name] = payload(tt); } return { t, v }; }
      case 11: { const n = buf.readInt32BE(i); i += 4; const v = []; for (let k = 0; k < n; k++) { v.push(buf.readInt32BE(i)); i += 4; } return { t, v }; }
      case 12: { const n = buf.readInt32BE(i); i += 4; const v = []; for (let k = 0; k < n; k++) { v.push(buf.readBigInt64BE(i)); i += 8; } return { t, v }; }
      default: throw new Error('NBT 태그 ' + t);
    }
  }
  const t = u8(); const name = str(); return { name, root: payload(t) };
}

// ---- 최소 NBT 쓰기 ----
function writer() {
  const parts = [];
  const push = b => parts.push(b);
  const u8 = v => push(Buffer.from([v]));
  const str = s => { const b = Buffer.from(s, 'utf8'); const l = Buffer.alloc(2); l.writeUInt16BE(b.length); push(l); push(b); };
  const i32 = v => { const b = Buffer.alloc(4); b.writeInt32BE(v); push(b); };
  function payload(p) {
    switch (p.t) {
      case 1: u8(p.v & 0xff); break;
      case 2: { const b = Buffer.alloc(2); b.writeInt16BE(p.v); push(b); break; }
      case 3: i32(p.v); break;
      case 4: { const b = Buffer.alloc(8); b.writeBigInt64BE(BigInt(p.v)); push(b); break; }
      case 5: { const b = Buffer.alloc(4); b.writeFloatBE(p.v); push(b); break; }
      case 6: { const b = Buffer.alloc(8); b.writeDoubleBE(p.v); push(b); break; }
      case 7: i32(p.v.length); push(Buffer.from(p.v)); break;
      case 8: str(p.v); break;
      case 9: u8(p.et); i32(p.v.length); for (const e of p.v) payload(e); break;
      case 10: for (const [k, e] of Object.entries(p.v)) { u8(e.t); str(k); payload(e); } u8(0); break;
      case 11: i32(p.v.length); for (const n of p.v) i32(n); break;
      case 12: i32(p.v.length); for (const n of p.v) { const b = Buffer.alloc(8); b.writeBigInt64BE(BigInt(n)); push(b); } break;
    }
  }
  return { write(name, root) { u8(root.t); str(name); payload(root); return Buffer.concat(parts); } };
}

function ensureServerEntry(root, name, address, log) {
  const file = path.join(root, 'servers.dat');
  let doc = { name: '', root: { t: 10, v: { servers: { t: 9, et: 10, v: [] } } } };
  if (fs.existsSync(file)) {
    try { doc = reader(fs.readFileSync(file)); } catch (e) { log(`servers.dat 읽기 실패, 새로 만듦 (${e.message})`); }
  }
  const list = doc.root.v.servers && doc.root.v.servers.t === 9 ? doc.root.v.servers : (doc.root.v.servers = { t: 9, et: 10, v: [] });
  const norm = a => a.replace(/:25565$/, '').toLowerCase();
  const found = list.v.find(e => e.t === 10 && e.v.ip && norm(e.v.ip.v) === norm(address) && !(e.v.hidden && e.v.hidden.v));
  if (found) {
    if (found.v.name.v !== name) { found.v.name.v = name; log(`서버 목록 이름 갱신: ${name}`); } else return false;
  } else {
    list.v.unshift({ t: 10, v: { ip: { t: 8, v: address }, name: { t: 8, v: name }, hidden: { t: 1, v: 0 } } });
    log(`서버 목록에 추가: ${name} (${address})`);
  }
  fs.mkdirSync(root, { recursive: true });
  fs.writeFileSync(file, writer().write(doc.name, doc.root));
  return true;
}

module.exports = { ensureServerEntry };
