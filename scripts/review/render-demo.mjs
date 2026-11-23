#!/usr/bin/env node
// Edit real, verified browser actions. Keep the entire product viewport in frame.
import { spawnSync } from "node:child_process";
import { readFileSync, writeFileSync, mkdirSync, existsSync } from "node:fs";
import { resolve, join } from "node:path";
const output = resolve(process.argv[2] || "");
if (!process.argv[2])
  throw Error("Usage: node scripts/review/render-demo.mjs <review-directory>");
const dir = join(output, "demo"),
  timeline = JSON.parse(readFileSync(join(dir, "timeline.json"), "utf8"));
const input = join(dir, "adventure.webm"),
  work = join(dir, "edit");
mkdirSync(work, { recursive: true });
function exec(exe, args) {
  const r = spawnSync(exe, args, {
    encoding: "utf8",
    maxBuffer: 8 * 1024 * 1024,
  });
  if (r.status !== 0) throw Error(r.stderr || String(r.error));
  return r.stdout;
}
const ffmpeg = (args) =>
  exec("ffmpeg", ["-hide_banner", "-loglevel", "error", "-y", ...args]);
const font = [
  "/usr/share/fonts/TTF/DejaVuSans.ttf",
  "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
].find(existsSync);
if (!font) throw Error("DejaVu Sans is required.");
const rawInfo = JSON.parse(
  exec("ffprobe", [
    "-v",
    "error",
    "-show_entries",
    "format=duration",
    "-of",
    "json",
    input,
  ]),
);
let sourceOffset =
  Number(rawInfo.format.duration) -
  (timeline.recordedEnd - timeline.started) / 1000;
if (timeline.sync) {
  const scan = join(work, "sync.txt");
  ffmpeg([
    "-i",
    input,
    "-t",
    "20",
    "-vf",
    `crop=2:2:0:0,signalstats,metadata=print:file=${scan}`,
    "-an",
    "-f",
    "null",
    "-",
  ]);
  const frames = readFileSync(scan, "utf8").split(/(?=frame:\d+)/);
  const marker = frames.find((frame) => {
    const y = Number(frame.match(/YAVG=([0-9.]+)/)?.[1]),
      u = Number(frame.match(/UAVG=([0-9.]+)/)?.[1]),
      v = Number(frame.match(/VAVG=([0-9.]+)/)?.[1]);
    return y > 140 && y < 150 && u > 45 && u < 65 && v > 20 && v < 50;
  });
  if (!marker) throw Error("Cannot find the capture synchronization slate.");
  sourceOffset =
    Number(marker.match(/pts_time:([0-9.]+)/)?.[1]) - timeline.sync.start;
}
if (!Number.isFinite(sourceOffset))
  throw Error("Invalid video clock synchronization.");
for (const name of [
  "opening",
  "voices",
  "health-before",
  "use-item",
  "health-after",
  "local-before",
  "arrival",
  "innkeeper",
  "local-after",
  "route",
  "journey",
  "world-arrival",
  "vote",
  "combat-result",
  "ending",
  "premise",
  "templates",
  "character-prompt",
  "character-result",
  "roster-prompt",
  "roster-result",
  "roster-saved",
  "relationships",
  "relationship-direction",
  "alignment",
  "map-design",
  "map-generated",
  "empires-2d",
  "empires-3d",
  "dice",
  "bookmarks",
  "branching",
  "world-state",
  "knowledge",
  "agents",
  "illustrations",
  "publish",
  "community",
  "party",
  "achievements",
  "portability",
  "settings",
]) {
  if (!timeline.beats.some((beat) => beat.name === name))
    throw Error("Incomplete adventure capture: missing " + name);
}
if (!(timeline.evidence.journeyProgress?.currentStep > 0))
  throw Error("Missing actual journey progress.");
