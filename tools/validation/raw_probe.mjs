/** Local-only protocol probe. This is a validation client, not a playable bot. */
import net from 'node:net';
import fs from 'node:fs';
import crypto from 'node:crypto';
import zlib from 'node:zlib';
import readline from 'node:readline';

const [idsPath, portText = '25585', protocolText = '777'] = process.argv.slice(2);
if (!idsPath) throw new Error('Usage: node raw_probe.mjs protocol-ids.json [port] [protocol]');
const ids = JSON.parse(fs.readFileSync(idsPath, 'utf8'));
const clients = new Map();
const report = (id, event, data = {}) => {
  const line = JSON.stringify({ time: new Date().toISOString(), id, event, ...data });
  console.log(line);
  if (process.env.CP_PROBE_LOG) fs.appendFileSync(process.env.CP_PROBE_LOG, line + '\n');
};
const varint = value => {
  const output = [];
  do { let byte = value & 127; value >>>= 7; if (value) byte |= 128; output.push(byte); } while (value);
  return Buffer.from(output);
};
function readVarint(buffer, offset = 0) {
  let value = 0;
  for (let index = 0; index < 5; index++) {
    if (offset + index >= buffer.length) return null;
    const byte = buffer[offset + index];
    value |= (byte & 127) << (index * 7);
    if (!(byte & 128)) return { value, end: offset + index + 1 };
  }
  throw new Error('Invalid VarInt');
}
const string = value => { const bytes = Buffer.from(value); return Buffer.concat([varint(bytes.length), bytes]); };
const short = value => { const bytes = Buffer.alloc(2); bytes.writeUInt16BE(value); return bytes; };
const float = value => { const bytes = Buffer.alloc(4); bytes.writeFloatBE(value); return bytes; };
const double = value => { const bytes = Buffer.alloc(8); bytes.writeDoubleBE(value); return bytes; };

class Probe {
  constructor(id, username, holdConfiguration = false, ackPosition = null) {
    if (!/^[A-Za-z0-9_]{1,16}$/.test(username)) throw new Error('Invalid test username');
    this.id = id;
    this.state = 'login';
    this.compression = -1;
    this.holdConfiguration = holdConfiguration;
    this.ackPosition = ackPosition;
    this.pending = Buffer.alloc(0);
    this.socket = net.createConnection({ host: '127.0.0.1', port: Number(portText) });
    this.socket.on('connect', () => {
      this.packet(0, varint(Number(protocolText)), string('127.0.0.1'), short(Number(portText)), varint(2));
      const uuid = crypto.createHash('md5').update('OfflinePlayer:' + username).digest();
      uuid[6] = (uuid[6] & 15) | 48;
      uuid[8] = (uuid[8] & 63) | 128;
      this.send('SERVERBOUND_HELLO', string(username), uuid);
      report(id, 'connected');
    });
    this.socket.on('data', chunk => {
      try {
        this.pending = Buffer.concat([this.pending, chunk]);
        while (this.pending.length) {
          const length = readVarint(this.pending);
          if (!length) break;
          if (length.value < 0 || length.value > 16 * 1024 * 1024) throw new Error('Invalid packet size');
          if (this.pending.length < length.end + length.value) break;
          let packet = this.pending.subarray(length.end, length.end + length.value);
          this.pending = this.pending.subarray(length.end + length.value);
          if (this.compression >= 0) {
            const size = readVarint(packet);
            if (!size || size.value < 0 || size.value > 16 * 1024 * 1024) throw new Error('Invalid compression size');
            packet = size.value ? zlib.inflateSync(packet.subarray(size.end), { maxOutputLength: 16 * 1024 * 1024 }) : packet.subarray(size.end);
          }
          const header = readVarint(packet);
          if (!header) throw new Error('Missing packet ID');
          this.receive(header.value, packet.subarray(header.end));
        }
      } catch (error) { report(id, 'error', { message: error.message }); this.socket.destroy(); }
    });
    this.socket.on('error', error => report(id, 'error', { message: error.message }));
    this.socket.on('close', () => report(id, 'closed'));
  }

  packet(number, ...parts) {
    let payload = Buffer.concat([varint(number), ...parts]);
    if (this.compression >= 0) {
      payload = payload.length >= this.compression
        ? Buffer.concat([varint(payload.length), zlib.deflateSync(payload)])
        : Buffer.concat([varint(0), payload]);
    }
    this.socket.write(Buffer.concat([varint(payload.length), payload]));
  }

  send(name, ...parts) {
    const number = ids[this.state].serverbound[name];
    if (number === undefined) throw new Error(`Missing ${this.state} packet ${name}`);
    this.packet(number, ...parts);
  }

  releaseConfiguration() {
    if (!this.configurationReady || this.state !== 'configuration') throw new Error('Configuration is not ready');
    this.send('SERVERBOUND_FINISH_CONFIGURATION');
    this.state = 'game';
    report(this.id, 'play');
  }

