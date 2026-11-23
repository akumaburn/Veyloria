import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import { TTSService } from './ttsService';

class Socket {
  static all: Socket[] = [];
  onopen: (() => void) | null = null;
  onmessage: ((event: { data: string }) => void) | null = null;
  onclose: (() => void) | null = null;
  onerror: (() => void) | null = null;
  send = vi.fn();
  close = vi.fn();
  constructor() { Socket.all.push(this); }
  receive(data: object) { this.onmessage?.({ data: JSON.stringify(data) }); }
}

const source = () => ({ buffer: null, playbackRate: { value: 1 }, connect: vi.fn(), start: vi.fn(), stop: vi.fn(), onended: null as (() => void) | null });
class Context {
  state = 'running';
  sampleRate = 48000;
  destination = {};
  sources: ReturnType<typeof source>[] = [];
  createAnalyser = () => ({ connect: vi.fn(), fftSize: 0, smoothingTimeConstant: 0 });
  createGain = () => ({ connect: vi.fn() });
  createBuffer = (_channels: number, length: number) => ({ getChannelData: () => new Float32Array(length) });
  createBufferSource = () => { const item = source(); this.sources.push(item); return item; };
  suspend = vi.fn(async () => { this.state = 'suspended'; });
  resume = vi.fn(async () => { this.state = 'running'; });
  close = vi.fn();
}

let service: TTSService;
let context: Context;
beforeEach(() => {
  Socket.all = [];
  context = new Context();
  vi.stubGlobal('WebSocket', Socket);
  vi.stubGlobal('AudioContext', class { constructor() { return context; } });
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, json: async () => ({ success: true, data: [] }) })));
  service = new TTSService();
});
afterEach(() => { service.dispose(); vi.unstubAllGlobals(); });

const tick = async () => { for (let i = 0; i < 10; i++) await Promise.resolve(); };

describe('Ming playback', () => {
  it('checks voices without creating or unlocking browser audio', async () => {
    vi.stubGlobal('AudioContext', class { constructor() { throw new Error('Audio needs a user gesture'); } });
    await expect(service.initialize()).resolves.toBeUndefined();
    expect(fetch).toHaveBeenCalledWith('/tts/api/voices', expect.objectContaining({ signal: expect.any(AbortSignal) }));
  });

  it('reports an unavailable voice service instead of retaining stale voices', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: false, status: 500 })));
    await expect(service.initialize()).rejects.toThrow('TTS server returned 500');
  });

  it('sends personality and preserves narrator/dialogue boundaries on restart', async () => {
    const character = { name: 'Ada', personality: 'Quiet and precise', gender: 'female', pronouns: 'she/her', ageYears: 27 };
    await service.play('one', '*I nod.* “Hello.”', undefined, character);
    Socket.all[0].onopen?.();
    expect(JSON.parse(Socket.all[0].send.mock.calls[0][0])).toMatchObject({ character, mode: 'VOICE_DESIGN', text: '*I nod.* “Hello.”' });
    await service.restart('one');
    Socket.all[1].onopen?.();
    expect(JSON.parse(Socket.all[1].send.mock.calls[0][0]).character).toEqual(character);
  });

  it('waits for finalized quotes and plays group replies in streaming order', async () => {
    await service.addStreamingChunk('first', '"unfinished');
    await service.addStreamingChunk('second', 'Other reply');
    await service.finalizeStreaming('second', 'Other reply');
    expect(Socket.all).toHaveLength(0);
    await service.finalizeStreaming('first', '"Finished."');
    expect(Socket.all).toHaveLength(1);
    Socket.all[0].receive({ type: 'COMPLETE' });
    await tick();
    expect(Socket.all).toHaveLength(2);
    Socket.all[1].onopen?.();
    expect(JSON.parse(Socket.all[1].send.mock.calls[0][0]).text).toBe('Other reply');
  });

  it('finishes after the last chunk even when COMPLETE arrives after audio ends', async () => {
    await service.play('one', 'Hello.');
    const ws = Socket.all[0];
    ws.receive({ type: 'START', sample_rate: 24000, total_chunks: 1 });
    ws.receive({ type: 'CHUNK', audio_base64: 'AAA=', chunk_index: 0, duration: 1 });
    expect(context.close).not.toHaveBeenCalled();
    context.sources[0].onended?.();
    ws.receive({ type: 'COMPLETE' });
    expect(service.getPlaybackState('one')).toMatchObject({ isPlaying: false, progress: 100 });
  });

  it('preserves pause/resume and applies speed during browser playback', async () => {
    service.updateConfig({ speed: 1.5 });
    await service.play('one', 'Hello.');
    Socket.all[0].receive({ type: 'CHUNK', audio_base64: 'AAA=', chunk_index: 0, duration: 1 });
    expect(context.sources[0].playbackRate.value).toBe(1.5);
    service.pause('one');
    expect(context.suspend).toHaveBeenCalled();
    await service.resume('one');
    expect(context.resume).toHaveBeenCalled();
    expect(service.getPlaybackState('one')?.isPaused).toBe(false);
  });

  it('stopAll cancels buffered replies and ignores late chunks', async () => {
    await service.play('one', 'Hello.');
    await service.addStreamingChunk('two', 'Buffer');
    service.stopAll();
    Socket.all[0].receive({ type: 'CHUNK', audio_base64: 'AAA=', chunk_index: 0, duration: 1 });
    expect(context.sources).toHaveLength(0);
    await service.finalizeStreaming('three', 'Fresh');
    expect(Socket.all).toHaveLength(2);
  });

  it('cannot resurrect stopped playback after asynchronous initialization', async () => {
    let resolve!: (value: unknown) => void;
    vi.stubGlobal('fetch', vi.fn(() => new Promise(r => { resolve = r; })));
    const pending = service.play('one', 'Hello.');
    await tick();
    service.stop('one');
    resolve({ ok: true, json: async () => ({ success: true, data: [] }) });
    await pending;
    expect(Socket.all).toHaveLength(0);
  });

  it('releases a queued reply when the preceding socket fails', async () => {
    await service.finalizeStreaming('one', 'First');
    await service.finalizeStreaming('two', 'Second');
    Socket.all[0].onerror?.();
    await tick();
    expect(Socket.all).toHaveLength(2);
    expect(service.getPlaybackState('one')?.isPlaying).toBe(false);
  });

  it('continues a queue after one message fails to initialize', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    await service.finalizeStreaming('first', 'First reply.');
    await service.finalizeStreaming('failed', 'Second reply.');
    await service.finalizeStreaming('last', 'Last reply.');
    vi.mocked(fetch).mockRejectedValueOnce(new Error('Connection lost'));
    Socket.all[0].receive({ type: 'COMPLETE' });
    await tick(); await tick(); await tick();
    expect(Socket.all).toHaveLength(2);
    expect(service.getPlaybackState('failed')).toMatchObject({ isPlaying: false, progress: 0, error: 'Connection lost' });
    Socket.all[1].onopen?.();
    expect(JSON.parse(Socket.all[1].send.mock.calls[0][0]).text).toBe('Last reply.');
  });


  it('sends the selected text language without mixing model attribute controls', async () => {
    service.updateConfig({ languageId: 'fr' });
    await service.play('french', 'Bonjour.');
    service.updateConfig({ languageId: 'de' });
    Socket.all[0].onopen?.();
    const request = JSON.parse(Socket.all[0].send.mock.calls[0][0]);
    expect(request.language_id).toBe('fr');
    expect(request.text).toBe('Bonjour.');
    expect(request).not.toHaveProperty('speed');
    expect(request).not.toHaveProperty('pitch');
    expect(request).not.toHaveProperty('emotion');
  });

});