if (
  timeline.viewport.width !== 1920 ||
  timeline.viewport.height !== 960 ||
  timeline.evidence.browserScale?.scale !== 1
)
  throw Error(
    "Recapture at native 1920x960 and browser zoom 100%; do not enlarge an undersized capture.",
  );
const shots = [];
const add = (name, caption, start, end, audio = []) => {
  if (end - start < 0.3) throw Error("Unusable scene " + name);
  shots.push({ name, caption, start, end, audio });
};
for (const beat of timeline.beats) {
  if (beat.name === "voices") {
    const heard = timeline.audio.filter(
      (a) => a.at >= beat.start && a.at < beat.end,
    );
    if (
      !heard.some((a) => a.speaker === "narrator") ||
      !heard.some((a) => a.speaker === "character")
    )
      throw Error(
        "The film must contain actual narrator AND character playback.",
      );
    for (const [index, clip] of heard.entries())
      add(
        "voice-" + index,
        clip.speaker === "character"
          ? "Mira Solstice — actual character voice playback"
          : "Narrator — actual in-product voice playback",
        Math.max(beat.start, clip.at - 0.35),
        clip.at + clip.seconds + 0.4,
        [{ ...clip, delay: 0.35 }],
      );
  } else if (
    [
      "character-prompt",
      "character-result",
      "roster-prompt",
      "roster-result",
      "map-design",
    ].includes(beat.name)
  ) {
    // Preserve the real Generate/Save click immediately following the composed prompt/result.
    add(
      beat.name,
      beat.caption,
      beat.start,
      beat.end + (beat.name === "map-design" ? 0.45 : 1.1),
    );
  } else if (beat.name === "empires-3d") {
    add(beat.name, beat.caption, beat.end - 4.8, beat.end);
  } else if (beat.name === "publish") {
    add("publish", beat.caption, beat.start + 0.9, beat.start + 1.9);
    add(
      "publish-action",
      "Publish the prepared story, then open its community reader.",
      beat.start + 1.7,
      beat.start + 3.5,
    );
  } else if (beat.name === "arrival") {
    // Hold the composed narration before the next shot starts scrolling to the greeting.
    add(beat.name, beat.caption, beat.start + 0.6, beat.end - 0.6);
  } else if (beat.name === "journey") {
    // Omit the 3D renderer's loading frames; retain actual progress in the loaded world.
    add(
      beat.name,
      "Travel underway — elapsed journey time compressed",
      beat.end - 7,
      beat.end - 0.35,
    );
  } else if (beat.name === "choose-inn") {
    add(
      beat.name,
      beat.caption,
      beat.start,
      Math.min(beat.start + 4.5, beat.end),
    );
  } else if (
    ["journey", "combat-result"].includes(beat.name) &&
    beat.end - beat.start > 12
  ) {
    add(beat.name + "-action", beat.caption, beat.start, beat.start + 3.5);
    add(
      beat.name + "-result",
      beat.name === "journey"
        ? "Journey continues — elapsed travel time compressed"
        : beat.caption,
      beat.end - 7,
      beat.end,
    );
  } else {
    const max = [
      "arrival",
      "ending",
      "health-before",
      "health-after",
      "local-before",
      "local-after",
    ].includes(beat.name)
      ? 5.5
      : 9;
    if (beat.name === "community")
      beat.caption =
        "Published story listing: synopsis, genre and reader ratings.";
    // Keep the result portion rather than a long network wait.
    if (beat.end - beat.start > max + 3) {
      add(beat.name + "-action", beat.caption, beat.start, beat.start + 3);
      add(beat.name + "-result", beat.caption, beat.end - 5, beat.end);
    } else
      add(
        beat.name,
        beat.name === "world-arrival"
          ? "After the journey — arrival at the Old Observatory."
          : beat.caption,
        Math.max(beat.start, beat.end - max),
        beat.end,
      );
  }
}
const usedCommentary = new Set();
for (const shot of shots) {
  const exact = join(output, "demo-narration", shot.name + ".wav");
  const key = existsSync(exact)
    ? shot.name
    : shot.name.replace(/-(action|result)$/, "");
  const file = join(output, "demo-narration", key + ".wav");
  if (existsSync(file) && !usedCommentary.has(key)) {
    const info = JSON.parse(
      exec("ffprobe", [
        "-v",
        "error",
        "-show_entries",
        "format=duration",
        "-of",
        "json",
        file,
      ]),
    );
    shot.commentary = { file, seconds: Number(info.format.duration) };
    usedCommentary.add(key);
  }
}
// Establish at native scale; zoom only to explain a specific control or consequence.
const focusFor = (name) => {
  if (name === "templates") return { zoom: 1.8, x: 0.65, y: 0, enter: true };
  // These dialogs already use almost the full viewport height: keep their headers and footers intact.
  if (
    [
      "roster-result",
      "relationships",
      "relationship-direction",
      "alignment",
    ].includes(name)
  )
    return { zoom: 1, x: 0.5, y: 0.5 };
  if (["map-generated", "empires-3d"].includes(name))
    return { zoom: 1.2, x: 0.55, y: 0.6, enter: true };
  if (
    [
      "character-prompt",
      "character-result",
      "roster-prompt",
      "roster-result",
      "roster-saved",
      "relationships",
      "relationship-direction",
      "alignment",
      "map-design",
      "publish",
      "party",
    ].includes(name)
  )
    return {
      zoom: 1.15,
      x: 0.5,
      y: 0.35,
      enter: ![
        "character-result",
        "roster-result",
        "relationship-direction",
      ].includes(name),
    };
  if (
    [
      "dice",
      "bookmarks",
      "branching",
      "world-state",
      "knowledge",
      "agents",
      "illustrations",
    ].includes(name)
  )
    return { zoom: 1.4, x: 1, y: 0.5, enter: true };
  if (name === "voice-0") return { zoom: 1.4, x: 0.5, y: 0.32, enter: true };
  if (name.startsWith("voice-")) return { zoom: 1.4, x: 0.5, y: 0.32 };
  if (["health-before", "use-item", "health-after"].includes(name))
    return { zoom: 1.55, x: 1, y: 0.5, enter: name === "health-before" };
  if (["local-before", "local-after"].includes(name))
    return { zoom: 1.3, x: 0.45, y: 0.5, enter: name === "local-before" };
  if (["arrival", "innkeeper"].includes(name))
    return { zoom: 1.3, x: 0.5, y: 0.32, enter: name === "arrival" };
  if (name === "vote") return { zoom: 1.65, x: 1, y: 1, enter: true };
  if (name === "combat-result") return { zoom: 1.65, x: 1, y: 0.3 };
  if (name === "ending") return { zoom: 1, x: 0.5, y: 0.5 };
  return { zoom: 1, x: 0.5, y: 0.5 };
};
let position = 0;
for (const [index, shot] of shots.entries()) {
  const text = join(work, `${index}.txt`);
  writeFileSync(text, shot.caption);
  const seconds =
    Math.ceil(
      Math.max(shot.end - shot.start, (shot.commentary?.seconds || 0) + 0.5) *
        30,
    ) / 30;
  shot.timelineStart = position;
  shot.duration = seconds;
  position += seconds;
  const fade =
    index === 0
      ? ",fade=t=in:st=0:d=0.3"
      : index === shots.length - 1
        ? `,fade=t=out:st=${seconds - 0.4}:d=0.4`
        : "";
  const focus = focusFor(shot.name);
  shot.focus = focus;
  const ease = "(1-pow(1-min(max((on-24)/18,0),1),3))";
  const zoom = focus.enter
    ? `1+${focus.zoom - 1}*${ease}`
    : focus.exit
      ? `${focus.zoom}-${focus.zoom - 1}*(1-pow(1-min(on/21,1),3))`
      : String(focus.zoom);
  // Supersampling gives subpixel camera motion; after 600 ms the crop locks.
  const camera =
    focus.zoom === 1
      ? "scale=1920:960:flags=lanczos"
      : `fps=30,scale=3840:1920:flags=lanczos,zoompan=z='${zoom}':x='(iw-iw/zoom)*${focus.x}':y='(ih-ih/zoom)*${focus.y}':d=1:s=1920x960:fps=30`;
  const spotlight = [
    "health-before",
    "use-item",
    "health-after",
    "vote",
    "combat-result",
  ].includes(shot.name)
    ? "drawbox=x=0:y=0:w=1536:h=960:color=black@0.55:t=fill,"
    : "";
  // Avoid freezing on navigation flashes or the next shot's first scroll frame.
  const sourceDuration =
    shot.end -
    shot.start -
    (shot.name.startsWith("voice-")
      ? 0
      : Math.min(0.45, (shot.end - shot.start) * 0.25));
  const filter = `trim=duration=${sourceDuration},setpts=PTS-STARTPTS,tpad=stop_mode=clone:stop_duration=${Math.max(0, seconds - sourceDuration).toFixed(6)},trim=duration=${seconds},${spotlight}${camera},pad=1920:1080:0:0:color=0x171614,drawbox=x=0:y=960:w=1920:h=1:color=0x584b36:t=1,drawtext=fontfile=${font}:textfile=${text}:x=42:y=995:fontsize=28:fontcolor=0xeee8dc,drawtext=fontfile=${font}:text='Veyloria':x=1735:y=1037:fontsize=22:fontcolor=0xffbc4c${fade}`;
  const file = join(work, `${index}.mp4`);
  ffmpeg([
    "-ss",
    String(shot.start + sourceOffset),
    "-i",
    input,
    "-t",
    String(shot.end - shot.start),
    "-vf",
    filter,
    "-t",
    String(seconds),
    "-an",
    "-r",
    "30",
    "-c:v",
    "libx264",
    "-preset",
    "fast",
    "-crf",
    "18",
    file,
  ]);
  shot.file = file;
}
const list = join(work, "shots.txt");
writeFileSync(list, shots.map((s) => `file '${s.file}'`).join("\n"));
const picture = join(work, "picture.mp4");
ffmpeg(["-f", "concat", "-safe", "0", "-i", list, "-c", "copy", picture]);
const clips = shots.flatMap((s) => [
  ...s.audio.map((a) => ({
    ...a,
    file: join(dir, a.file),
    at: s.timelineStart + a.delay,
  })),
  ...(s.commentary ? [{ ...s.commentary, at: s.timelineStart + 0.2 }] : []),
]);
const audioArgs = clips.flatMap((a) => ["-i", a.file]);
const filters =
  clips
    .map(
      (a, i) =>
        `[${i}:a]aresample=48000,adelay=${Math.round(a.at * 1000)}:all=1[a${i}]`,
    )
    .join(";") +
  ";" +
  clips.map((_, i) => `[a${i}]`).join("") +
  `amix=inputs=${clips.length}:normalize=0,loudnorm=I=-16:TP=-1.5:LRA=11,aresample=48000,apad,atrim=duration=${position}[mix]`;