  receive(number, payload) {
    const name = Object.entries(ids[this.state].clientbound).find(([, value]) => value === number)?.[0];
    if (!name) return;
    if (name.includes('DISCONNECT')) { report(this.id, 'disconnected', { reason: payload.toString('utf8') }); return; }
    if (this.state === 'login') {
      if (name === 'CLIENTBOUND_LOGIN_COMPRESSION') this.compression = readVarint(payload).value;
      if (name === 'CLIENTBOUND_LOGIN_FINISHED') {
        this.send('SERVERBOUND_LOGIN_ACKNOWLEDGED');
        this.state = 'configuration';
        this.send('SERVERBOUND_CLIENT_INFORMATION', string('en_us'), Buffer.from([2]), varint(0), Buffer.from([1, 127]), varint(1), Buffer.from([0, 0]), varint(2));
        report(this.id, 'configuration');
      }
      if (name === 'CLIENTBOUND_CUSTOM_QUERY') this.send('SERVERBOUND_CUSTOM_QUERY_ANSWER', varint(readVarint(payload).value), Buffer.from([0]));
      return;
    }
    if (name === 'CLIENTBOUND_KEEP_ALIVE') this.send('SERVERBOUND_KEEP_ALIVE', payload);
    if (name === 'CLIENTBOUND_PING') this.send('SERVERBOUND_PONG', payload);
    if (name === 'CLIENTBOUND_COOKIE_REQUEST') this.send('SERVERBOUND_COOKIE_RESPONSE', payload, Buffer.from([0]));
    if (this.state === 'configuration') {
      if (name === 'CLIENTBOUND_SELECT_KNOWN_PACKS') this.send('SERVERBOUND_SELECT_KNOWN_PACKS', varint(0));
      if (name === 'CLIENTBOUND_CODE_OF_CONDUCT') this.send('SERVERBOUND_ACCEPT_CODE_OF_CONDUCT');
      if (name === 'CLIENTBOUND_FINISH_CONFIGURATION') {
        this.configurationReady = true;
        if (this.holdConfiguration) report(this.id, 'configuration-ready');
        else this.releaseConfiguration();
      }
      return;
    }
    if (name === 'CLIENTBOUND_LOGIN') {
      this.send('SERVERBOUND_PLAYER_LOADED');
      report(this.id, 'joined');
    }
    if (name === 'CLIENTBOUND_PLAYER_POSITION') {
      const teleport = readVarint(payload);
      const xyz = [0, 8, 16].map(offset => payload.readDoubleBE(teleport.end + offset));
      // 26.3 acknowledges the applied position and rotation as well as the ID.
      this.send('SERVERBOUND_ACCEPT_TELEPORTATION', varint(teleport.value),
        this.ackPosition ? Buffer.concat(this.ackPosition.map(double)) : payload.subarray(teleport.end, teleport.end + 24),
        payload.subarray(teleport.end + 48, teleport.end + 56));
      if (this.ackPosition) {
        report(this.id, 'malicious-ack', { xyz: this.ackPosition });
        this.ackPosition = null;
      }
      report(this.id, 'position', { xyz });
    }
    if (name === 'CLIENTBOUND_CHUNK_BATCH_FINISHED') this.send('SERVERBOUND_CHUNK_BATCH_RECEIVED', float(1));
    if (name === 'CLIENTBOUND_SYSTEM_CHAT' && payload[payload.length - 1] === 0) {
      report(this.id, 'message', { text: payload.toString('utf8').replace(/[\u0000-\u001f]/g, '') });
    }
    if (name === 'CLIENTBOUND_CONTAINER_SET_CONTENT') {
      let offset = readVarint(payload).end;
      offset = readVarint(payload, offset).end;
      const length = readVarint(payload, offset);
      offset = length.end;
      let empty = true;
      for (let slot = 0; slot <= length.value; slot++) {
        const count = readVarint(payload, offset);
        if (!count || count.value !== 0) { empty = false; break; }
        offset = count.end;
      }
      report(this.id, 'inventory', { empty, slots: length.value, bytes: payload.length });
    }
  }
}

readline.createInterface({ input: process.stdin }).on('line', line => {
  try {
    const command = JSON.parse(line);
    if (command.action === 'connect') {
      if (clients.has(command.id)) throw new Error('Client ID already used');
      clients.set(command.id, new Probe(command.id, command.name, command.holdConfiguration, command.ackPosition));
    } else if (command.action === 'release') {
      clients.get(command.id).releaseConfiguration();
    } else if (command.action === 'command') {
      clients.get(command.id).send('SERVERBOUND_CHAT_COMMAND', string(command.text));
      if (command.disconnectAfterMs !== undefined) {
        setTimeout(() => clients.get(command.id).socket.end(), command.disconnectAfterMs);
      }
    } else if (command.action === 'move') {
      clients.get(command.id).send('SERVERBOUND_MOVE_PLAYER_POS', ...command.xyz.map(double), Buffer.from([1]));
    } else if (command.action === 'respawn') {
      clients.get(command.id).send('SERVERBOUND_CLIENT_COMMAND', varint(0));
    } else if (command.action === 'disconnect') {
      clients.get(command.id).socket.end();
    } else if (command.action === 'quit') {
      for (const client of clients.values()) client.socket.destroy();
      process.exit(0);
    } else throw new Error('Unknown action');
  } catch (error) { report('control', 'error', { message: error.message }); }
});