it('marks only fully successful audio as completed and keeps a paused queue paused', async () => {
  await service.play('one', 'Hello.');
  Socket.all[0].receive({ type: 'CHUNK', audio_base64: 'AAA=', chunk_index: 0, duration: 1 });
  service.pause('one');
  Socket.all[0].receive({ type: 'COMPLETE' });
  expect(context.state).toBe('suspended');
  await service.resume('one');
  context.sources[0].onended?.();
  expect(service.getPlaybackState('one')).toMatchObject({ completed: true, progress: 100 });
  await service.play('two', 'Another line.');
  Socket.all[1].receive({ type: 'CHUNK', audio_base64: 'AAA=', chunk_index: 0, duration: 1 });
  Socket.all[1].receive({ type: 'ERROR', error: 'Generation interrupted' });
  context.sources[1].onended?.();
  expect(service.getPlaybackState('two')).toMatchObject({ completed: false, error: 'Generation interrupted' });
});

it('restarting one message clears other queued replies', async () => {
  await service.finalizeStreaming('one', 'First.');
  await service.finalizeStreaming('two', 'Second.');
  await service.restart('one');
  Socket.all[1].receive({ type: 'COMPLETE' });
  await tick();
  expect(Socket.all).toHaveLength(2);
});

it('buffers exactly the next message during playback without regenerating it', async () => {
  await service.finalizeStreaming('narrator', 'The door opens.');
  const first = Socket.all[0];
  first.receive({ type: 'CHUNK', audio_base64: 'AAA=', chunk_index: 0, duration: 1 });
  await service.finalizeStreaming('character', '“Come in.”');
  await service.finalizeStreaming('following', 'She smiles.');
  expect(Socket.all).toHaveLength(1); // Current synthesis retains GPU priority.
  first.receive({ type: 'COMPLETE' });
  await tick();
  expect(Socket.all).toHaveLength(2);
  const next = Socket.all[1];
  next.onopen?.();
  expect(JSON.parse(next.send.mock.calls[0][0]).text).toBe('“Come in.”');
  next.receive({ type: 'CHUNK', audio_base64: 'AAA=', chunk_index: 0, duration: 1 });
  next.receive({ type: 'COMPLETE' });
  expect(context.sources).toHaveLength(1); // Lookahead stays silent until its turn.
  expect(service.getPlaybackState('character')?.isPlaying).toBe(false);
  expect(service.getPlaybackState('character')?.completed).not.toBe(true);
  expect(Socket.all).toHaveLength(2); // Only one message ahead.
  context.sources[0].onended?.();
  await tick();
  expect(context.sources).toHaveLength(2);
  expect(service.getPlaybackState('character')).toMatchObject({ isPlaying: true });
  expect(Socket.all).toHaveLength(3); // Following message now fills the lookahead slot.
});

