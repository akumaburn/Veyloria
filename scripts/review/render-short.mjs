#!/usr/bin/env node
// Stable product frames and editorial punch-ins. No continuous zoom or camera drift.
import { spawnSync } from "node:child_process";
import { mkdirSync, writeFileSync, existsSync } from "node:fs";
import { join, resolve } from "node:path";
const output = resolve(process.argv[2] || "");
const narrationFlag = process.argv.indexOf("--narration-from");
if (
  !process.argv[2] ||
  (narrationFlag >= 0 && !process.argv[narrationFlag + 1])
) {
  throw Error(
    "Usage: node scripts/review/render-short.mjs <review-directory> [--narration-from <previous-review-directory>]",
  );
}
const narration =
  narrationFlag >= 0 ? resolve(process.argv[narrationFlag + 1]) : output;
const work = join(output, "short-source");
mkdirSync(work, { recursive: true });
// Fractions refer to the original, uncropped desktop capture. Focus changes happen
// at cuts; every pixel position is fixed for the complete duration of each shot.
const scenes = [
  {
    image: "conversation",
    title: "A story you\nstep into.",
    copy: "A narrator sets the scene.\nYour party gives it life.",
    focus: [0.26, 0.18, 0.64, 0.64],
    cut: 1.6,
  },
  {
    image: "companions",
    title: "Meet your\ncompanions.",
    copy: "Distinct personalities.\nA shared adventure.",
    focus: [0.5, 0.2, 0.5, 0.5],
    cut: 1.5,
  },
  {
    image: "local-map",
    title: "Know where\nyou stand.",
    copy: "Explore connected local areas.\nReturn straight to the story.",
    focus: [0.18, 0.18, 0.72, 0.72],
    cut: 1.8,
  },
  {
    image: "global-2d",
    detail: "global-3d",
    title: "See the\nwider world.",
    copy: "Procedural terrain and locations.\nSwitch between 2D and 3D.",
    cut: 1.7,
  },
  {
    image: "inventory",
    title: "Make each\naction count.",
    copy: "Equipment, consumables and gifts.\nConsequences stay in the world.",
    focus: [0.5, 0.36, 0.5, 0.5],
    cut: 1.4,
  },
  {
    image: "combat",
    title: "Face the\nunknown.",
    copy: "Vote on the next move.\nResolve combat through story.",
    focus: [0.5, 0.43, 0.5, 0.5],
    cut: 1.5,
  },
  {
    image: "voice-settings",
    title: "Give the\nstory a voice.",
    copy: "Narrator and character routing.\nOrdered, buffered playback.",
    focus: [0.38, 0.34, 0.6, 0.6],
    cut: 1.6,
  },
  {
    image: "arrival",
    title: "Your choices\nmove the story.",
    copy: "Confirmed travel. Arrival narration.\nA new face at the inn.",
    focus: [0.3, 0.37, 0.63, 0.63],
    cut: 1.6,
  },
];
function ffmpeg(args) {
  const result = spawnSync(
    "ffmpeg",
    ["-hide_banner", "-loglevel", "error", "-y", ...args],
    { encoding: "utf8", maxBuffer: 4 * 1024 * 1024 },
  );
  if (result.status !== 0) throw Error(result.stderr || String(result.error));
}
function probe(file, fields = "format=duration") {
  const result = spawnSync(
    "ffprobe",
    ["-v", "error", "-show_entries", fields, "-of", "json", file],
    { encoding: "utf8" },
  );
  if (result.status !== 0)
    throw Error(result.stderr || "Cannot inspect media: " + file);
  return JSON.parse(result.stdout);
}
const chapters = scenes.map((_, i) => join(narration, `voiceover-${i}.wav`));
const hasChapters = chapters.some(existsSync);
if ((hasChapters || narrationFlag >= 0) && !chapters.every(existsSync))
  throw Error("All eight narration chapters are required.");
const durations = hasChapters
  ? chapters.map((file) => Number(probe(file).format.duration))
  : [];
if (durations.some((value) => !Number.isFinite(value)))
  throw Error("Invalid narration duration.");
