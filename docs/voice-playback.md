# Ming voice playback

Veyloria uses `inclusionAI/Ming-3.0-tts-0.5B`: **Voice Design** creates each voice, and reference-based TTS maintains it across later clips. The inference code and weights are distributed separately. The adapter pins the inference repository to `c88c023ea6ccc08fd1e7a1d289cb6246c21deff1`; the Hugging Face model revision can be pinned with `MING_MODEL_REVISION` (default `main`). Upstream publishes the release under MIT.

## Speech pipeline

1. Chat GraphQL queries request each character's `personality`, `gender`, `pronouns`, and `ageYears`. The conversation roster and the selected single-chat character carry it into live messages as well as saved messages. Narration/action messages omit character metadata even if they have a character ID.
2. While the LLM streams text, the browser reserves that message's place in the playback queue. It waits for final text before synthesis: this avoids speaking incomplete quotes, changing speakers mid-fragment, or repeating text. Group messages play in the order they first appeared, even if later messages finish first.
3. The browser sends one WebSocket request to `/tts/ws/tts`: `{action: "synthesize", text, character: {id, name, personality, gender, pronouns, ageYears}, mode: "VOICE_DESIGN"}`. `language_id` is sent separately (default `en`). The server chooses initial Voice Design or reference-based continuation; legacy voice IDs never select a different model.
4. The adapter removes thinking/code blocks and image markup. Before chunking, standalone uppercase letter tokens are spaced for speech (`CID` → `C I D`, `NPC` → `N P C`), in both narration and dialogue. This does not change displayed chat text or Personality. Existing acoustic-reference transcripts remain unchanged to stay aligned with their audio; new references store the expanded spoken text. Third-person prose and actions use a clear adult male narrator in a clean modern studio recording, with an open vocal tone, crisp consonants, conversational intonation, varied emphasis, and subtle curiosity and suspense, at a measured audiobook pace. In character-authored messages, sentence-level first-person pronouns (`I`, `my`, `me`, `myself`, `we`, `our`, and related forms) route unquoted actions and inner thoughts to that character’s existing voice; for example, `I shift my weight` and `*I shift my weight*`. Mixed paragraphs switch voices at sentence boundaries. Quoted pronouns inside reported action text are masked before viewpoint detection. A stray opening double quote before a standalone italic action paragraph (for example, `"*I tuck my hair back.*` followed by a blank line) is removed in the speech script before quote masking, so malformed formatting cannot hide the action’s first-person viewpoint. Balanced quotations and quoted speech reported inside third-person actions keep their normal routing; the saved/displayed reply is unchanged. Balanced straight or curly double-quoted speech outside action blocks uses **a concise vocal brief derived from the authoring character's Personality and profile**. The builder uses two natural-language sentences covering age/gender presentation, timbre and pitch, pacing and articulation, and an emotional baseline; it maps recognized vocal/behavioral cues deterministically and uses clear, conversational defaults for unmatched traits, rather than copying biography or abstract world details into the instruction. `Woman`/`female` requests a female voice: the brief opens with the label `female voice.` and asks for a natural feminine pitch, because Voice Design otherwise drifts toward a male voice; `Man`/`male` requests a male voice. Declared gender takes precedence over pronouns; pronouns and unambiguous self-description support older profiles with no structured gender. Names never determine gender. For example, `The corridor darkens. *I lift the lamp.* “Stay close.”` becomes narrator → character. First-person actions and direct speech share the same reference voice. Explicit `NARRATION` messages never receive character metadata, so even first-person narrator prose stays with the narrator. Missing Personality and unidentified speakers also stay with the narrator.
5. Adjacent character segments with the same speaker are merged. Chunking targets 200 characters softly: ordinary sentences remain intact up to 600 characters. Exceptional sentences split at clauses, or word boundaries as a fallback, and their continuation fragments are buffered into one playback chunk. Inline emphasis and wrapped lines never create audible boundaries. Narrator sentences stay separate so each can breathe before the next. Every voice design prompt begins with the fixed prefix `smooth voice,`, before the narrator description or character profile and Personality. The prefix is part of the voice-cache identity, so references generated before this change are replaced by newly designed voices on their next use. The first usable clip for each voice calls `MingTTS.synthesize(task="VOICE_DESIGN", controls={"style": ...}, use_zero_spk_emb=True)`. Its complete trimmed PCM and exact spoken text are saved as an acoustic reference. Follow-up segments and later messages use `task="TTS"`, `prompt_wav_path`, `prompt_text`, `language_id="en"`, and `use_spk_emb=True` to clone that designed voice; style controls are not mixed into the cloning task. Languages other than English/Chinese use `MULTILINGUAL` for reference-based continuations, with an explicit `language_id` on every call. Every call uses `raw_text=True` so Ming does not split the prepared utterance a second time. Decoder limits scale with utterance length, capped at 2400 steps, so longer sentences have time to finish. Clips shorter than one second, longer than fifteen seconds, or effectively silent are not pinned; design continues until a usable clip exists. Every take must suit its text: one far shorter than its text could be spoken in (an early stop) or far longer (a run-on) is regenerated with the next seed, up to four attempts. A designed clip must also match the voice's requested presentation: its median pitch must be at least 155 Hz for a female voice and at most 175 Hz for a male voice, including the narrator. Non-binary or unspecified voices are not checked. If no design matches, the closest complete take is pinned, so a character never changes voice between lines. If every line cloned from a reference is cut off, that reference is discarded and the voice designed again. Audio is never cropped independently of its transcript.
6. The adapter returns `START` (actual model sample rate and chunk count), ordered `CHUNK` messages (base64 little-endian signed 16-bit mono PCM, text, speaker, duration), then `COMPLETE`, or `ERROR`. Web Audio resamples each buffer for the existing context. Each voice is brought to the same baseline: a gain is set from the active speech level of its saved reference (or of the take itself before a reference exists) to reach -20 dBFS, and applied to every line in that voice, so delivery within and between its lines keeps its natural dynamics. The gain is lowered for any line whose peaks would exceed -1 dBFS. The reference itself is stored unscaled. Generated tails quieter than about 26 dB below the clip's loud speech, including faint room noise, are trimmed to a 120 ms pause. This matters most for references: Ming continues a cloned voice from the end of its reference, and a noisy tail makes later lines stop after a few steps. Trimming also avoids long silence between speakers. Narration uses a measured audiobook voice-design instruction and an extra 250 ms pause only at sentence endings or the end of the message. Continuation fragments retain just 20 ms of generated tail silence and receive no extra narrator pause. Its speech samples are otherwise passed through exactly: no phase vocoder, time stretching, or tone filtering. The pause is added after storing the unprocessed reference. Character audio receives no narrator pacing adjustment. Speech speed is applied by browser playback rate, keeping the model task valid. This also changes pitch, as standard playback-rate adjustment does.
7. Pause suspends the audio context; resume continues from that position. Restart retains both original text and character metadata. Stop closes synthesis, clears buffered replies, and rejects late callbacks. The server stops scheduling segments on disconnect; a running GPU call finishes under the inference lock before another client uses the model.