const sound = join(work, "playback.wav");
ffmpeg([
  ...audioArgs,
  "-filter_complex",
  filters,
  "-map",
  "[mix]",
  "-c:a",
  "pcm_s16le",
  sound,
]);
const chapterStarts = [
  ["premise", "Begin a story"],
  ["character-prompt", "Characters and relationships"],
  ["map-design", "Shape a world"],
  ["opening", "An adventure with voices"],
  ["empires-2d", "Empires and travel"],
  ["vote", "Choose together"],
  ["dice", "Chance and continuity"],
  ["publish", "Share and keep your story"],
  ["ending", "Veyloria"],
].map(([name, title]) => ({
  title,
  start: shots.find((shot) => shot.name === name)?.timelineStart,
}));
if (chapterStarts.some((chapter) => chapter.start === undefined))
  throw Error("A planned chapter is missing.");
const chapterFile = join(dir, "chapters.ffmeta");
writeFileSync(
  chapterFile,
  ";FFMETADATA1\ntitle=Veyloria — complete product overview\ncopyright=Copyright 2024-2026 Amir Farhad Eslampanah\n" +
    chapterStarts
      .map(
        (chapter, index) =>
          `[CHAPTER]\nTIMEBASE=1/1000\nSTART=${Math.round(chapter.start * 1000)}\nEND=${Math.round((chapterStarts[index + 1]?.start ?? position) * 1000)}\ntitle=${chapter.title}\n`,
      )
      .join(""),
);
writeFileSync(
  join(dir, "chapters.json"),
  JSON.stringify(chapterStarts, null, 2),
);
const destination = join(output, "veyloria-overview.mp4");
ffmpeg([
  "-i",
  picture,
  "-i",
  sound,
  "-f",
  "ffmetadata",
  "-i",
  chapterFile,
  "-map_metadata",
  "2",
  "-map_chapters",
  "2",
  "-map",
  "0:v",
  "-map",
  "1:a",
  "-c:v",
  "copy",
  "-c:a",
  "aac",
  "-b:a",
  "192k",
  "-t",
  String(position),
  "-movflags",
  "+faststart",
  destination,
]);
writeFileSync(
  join(dir, "edit-decisions.json"),
  JSON.stringify(
    {
      duration: position,
      sourceOffset,
      composition: timeline.composition || null,
      frame:
        "Native 1920x960 product viewport at browser zoom 100%, one capture pixel per output pixel; separate 120px caption rail. Timed 600ms focus zooms then locked holds; 3D uses native camera zoom.",
      speech:
        "Actual PCM scheduled by the browser during narrator/character playback, synchronized to its source footage.",
      shots,
    },
    null,
    2,
  ),
);
console.log(destination);