it('buffers late arrivals without resuming paused playback', async () => {
  await service.finalizeStreaming('first', 'First.');
  Socket.all[0].receive({ type: 'CHUNK', audio_base64: 'AAA=', chunk_index: 0, duration: 1 });
  Socket.all[0].receive({ type: 'COMPLETE' });
  service.pause('first');
  await service.finalizeStreaming('second', 'Second.');
  await tick();
  expect(Socket.all).toHaveLength(2);
  Socket.all[1].receive({ type: 'CHUNK', audio_base64: 'AAA=', chunk_index: 0, duration: 1 });
  Socket.all[1].receive({ type: 'COMPLETE' });
  expect(context.state).toBe('suspended');
  expect(context.sources).toHaveLength(1);
  await service.resume('first');
  context.sources[0].onended?.();
  await tick();
  expect(context.sources).toHaveLength(2);
});

it('cancels lookahead and ignores late audio without marking it completed', async () => {
  await service.finalizeStreaming('first', 'First.');
  Socket.all[0].receive({ type: 'CHUNK', audio_base64: 'AAA=', chunk_index: 0, duration: 1 });
  Socket.all[0].receive({ type: 'COMPLETE' });
  await service.finalizeStreaming('second', 'Second.');
  await tick();
  const next = Socket.all[1];
  service.stopAll();
  next.receive({ type: 'CHUNK', audio_base64: 'AAA=', chunk_index: 0, duration: 1 });
  next.receive({ type: 'COMPLETE' });
  await tick();
  expect(context.sources).toHaveLength(1);
  expect(next.close).toHaveBeenCalled();
  expect(service.getPlaybackState('second')).toMatchObject({ isPlaying: false, completed: false });
});

it('keeps current audio playing if lookahead fails and advances past the failed reply', async () => {
  await service.finalizeStreaming('first', 'First.');
  Socket.all[0].receive({ type: 'CHUNK', audio_base64: 'AAA=', chunk_index: 0, duration: 1 });
  Socket.all[0].receive({ type: 'COMPLETE' });
  await service.finalizeStreaming('second', 'Second.');
  await service.finalizeStreaming('third', 'Third.');
  await tick();
  Socket.all[1].receive({ type: 'ERROR', error: 'Generation failed' });
  expect(service.getPlaybackState('first')?.isPlaying).toBe(true);
  context.sources[0].onended?.();
  await tick(); await tick();
  expect(service.getPlaybackState('second')?.error).toBe('Generation failed');
  expect(service.getPlaybackState('second')?.completed).not.toBe(true);
  expect(Socket.all).toHaveLength(3);
  Socket.all[2].onopen?.();
  expect(JSON.parse(Socket.all[2].send.mock.calls[0][0]).text).toBe('Third.');
});

it('waits for earlier unfinished narration before prefetching a later completed reply', async () => {
  await service.finalizeStreaming('first', 'First.');
  Socket.all[0].receive({ type: 'CHUNK', audio_base64: 'AAA=', chunk_index: 0, duration: 1 });
  Socket.all[0].receive({ type: 'COMPLETE' });
  await service.addStreamingChunk('narration', 'The door');
  await service.finalizeStreaming('character', '“Welcome.”');
  await tick();
  expect(Socket.all).toHaveLength(1);
  await service.finalizeStreaming('narration', 'The door opens.');
  await tick();
  expect(Socket.all).toHaveLength(2);
  Socket.all[1].onopen?.();
  expect(JSON.parse(Socket.all[1].send.mock.calls[0][0]).text).toBe('The door opens.');
});

it('cancels lookahead while voice initialization is pending', async () => {
  await service.finalizeStreaming('first', 'First.');
  Socket.all[0].receive({ type: 'CHUNK', audio_base64: 'AAA=', chunk_index: 0, duration: 1 });
  Socket.all[0].receive({ type: 'COMPLETE' });
  let release!: (value: Response) => void;
  vi.mocked(fetch).mockImplementationOnce(() => new Promise(resolve => { release = resolve; }));
  await service.finalizeStreaming('second', 'Second.');
  await tick();
  service.stopAll();
  release(new Response(JSON.stringify({ success: true, data: [] })));
  await tick();
  expect(Socket.all).toHaveLength(1);
  expect(context.sources).toHaveLength(1);
});