const duration = Math.max(5, Math.ceil(Math.max(0, ...durations) + 0.35));
const fps = 30;
const fontChoices = [
  "/usr/share/fonts/TTF/DejaVuSerif.ttf",
  "/usr/share/fonts/truetype/dejavu/DejaVuSerif.ttf",
];
const face = fontChoices.find(existsSync);
if (!face) throw Error("Install DejaVu fonts before rendering.");
const shots = [];
const storyboard = [];
for (let index = 0; index < scenes.length; index++) {
  const scene = scenes[index];
  const headline = join(work, `${index}-title.txt`),
    body = join(work, `${index}-copy.txt`);
  writeFileSync(headline, scene.title);
  writeFileSync(body, scene.copy);
  for (let shot = 0; shot < 2; shot++) {
    const image = shot === 1 && scene.detail ? scene.detail : scene.image;
    const input = join(output, "screenshots", image + ".png");
    if (!existsSync(input)) throw Error("Missing reviewed capture: " + input);
    const region = shot === 1 ? scene.focus : undefined;
    const geometry = probe(input, "stream=width,height").streams[0];
    const crop = region
      ? `crop=${Math.round(geometry.width * region[2])}:${Math.round(geometry.height * region[3])}:${Math.round(geometry.width * region[0])}:${Math.round(geometry.height * region[1])},`
      : "";
    const length = shot === 0 ? scene.cut : duration - scene.cut;
    const frames = Math.round(length * fps);
    const fades =
      index === 0 && shot === 0
        ? ",fade=t=in:st=0:d=0.25"
        : index === scenes.length - 1 && shot === 1
          ? `,fade=t=out:st=${length - 0.3}:d=0.30`
          : "";
    const filter = `${crop}scale=1260:840:flags=lanczos,pad=1920:1080:600:150:color=0x171614,drawbox=x=599:y=149:w=1262:h=842:color=0x4c4438:t=1,drawtext=fontfile=${face}:text='Veyloria':x=64:y=76:fontsize=34:fontcolor=0xffbc4c,drawtext=fontfile=${face}:textfile=${headline}:x=64:y=275:fontsize=62:line_spacing=14:fontcolor=0xf4eee2,drawtext=fontfile=${face}:textfile=${body}:x=64:y=505:fontsize=25:line_spacing=16:fontcolor=0xc6bba8,drawtext=fontfile=${face}:text='A shared world. A living story.':x=64:y=900:fontsize=22:fontcolor=0xffbc4c,drawtext=fontfile=${face}:text='Scripted review story / real product interface':x=64:y=1005:fontsize=18:fontcolor=0xa99b86${fades},format=yuv420p`;
    const clip = join(work, `${index}-${shot}.mp4`);
    ffmpeg([
      "-loop",
      "1",
      "-framerate",
      String(fps),
      "-i",
      input,
      "-vf",
      filter,
      "-frames:v",
      String(frames),
      "-an",
      "-c:v",
      "libx264",
      "-preset",
      "fast",
      "-tune",
      "stillimage",
      "-crf",
      "18",
      clip,
    ]);
    shots.push(clip);
    storyboard.push({
      chapter: index,
      shot,
      image,
      kind: shot === 0 ? "overview" : region ? "punch-in" : "mode cut",
      crop: region || null,
      seconds: frames / fps,
    });
  }
}
const list = join(work, "shots.txt");
writeFileSync(list, shots.map((file) => `file '${file}'`).join("\n"));
const film = join(work, "picture.mp4");
ffmpeg(["-f", "concat", "-safe", "0", "-i", list, "-c", "copy", film]);
const destination = join(output, "veyloria-short.mp4");
if (hasChapters) {
  const sound = join(work, "narration.wav");
  const filters =
    chapters
      .map(
        (_, i) =>
          `[${i}:a]aresample=48000,apad,atrim=duration=${duration},asetpts=PTS-STARTPTS[a${i}]`,
      )
      .join(";") +
    ";" +
    chapters.map((_, i) => `[a${i}]`).join("") +
    `concat=n=${chapters.length}:v=0:a=1[audio]`;
  ffmpeg([
    ...chapters.flatMap((file) => ["-i", file]),
    "-filter_complex",
    filters,
    "-map",
    "[audio]",
    "-c:a",
    "pcm_s16le",
    sound,
  ]);
  ffmpeg([
    "-i",
    film,
    "-i",
    sound,
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
    String(duration * scenes.length),
    "-movflags",
    "+faststart",
    destination,
  ]);
} else {
  ffmpeg(["-i", film, "-c", "copy", "-movflags", "+faststart", destination]);
}
writeFileSync(
  join(output, "short-storyboard.json"),
  JSON.stringify(
    {
      durationSeconds: duration * scenes.length,
      dimensions: "1920x1080",
      theme: "dark",
      motion:
        "Locked frames with deliberate punch-in and mode-change cuts; no continuous pan or zoom.",
      audio: hasChapters ? "real Ming narrator voiceover" : "silent, captioned",
      narrationSource: hasChapters ? narration : null,
      shots: storyboard,
      boundary:
        "Fictional scripted review story, actual product screens. Narration is Ming synthesis when supplied; browser speech transport tests use a synthetic tone.",
    },
    null,
    2,
  ),
);
// Verify the encoded film, not only the rendering instructions. Each hold must
// remain frozen until its planned cut (apart from the opening/closing fades).
const inspection = spawnSync(
  "ffmpeg",
  [
    "-hide_banner",
    "-i",
    destination,
    "-vf",
    "freezedetect=n=-60dB:d=0.5",
    "-an",
    "-f",
    "null",
    "-",
  ],
  { encoding: "utf8", maxBuffer: 4 * 1024 * 1024 },
);
if (inspection.status !== 0)
  throw Error("Cannot verify encoded picture stability.");
const holds = [];
let begin;
for (const line of inspection.stderr.split("\n")) {
  const start = line.match(/freeze_start: ([0-9.]+)/),
    end = line.match(/freeze_end: ([0-9.]+)/);
  if (start) begin = Number(start[1]);
  if (end && begin !== undefined) {
    holds.push([begin, Number(end[1])]);
    begin = undefined;
  }
}
let offset = 0;
const stability = storyboard.map((shot, index) => {
  const start = offset + (index === 0 ? 0.3 : 0),
    end = offset + shot.seconds - (index === storyboard.length - 1 ? 0.3 : 0);
  offset += shot.seconds;
  const stable = holds.some(([a, b]) => a <= start + 0.05 && b >= end - 0.05);
  return { chapter: shot.chapter, shot: shot.shot, start, end, stable };
});
const encoded = probe(
  destination,
  "format=duration:stream=codec_type,codec_name,width,height",
);
const valid =
  stability.every((shot) => shot.stable) &&
  Math.abs(Number(encoded.format.duration) - duration * scenes.length) < 0.1 &&
  encoded.streams.some(
    (stream) =>
      stream.codec_type === "video" &&
      stream.width === 1920 &&
      stream.height === 1080,
  ) &&
  (!hasChapters ||
    encoded.streams.some((stream) => stream.codec_type === "audio"));
writeFileSync(
  join(output, "video-quality.json"),
  JSON.stringify({ passed: valid, encoded, holds: stability }, null, 2),
);
if (!valid)
  throw Error(
    "The encoded short did not pass stability, timing or stream checks.",
  );
console.log(destination);