const probe = JSON.parse(
  exec("ffprobe", [
    "-v",
    "error",
    "-show_streams",
    "-show_format",
    "-of",
    "json",
    destination,
  ]),
);
const pictureStream = probe.streams.find((s) => s.codec_type === "video");
if (
  pictureStream?.width !== 1920 ||
  pictureStream?.height !== 1080 ||
  !probe.streams.some((s) => s.codec_type === "audio") ||
  Math.abs(Number(probe.format.duration) - position) > 0.1
)
  throw Error("Invalid final film streams or duration.");
for (const clip of clips)
  if (
    clip.at < 0 ||
    clip.at + clip.seconds > position + 0.05 ||
    readFileSync(clip.file).length < 45
  )
    throw Error("Missing or truncated speech: " + clip.file);
for (const [index, shot] of shots.entries())
  ffmpeg([
    "-ss",
    String(shot.timelineStart + shot.duration * 0.6),
    "-i",
    destination,
    "-frames:v",
    "1",
    "-vf",
    "scale=480:270",
    join(work, "review-" + String(index).padStart(3, "0") + ".png"),
  ]);
ffmpeg([
  "-framerate",
  "1",
  "-i",
  join(work, "review-%03d.png"),
  "-vf",
  "tile=4x" + Math.ceil(shots.length / 4),
  "-frames:v",
  "1",
  join(dir, "contact-sheet.png"),
]);
writeFileSync(
  join(dir, "export-quality.json"),
  JSON.stringify(
    {
      passed: true,
      duration: position,
      width: 1920,
      height: 1080,
      sourceOffset,
      completeSpokenExcerpts: clips.length,
      actualPlaybackSpeakers: timeline.audio.map((a) => a.speaker),
      captureEvidence: timeline.evidence,
      review:
        "Inspect contact-sheet.png and the moving film; stream and routing checks do not establish perceptual speech quality.",
    },
    null,
    2,
  ),
);
