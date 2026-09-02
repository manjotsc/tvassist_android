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
  // An Assist agent. Real HA reports its state as the ISO timestamp it was last used, which is
  // what the app has to avoid showing raw on a tile.
  'conversation.home_assistant': {
    state: '2026-08-24T17:00:00.000000+00:00',
    attributes: { friendly_name: 'Home Assistant', supported_features: 1 },
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

// Almost everything here is WebSocket, but the recogniser route reaches for one REST endpoint: it
// has no pipeline run to hand it audio, so it asks HA to synthesise the reply text instead.
const server = http.createServer((req, res) => {
  if (req.method === 'POST' && req.url === '/api/tts_get_url') {
    let body = '';
    req.on('data', (chunk) => { body += chunk; });
    req.on('end', () => {
      let msg = '';
      try { msg = (JSON.parse(body) || {}).message || ''; } catch { /* ignore */ }
      console.log('tts_get_url:', JSON.stringify(msg).slice(0, 80));
      res.writeHead(200, { 'Content-Type': 'application/json' });
      // Both fields, as real HA sends: the app prefers `path` so it resolves against the address it
      // is already connected on rather than whatever HA thinks its own URL is.
      res.end(JSON.stringify({
        url: 'http://mock.invalid/api/tts_proxy/mock-say.mp3',
        path: '/api/tts_proxy/mock-say.mp3',
      }));
    });
    return;
  }
  res.writeHead(426);
  res.end('Upgrade required');
});

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
      if (opcode === 0x2) {                                  // binary = Assist audio chunk
        handleAudio(payload, send);
        continue;
      }
      if (opcode !== 0x1) continue;                          // only handle text
      handleMessage(payload.toString('utf8'), send, socket);
    }
  });
  socket.on('error', () => {});
});

// --- Fake Assist pipeline -------------------------------------------------------------------
// One run at a time. Audio is counted, not transcribed: after enough chunks the run "hears" a
// canned sentence, so the client's event handling, binary framing and end-of-stream can all be
// exercised without a speech engine.
let assistRun = null;

// Two pipelines on purpose: the preferred one has no speech-to-text, which is the shape of a real
// instance that has never been set up for voice, and the case the app has to report rather than
// hang on. Picking the other one in Settings is what makes Speak work.
const PIPELINES = [
  { id: 'pipe-text', name: 'Home Assistant', language: 'en', conversation_engine: 'conversation.home_assistant', stt_engine: null, tts_engine: null },
  { id: 'pipe-voice', name: 'Whisper + Piper', language: 'en', conversation_engine: 'conversation.home_assistant', stt_engine: 'stt.faster_whisper', tts_engine: 'tts.piper' },
  { id: 'pipe-mute', name: 'Whisper only', language: 'en', conversation_engine: 'conversation.home_assistant', stt_engine: 'stt.faster_whisper', tts_engine: null },
];
const PREFERRED_PIPELINE = 'pipe-text';

// Whether the mock is already waiting on an answer. HA sets `continue_conversation` when the agent
// asked something back ("which light did you mean?"), and the app answers by reopening the mic.
// Alternating on this flag means the turn AFTER a follow-up never asks for another, so a mock
// exchange cannot hold the microphone open in a loop on an emulator nobody is talking to.
let awaitingFollowUp = false;

function askFollowUp(want) {
  if (awaitingFollowUp) { awaitingFollowUp = false; return false; }
  awaitingFollowUp = !!want;
  return awaitingFollowUp;
}

function assistEvent(send, type, data) {
  send({ id: assistRun.id, type: 'event', event: { type, data: data || {}, timestamp: new Date().toISOString() } });
}

