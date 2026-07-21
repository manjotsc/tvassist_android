// Dependency-free mock Home Assistant WebSocket server for verifying TV Assist.
// Implements just enough of https://developers.home-assistant.io/docs/api/websocket
// Run: node tools/mock_ha_server.js   (listens on 0.0.0.0:8123, ws path /api/websocket)
const http = require('http');
const crypto = require('crypto');

const VALID_TOKEN = 'VALID_TEST_TOKEN';
const GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';

// In-memory entity states, each with full HA-style attributes so the control cards
// (brightness/color/climate) have real capabilities to drive.
const states = {
  'light.living_room': {
    state: 'on',
    attributes: {
      friendly_name: 'Living Room Light',
      supported_color_modes: ['color_temp', 'rgb'],
      color_mode: 'color_temp',
      brightness: 160,
      color_temp_kelvin: 3000,
      min_color_temp_kelvin: 2000,
      max_color_temp_kelvin: 6500,
    },
  },
  'light.kitchen': {
    state: 'off',
    attributes: { friendly_name: 'Kitchen Light', supported_color_modes: ['brightness'] },
  },
  'switch.fan': { state: 'on', attributes: { friendly_name: 'Bedroom Fan' } },
  'camera.front_door': {
    state: 'idle',
    attributes: { friendly_name: 'Front Door', supported_features: 2, entity_picture: '/api/camera_proxy/camera.front_door' },
  },
  'person.jot': {
    state: 'home',
    attributes: { friendly_name: 'Jot', latitude: 43.6532, longitude: -79.3832, gps_accuracy: 18, source: 'device_tracker.jot_phone' },
  },
  'input_button.doorbell': {
    state: '2026-06-25T00:00:00+00:00',
    attributes: { friendly_name: 'Doorbell Chime' },
  },
  'climate.living_room': {
    state: 'heat',
    attributes: {
      friendly_name: 'Living Room Thermostat',
      current_temperature: 21.5,
      temperature: 22,
      min_temp: 7,
      max_temp: 35,
      target_temp_step: 0.5,
      hvac_modes: ['off', 'heat', 'cool', 'auto'],
      fan_modes: ['low', 'medium', 'high', 'auto'],
      fan_mode: 'auto',
    },
  },
};

function stateObj(id) {
  return { entity_id: id, state: states[id].state, attributes: states[id].attributes };
}

function encodeFrame(str) {
  const payload = Buffer.from(str, 'utf8');
  const len = payload.length;
  let header;
  if (len < 126) {
    header = Buffer.from([0x81, len]);
  } else if (len < 65536) {
    header = Buffer.alloc(4);
    header[0] = 0x81; header[1] = 126; header.writeUInt16BE(len, 2);
  } else {
    header = Buffer.alloc(10);
    header[0] = 0x81; header[1] = 127; header.writeUInt32BE(0, 2); header.writeUInt32BE(len, 6);
  }
  return Buffer.concat([header, payload]);
}

const server = http.createServer((req, res) => { res.writeHead(426); res.end('Upgrade required'); });

server.on('upgrade', (req, socket) => {
  const key = req.headers['sec-websocket-key'];
  const accept = crypto.createHash('sha1').update(key + GUID).digest('base64');
  socket.write(
    'HTTP/1.1 101 Switching Protocols\r\n' +
    'Upgrade: websocket\r\n' +
    'Connection: Upgrade\r\n' +
    `Sec-WebSocket-Accept: ${accept}\r\n\r\n`,
  );

  const send = (obj) => socket.write(encodeFrame(JSON.stringify(obj)));
  send({ type: 'auth_required', ha_version: '2024.6.0-mock' });

  let buf = Buffer.alloc(0);
  socket.on('data', (chunk) => {
    buf = Buffer.concat([buf, chunk]);
    // Parse as many complete frames as available.
    while (buf.length >= 2) {
      const opcode = buf[0] & 0x0f;
      const masked = (buf[1] & 0x80) !== 0;
      let len = buf[1] & 0x7f;
      let offset = 2;
      if (len === 126) { if (buf.length < 4) return; len = buf.readUInt16BE(2); offset = 4; }
      else if (len === 127) { if (buf.length < 10) return; len = Number(buf.readBigUInt64BE(2)); offset = 10; }
      let mask;
      if (masked) { if (buf.length < offset + 4) return; mask = buf.slice(offset, offset + 4); offset += 4; }
      if (buf.length < offset + len) return;
      const payload = buf.slice(offset, offset + len);
      if (masked) for (let i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
      buf = buf.slice(offset + len);

      if (opcode === 0x8) { socket.end(); return; }        // close
      if (opcode === 0x9) {                                  // ping -> reply with pong
        const pong = Buffer.concat([Buffer.from([0x8a, payload.length]), payload]);
        socket.write(pong);
        continue;
      }
      if (opcode === 0xa) continue;                          // pong (ignore)
      if (opcode !== 0x1) continue;                          // only handle text
      handleMessage(payload.toString('utf8'), send, socket);
    }
  });
  socket.on('error', () => {});
});

function handleMessage(text, send, socket) {
  let msg;
  try { msg = JSON.parse(text); } catch { return; }
  console.log('<-', text);
  switch (msg.type) {
    case 'auth':
      if (msg.access_token === VALID_TOKEN) send({ type: 'auth_ok', ha_version: '2024.6.0-mock' });
      else send({ type: 'auth_invalid', message: 'Invalid access token or password' });
      break;
    case 'get_states':
      send({ id: msg.id, type: 'result', success: true, result: Object.keys(states).map(stateObj) });
      break;
    case 'subscribe_events':
      send({ id: msg.id, type: 'result', success: true, result: null });
      break;
    case 'call_service': {
      const id = msg.target && msg.target.entity_id;
      const data = msg.service_data || {};
      if (id && states[id]) {
        const a = states[id].attributes;
        if (msg.service === 'toggle') states[id].state = states[id].state === 'on' ? 'off' : 'on';
        else if (msg.service === 'turn_on' || msg.service === 'open_cover') states[id].state = 'on';
        else if (msg.service === 'turn_off' || msg.service === 'close_cover') states[id].state = 'off';
        // Light parameters.
        if (data.brightness_pct != null) { a.brightness = Math.round(data.brightness_pct * 255 / 100); states[id].state = 'on'; }
        if (data.color_temp_kelvin != null) { a.color_temp_kelvin = data.color_temp_kelvin; a.color_mode = 'color_temp'; states[id].state = 'on'; }
        if (data.rgb_color != null) { a.rgb_color = data.rgb_color; a.color_mode = 'rgb'; states[id].state = 'on'; }
        // Climate parameters.
        if (data.temperature != null) a.temperature = data.temperature;
        if (data.hvac_mode != null) states[id].state = data.hvac_mode;
        if (data.fan_mode != null) a.fan_mode = data.fan_mode;
        // Push the resulting state change like real HA does.
        send({ id: 2, type: 'event', event: { event_type: 'state_changed', data: { entity_id: id, new_state: stateObj(id) } } });
      }
      send({ id: msg.id, type: 'result', success: true, result: null });
      break;
    }
    default:
      if (msg.id) send({ id: msg.id, type: 'result', success: true, result: null });
  }
}

server.listen(8123, '0.0.0.0', () => console.log('Mock HA listening on ws://0.0.0.0:8123/api/websocket'));