The ownership rule follows the existing roleplay contract: a character writes their own quoted speech and first-person actions/thoughts. This is deliberately not a speaker-guessing LLM. A narrator message containing several characters' quotations stays in the narrator voice; supporting those requires explicit speaker IDs in structured generated output.

Voice continuity is now acoustic, rather than seed-only. The fixed narrator and each character have separate persistent reference WAV/transcript pairs keyed by resolved model revision, inference/cache version, speaker identity, derived vocal instruction, language, and seed. Subsequent lines reuse the first usable reference without replacing it. Profile edits that change the derived vocal brief create a new voice reference; different characters never share one. Stable seeds remain in use, but an acoustic reference now anchors timbre. This improves consistency without guaranteeing identical delivery on every utterance.

References live in `$HF_HOME/voice-references` by default, or `MING_VOICE_CACHE_DIR` when set. Native development retains them under `.runtime/tts/huggingface/voice-references`; Docker retains them in its existing model volume. Each pair is committed with checksum-protected metadata, so partial/corrupt files are regenerated. To deliberately redesign a voice, stop the service and remove that voice's reference directory, or change its profile, then restart. No uploaded or arbitrary external reference paths are accepted. In-memory upstream prompt caching is disabled to avoid retaining unbounded GPU tensors as more characters speak.

## Prompt contract and script preparation