function startAssist(msg, send) {
  // Real HA answers a run it cannot serve with a failed result and NO events at all; the app relies
  // on that reply to avoid waiting forever, so the mock has to reject the same way.
  const startStage = msg.start_stage || 'stt';
  const pipe = PIPELINES.find((p) => p.id === (msg.pipeline || PREFERRED_PIPELINE));
  if (!pipe) {
    send({ id: msg.id, type: 'result', success: false, error: { code: 'not_found', message: 'unknown pipeline' } });
    return;
  }
  // Speech-to-text is only required by a run that starts there. A run starting at 'intent' brings
  // its own words — which is how a TV whose microphone the app cannot open still gets the whole
  // assistant — so refusing it for a missing stt engine would reject the app's normal path.
  if (startStage === 'stt' && !pipe.stt_engine) {
    send({ id: msg.id, type: 'result', success: false, error: { code: 'not_supported', message: 'the pipeline does not support speech-to-text' } });
    return;
  }
  // Same refusal shape for a missing voice, so the "no text-to-speech" path is exercised too.
  if ((msg.end_stage || 'tts') === 'tts' && !pipe.tts_engine) {
    send({ id: msg.id, type: 'result', success: false, error: { code: 'not_supported', message: 'the pipeline does not support text-to-speech' } });
    return;
  }
  // The app ends its runs at 'stt' and puts the transcript to its chosen agent itself, so honour
  // end_stage rather than always running to intent — otherwise the mock is the one place where a
  // pipeline answers on its own and the two paths cannot be told apart.
  assistRun = { id: msg.id, handler: 1, bytes: 0, ended: false, endStage: msg.end_stage || 'tts', convId: (msg.conversation_id || 'voice-' + Math.random().toString(36).slice(2, 8)) };
  send({ id: msg.id, type: 'result', success: true, result: null });
  assistEvent(send, 'run-start', {
    pipeline: 'mock', language: msg.language || pipe.language || 'en',
    runner_data: startStage === 'stt' ? { stt_binary_handler_id: assistRun.handler, timeout: 300 } : {},
  });
  if (startStage === 'intent') {
    // No audio phase at all: the words arrived with the request.
    assistRun.ended = true;
    answer(send, ((msg.input && msg.input.text) || '').trim());
    return;
  }
  assistEvent(send, 'stt-start', { engine: 'mock', metadata: { format: 'wav', sample_rate: 16000 } });
}

function finishAssist(send) {
  if (!assistRun || assistRun.ended) return;
  assistRun.ended = true;
  const heard = assistRun.bytes > 4000 ? 'turn on the kitchen light' : 'hello';
  assistEvent(send, 'stt-end', { stt_output: { text: heard } });
  if (assistRun.endStage === 'stt') {
    assistEvent(send, 'run-end', {});
    assistRun = null;
    return;
  }
  answer(send, heard);
}

/**
 * Everything after the words are known, whoever produced them — the app's two voice routes differ
 * only in whether HA did the hearing.
 */
function answer(send, heard) {
  assistEvent(send, 'intent-start', { engine: 'mock', language: 'en', intent_input: heard });
  // A bare greeting is the natural thing for an agent to answer with a question, so that is where
  // the follow-up path gets exercised.
  const followUp = askFollowUp(heard === 'hello');
  let speech;
  if (followUp) {
    speech = 'What would you like me to do?';
  } else if (/turn on/i.test(heard)) {
    states['light.kitchen'].state = 'on';
    send({ id: 2, type: 'event', event: { event_type: 'state_changed', data: { entity_id: 'light.kitchen', new_state: stateObj('light.kitchen') } } });
    speech = 'Turned on Kitchen Light';
  } else if (!heard) {
    speech = 'I did not catch that.';
  } else {
    speech = 'I heard you say ' + heard;
  }
  streamAnswer(send, speech, followUp);
}

/**
 * Sends the answer the way a streaming conversation agent does: `intent-progress` deltas as the
 * words are produced, then `intent-end` with the whole of it.
 *
 * Word by word on a timer rather than all at once, because the point of the app's Answering phase
 * is that a long reply is readable while it is still being written — which an instant stream would
 * not exercise at all. An agent that does not stream simply never sends these, which is why the app
 * treats them as optional.
 */
