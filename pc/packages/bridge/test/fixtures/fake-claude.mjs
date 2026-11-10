#!/usr/bin/env node
// Stand-in for `claude -p --input-format stream-json ...` used by the bridge tests.
// Records argv and env keys into the cwd, then answers each user message by keyword:
//   "lento"            stream slowly; honours control_request interrupt
//   "sordo"            stream slowly; ignores interrupts (forces kill + --resume)
//   "crash"            exit(3) in the middle of the turn
//   "falla"            error result (error_max_turns)
//   anything else      replay fixtures/turn-launch.jsonl
import { appendFileSync, readFileSync, writeFileSync } from 'node:fs';
import { createInterface } from 'node:readline';

const here = new URL('.', import.meta.url);
const argv = process.argv.slice(2);
const resumeIdx = argv.indexOf('--resume');
const sessionId = resumeIdx >= 0 ? argv[resumeIdx + 1] : `fake-${process.pid}`;

writeFileSync('args.json', JSON.stringify(argv));
writeFileSync('env.json', JSON.stringify(Object.keys(process.env)));
appendFileSync('spawns.log', `${resumeIdx >= 0 ? `resume ${sessionId}` : 'new'}\n`);

const emit = (obj) => process.stdout.write(`${JSON.stringify(obj)}\n`);
const fixture = (name) =>
  readFileSync(new URL(name, here), 'utf8').split('\n').filter(Boolean).map((l) => l.replaceAll('SESSION', sessionId));
const delta = (text) => ({
  type: 'stream_event',
  event: { type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text } },
  session_id: sessionId,
  parent_tool_use_id: null,
});
const result = (subtype) => ({ type: 'result', subtype, is_error: subtype !== 'success', duration_ms: 5, session_id: sessionId });

let slow = null;

function startSlow(honourInterrupt) {
  let n = 0;
  slow = { honourInterrupt, timer: setInterval(() => emit(delta(`Frase número ${++n}. `)), 40) };
}

function handleInterrupt(requestId) {
  emit({ type: 'control_response', response: { subtype: 'success', request_id: requestId, response: {} } });
  if (!slow || !slow.honourInterrupt) return;
  clearInterval(slow.timer);
  slow = null;
  for (const line of fixture('interrupted.jsonl').slice(1)) process.stdout.write(`${line}\n`);
}

function handleUser(text) {
  emit({ type: 'system', subtype: 'init', session_id: sessionId, model: 'fake', tools: ['mcp__phone__read_screen'] });
  if (text.includes('lento')) return startSlow(true);
  if (text.includes('sordo')) return startSlow(false);
  if (text.includes('crash')) {
    emit(delta('Empiezo'));
    return setTimeout(() => process.exit(3), 20);
  }
  if (text.includes('falla')) return emit(result('error_max_turns'));
  for (const line of fixture('turn-launch.jsonl').slice(1)) process.stdout.write(`${line}\n`);
}

const rl = createInterface({ input: process.stdin });
rl.on('line', (line) => {
  const msg = JSON.parse(line);
  if (msg.type === 'control_request' && msg.request?.subtype === 'interrupt') return handleInterrupt(msg.request_id);
  if (msg.type === 'user') {
    const content = msg.message.content;
    const text = typeof content === 'string' ? content : content.map((c) => c.text ?? '').join('');
    appendFileSync('utterances.log', `${text}\n`);
    handleUser(text);
  }
});
rl.on('close', () => process.exit(0));