`POST /tts/api/speech-plan` accepts the same `text`, optional `character`, and `language_id` as a synthesis request. It returns each prepared utterance as three separate, copy-ready fields: `instruction`, `language_id`, and `text`. This previews the plan without generating audio. Each utterance has one speaker; description text is never prepended to spoken words. For the dense Ming API the adapter maps `instruction` exclusively to `controls={"style": instruction}` with `task="VOICE_DESIGN"`, rather than passing both style and `instruction` or mixing built-in/attribute controls.

For example, Elara's impulsive, cynical Personality and young-adult female profile produce:

```json
{
  "instruction": "smooth voice, female voice. A young adult woman with a clear, natural timbre and a natural feminine pitch. Use natural conversational pacing and crisp, direct articulation, with a lively, wry emotional baseline.",
  "language_id": "en",
  "text": "I check my C I D before we leave."
}
```

Known parenthetical delivery cues such as `(angrily)` and `(clears throat)` are removed, while genuine parenthetical spoken prose and punctuation remain. Supported bracket cues are normalized to the pinned checkpoint's exact case: `[Laughter]`, `[Sigh]`, `[Breathing]`, `[Uhm]`, and its other cookbook tags. Unknown bracket directions are removed. Paralinguistic tags are used only for English/Chinese reference-based TTS; initial Voice Design removes them, and its saved transcript records exactly the words used to generate the anchor. Other target languages remove these unsupported tags. Standalone cue-only fragments without spoken words are skipped.

Text preparation also spells uppercase initialisms, leading-zero serials and decimal/version components, and localizes ampersands. It leaves ordinary quantities and ambiguous locale-dependent dates alone rather than guessing their meaning. Set **Text language** in voice settings to English, Chinese, German, Spanish, French, Korean, or Japanese. This identifies the script language and does not translate messages; narrator previews use a corresponding sample sentence. Changing it cancels pending playback and uses a language-specific reference cache. Sichuanese/Cantonese attribute modes are not combined with Voice Design.

## Reading the conversation

Narration and action blocks have visible play/pause/restart controls. Narration always uses the narrator; character actions retain their profile so the segmenter can distinguish first-person actions from third-person prose.

**Read conversation** reads unread loaded replies in chronological order, skipping player messages, incomplete/error replies, and OOC/system/dice notices. System-authored narration is included. Completed replies are remembered locally per conversation across page reloads; only content fingerprints are stored, not spoken prose or audio. Edited or regenerated text becomes unread again. Manual and live-response playback also count when they finish successfully. Failed or interrupted speech remains unread. History is bounded to 100 conversations and 1,000 completed messages per conversation and clears on logout.

**Pause reading / Resume reading** keeps the current audio position and queue without regenerating speech. **Stop reading**, manual playback of another message, disabling voices, changing the voice server/language, or leaving the conversation cancels the queue. Continuing afterward skips completed replies and restarts the interrupted reply from its beginning; reloading does not restore a position within an audio clip. **Read from beginning** explicitly replays loaded replies, and **Read again** appears after all loaded replies finish. Newly saved readable messages join an active read-through once; loading older unread history also adds those messages after the existing queue.

Live autoplay accepts both token streams and completed-only story events, including opening and closing narrator messages. It reserves their arrival order before voice-service initialization and finalizes each event once. Starting a read-through includes every unread loaded reply, even narration before an already playing character reply; only successfully completed speech is skipped.

Playback pre-generates one following finalized message while the current audio plays, for both live autoplay and read-through. Lookahead starts after the current message finishes synthesis so it cannot compete for the GPU needed by current speech. Incoming PCM stays buffered and silent until its turn, then plays without a second synthesis request. Only one following message is generated ahead; cancellation closes its socket and drops buffered audio. Prefetch never marks speech as read. This reduces inter-message delay when synthesis finishes before playback; it cannot eliminate delays when generation is slower than listening or the next message has not arrived yet.

The control is distinct from automatically reading newly generated responses. Live-response playback yields to an active read-through. Restarting a single message clears other queued replies. Browser audio is unlocked directly from the read button before awaiting the voice server. Playback errors are visible and retryable; network/generation failures never mark a reply as read.

## Startup and download

`./run-dev.sh` starts MySQL and Redis, then starts Ming natively on the host GPU before launching the frontend/backend. It reuses an already healthy service. The native runner avoids Docker GPU passthrough requirements:

```sh
./scripts/run-tts.sh
```

The runner requires `uv`, creates an isolated Python 3.11 environment under `.runtime/tts`, installs CUDA 12.8 PyTorch and the pinned Ming source, and downloads missing model files into `.runtime/tts/huggingface`. Later starts reuse dependencies and the model cache. `TTS_RUNTIME_DIR`, `HF_HOME`, `MING_DEVICE`, and `TTS_PORT` can override those defaults. Runtime files are excluded from Git and Docker build contexts. It binds to loopback; Vite proxies browser requests to it.

For container deployment, a normal `docker compose up -d --build` includes Ming and requires working NVIDIA Container Toolkit/GPU passthrough. For the container voice service alone:

```sh
docker compose up -d --build tts
docker compose logs -f tts
```

On container startup, Hugging Face `snapshot_download` fetches missing files (or resumes an interrupted download) into the named `tts-models` volume. Restarts reuse that cache and check the selected revision. Weights are never baked into the image. The process loads one model before accepting traffic; `/health` becomes available only once loading succeeds. A failed download or model load fails startup visibly and Compose restarts the service. Initial downloads can take several minutes. Offline restarts require a complete cached revision (`HF_HUB_OFFLINE=1` can be supplied to the container when needed).

The image uses PyTorch/torchaudio 2.7.1 with CUDA 12.8 to support Blackwell GPUs, while retaining Ming's pinned Transformers 4.46.3 and other inference requirements. Flash Attention and FST text normalization are optional upstream and disabled here; SDPA handles attention. The container requests NVIDIA GPUs and requires the NVIDIA Container Toolkit. `MING_DEVICE` defaults to `cuda:0`; changing the device alone does not remove Compose's GPU reservation. The adapter also enables CPU bfloat16 autocast and limits CPU threads (`MING_CPU_THREADS`, default 4); a real narrator/character synthesis smoke test works on CPU, but decoding is far too slow for interactive playback. CUDA remains the intended deployment target.

Vite proxies `/tts` to `127.0.0.1:9797` (override `TTS_DEV_URL` for another development endpoint). Production Nginx proxies the same path to `tts:9797`, including WebSocket upgrades, so browsers use the page's origin and HTTPS/WSS scheme. Only the loopback host port is exposed directly. External direct connections require an explicit `TTS_ALLOWED_ORIGINS` allowlist. Same-origin WebSockets work without an allowlist entry. Connection checks do not create or resume browser audio. Voice HTTP requests time out after ten seconds; a failed request shows an error and a Reconnect action, with no loading spinner. Browser audio is initialized only for playback. Legacy saved Kokoro defaults migrate to `narrator` and `/tts`; enabled/auto-play/speed preferences remain intact.

## Checks

```sh
PYTHONPATH=services/tts python -m pytest services/tts/tests
cd frontend
node node_modules/vitest/vitest.mjs run src/services/ttsService.test.ts src/components/chat/StreamingMessageLayout.test.tsx src/components/chat/MessageVoiceRouting.test.tsx
node node_modules/typescript/bin/tsc --noEmit
```

Adapter tests use fake synthesis to cover routing, validation, model download configuration, WebSocket ordering, and concurrent inference serialization without a GPU. An actual startup/speech smoke test is required when changing inference dependencies or model revisions.

References: [official Ming inference API and cookbook](https://github.com/inclusionAI/Ming-3.0-tts), [model distribution](https://huggingface.co/inclusionAI/Ming-3.0-tts-0.5B), [PyTorch Blackwell/CUDA 12.8 support](https://pytorch.org/blog/pytorch-2-7/).

## Development lifecycle and ownership

The native runner uses a single Uvicorn worker without an auto-reloader. Restart
the voice process after editing Python source; frontend HMR and Quarkus reloads
do not reload the speech adapter. Reuse the existing `HF_HOME` or
`MING_VOICE_CACHE_DIR` to retain established acoustic references. Do not remove
cached voices merely to apply a speech-plan change.

Veyloria's adapter is covered by the repository [Apache License 2.0](../LICENSE),
Copyright 2024-2026 Amir Farhad Eslampanah. Ming's source/model distribution and
all inference dependencies retain their upstream licenses and notices. Model
weights, references and the cloned inference source belong in ignored runtime
storage, not in Git commits.