function streamAnswer(send, speech, followUp) {
  const run = assistRun;
  const words = speech.split(' ');
  let i = 0;
  // HA opens the stream with a role delta carrying no content; the app must skip it, not render it.
  assistEvent(send, 'intent-progress', { chat_log_delta: { role: 'assistant' } });
  const tick = () => {
    // A newer run replaced this one, or it was torn down: stop talking into a finished exchange.
    if (assistRun !== run) return;
    if (i < words.length) {
      assistEvent(send, 'intent-progress', { chat_log_delta: { content: (i ? ' ' : '') + words[i] } });
      i += 1;
      setTimeout(tick, 120);
      return;
    }
    assistEvent(send, 'intent-end', {
      intent_output: {
        response: {
          speech: { plain: { speech, extra_data: null } },
          card: {}, language: 'en', response_type: 'action_done', data: { targets: [] },
        },
        conversation_id: run.convId,
        continue_conversation: followUp,
      },
    });
    if (run.endStage === 'tts') {
      assistEvent(send, 'tts-start', { engine: 'mock', language: 'en' });
      // A real pipeline hands back a path under /api/tts_proxy; the app resolves it against the base
      // URL and plays it, so the shape matters more than the bytes.
      assistEvent(send, 'tts-end', {
        tts_output: { media_id: 'media-source://tts/mock', url: '/api/tts_proxy/mock-reply.mp3', mime_type: 'audio/mpeg' },
      });
    }
    assistEvent(send, 'run-end', {});
    assistRun = null;
  };
  tick();
}

function handleAudio(payload, send) {
  if (!assistRun) return;
  if (payload.length <= 1) { finishAssist(send); return; }  // handler byte only = end of stream
  assistRun.bytes += payload.length - 1;
  // Real HA relies on its own VAD; here, enough audio simply ends the utterance on its own so a
  // client that never sends the closing frame is still exercised.
  if (assistRun.bytes > 96000) { assistEvent(send, 'stt-vad-end', {}); finishAssist(send); }
}

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
    case 'assist_pipeline/run':
      startAssist(msg, send);
      break;
    case 'assist_pipeline/pipeline/list':
      send({ id: msg.id, type: 'result', success: true, result: { pipelines: PIPELINES, preferred_pipeline: PREFERRED_PIPELINE } });
      break;
    case 'call_service': {
      // conversation.process is addressed by service_data.agent_id (no target) and answers with a
      // response payload, so it is handled before the ordinary state-mutating path below.
      if (msg.domain === 'conversation' && msg.service === 'process') {
        const d = msg.service_data || {};
        const said = String(d.text || '');
        const convId = d.conversation_id || 'mock-' + Math.random().toString(36).slice(2, 10);
        let speech;
        let responseType = 'action_done';
        const onOff = /\b(turn|switch)\s+(on|off)\b/i.exec(said);
        // "Turn on the light" names no room, which is exactly when a real agent asks back.
        const followUp = askFollowUp(!!onOff && !/kitchen|living/i.test(said));
        if (followUp) {
          responseType = 'query_answer';
          speech = 'Which light did you mean — kitchen or living room?';
        } else if (onOff) {
          const want = onOff[2].toLowerCase();
          const target = /kitchen/i.test(said) ? 'light.kitchen' : 'light.living_room';
          states[target].state = want;
          send({ id: 2, type: 'event', event: { event_type: 'state_changed', data: { entity_id: target, new_state: stateObj(target) } } });
          speech = 'Turned ' + want + ' ' + states[target].attributes.friendly_name;
        } else if (/\?\s*$/.test(said) || /^(what|which|is|are|how)\b/i.test(said)) {
          responseType = 'query_answer';
          speech = 'The living room light is ' + states['light.living_room'].state + '.';
        } else {
          responseType = 'error';
          speech = 'Sorry, I am not aware of any device called that.';
        }
        send({
          id: msg.id, type: 'result', success: true,
          result: {
            context: { id: 'ctx-mock' },
            response: {
              response: {
                speech: { plain: { speech, extra_data: null } },
                card: {},
                language: d.language || 'en',
                response_type: responseType,
                data: responseType === 'error' ? { code: 'no_intent_match' } : { targets: [] },
              },
              conversation_id: convId,
              continue_conversation: followUp,
            },
          },
        });
        break;
      }
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
