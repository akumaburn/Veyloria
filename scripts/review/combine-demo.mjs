#!/usr/bin/env node
// Reuse completed creator chapters; preserve both raw takes and their provenance.
import { spawnSync } from "node:child_process";
import {
  readFileSync,
  writeFileSync,
  renameSync,
  existsSync,
  copyFileSync,
} from "node:fs";
import { resolve, join } from "node:path";
const [output, previous] = process.argv
  .slice(2, 4)
  .map((value) => resolve(value));
const through = process.argv[4] || "map-generated";
if (!output || !previous)
  throw Error("Usage: combine-demo.mjs <new-run> <creation-run>");
const dir = join(output, "demo"),
  prior = join(previous, "demo");
const timeline = JSON.parse(readFileSync(join(dir, "timeline.json"), "utf8"));
const source = JSON.parse(readFileSync(join(prior, "timeline.json"), "utf8"));
if (timeline.composition) throw Error("This take has already been combined.");
const last = source.beats.findIndex((beat) => beat.name === through);
if (
  last < 0 ||
  (source.evidence.roster || source.evidence.creation?.roster)?.characterIds
    ?.length !== 3 ||
  source.viewport.width !== 1920
)
  throw Error("The creation chapters did not meet their capture gates.");
const chapters = source.beats.slice(0, last + 1);
if (chapters[0].name !== "premise")
  throw Error("Creation sequence has no opening.");
function run(exe, args) {
  const r = spawnSync(exe, args, {
    encoding: "utf8",
    maxBuffer: 8 * 1024 * 1024,
  });
  if (r.status !== 0) throw Error(r.stderr || String(r.error));
  return r.stdout;
}
const ffmpeg = (args) =>
  run("ffmpeg", ["-hide_banner", "-loglevel", "error", "-y", ...args]);
const duration = (file) =>
  Number(
    run("ffprobe", [
      "-v",
      "error",
      "-show_entries",
      "format=duration",
      "-of",
      "csv=p=0",
      file,
    ]),
  );
function offset(file, meta, label) {
  if (!meta.sync) throw Error("A synchronization slate is required.");
  const scan = join(dir, label + "-sync.txt");
  ffmpeg([
    "-i",
    file,
    "-t",
    "15",
    "-vf",
    `crop=2:2:0:0,signalstats,metadata=print:file=${scan}`,
    "-an",
    "-f",
    "null",
    "-",
  ]);
  const frame = readFileSync(scan, "utf8")
    .split(/(?=frame:\d+)/)
    .find((f) => {
      const y = Number(f.match(/YAVG=([0-9.]+)/)?.[1]),
        u = Number(f.match(/UAVG=([0-9.]+)/)?.[1]),
        v = Number(f.match(/VAVG=([0-9.]+)/)?.[1]);
      return y > 140 && y < 150 && u > 45 && u < 65 && v > 20 && v < 50;
    });
  if (!frame) throw Error("Missing " + label + " recording slate.");
  return Number(frame.match(/pts_time:([0-9.]+)/)?.[1]) - meta.sync.start;
}
const current = join(dir, "adventure.webm"),
  old = join(prior, "adventure.webm");
if (!existsSync(old)) throw Error("Missing original creator footage.");
const oldOffset = offset(old, source, "creation"),
  newOffset = offset(current, timeline, "play");
const prefix = join(dir, "creation.webm"),
  take = join(dir, "adventure-take.webm");
ffmpeg([
  "-i",
  old,
  "-t",
  String(chapters.at(-1).end + oldOffset),
  "-an",
  "-c",
  "copy",
  prefix,
]);
let prefixDuration = duration(prefix);
if (Math.abs(prefixDuration - chapters.at(-1).end - oldOffset) > 0.2) {
  ffmpeg([
    "-i",
    old,
    "-t",
    String(chapters.at(-1).end + oldOffset),
    "-an",
    "-c:v",
    "libvpx",
    "-b:v",
    "3M",
    "-deadline",
    "realtime",
    "-cpu-used",
    "4",
    prefix,
  ]);
  prefixDuration = duration(prefix);
}
if (Math.abs(prefixDuration - chapters.at(-1).end - oldOffset) > 0.2)
  throw Error("Creation prefix has an unexpected duration.");
const shift = prefixDuration + newOffset - oldOffset;
renameSync(current, take);
const list = join(dir, "takes.txt");
const escape = (file) => file.replaceAll("'", "'\\''");
writeFileSync(list, `file '${escape(prefix)}'\nfile '${escape(take)}'\n`);
ffmpeg([
  "-f",
  "concat",
  "-safe",
  "0",
  "-i",
  list,
  "-an",
  "-c",
  "copy",
  current,
]);
const joined = duration(current);
if (Math.abs(joined - prefixDuration - duration(take)) > 0.2)
  throw Error("Joined capture duration does not match its sources.");
writeFileSync(
  join(dir, "timeline-take.json"),
  JSON.stringify(timeline, null, 2),
);
timeline.beats = [
  ...chapters,
  ...timeline.beats.map((beat) => ({
    ...beat,
    start: beat.start + shift,
    end: beat.end + shift,
  })),
];
const inherited = source.audio
  .filter((clip) => clip.at + clip.seconds <= chapters.at(-1).end)
  .map((clip, index) => {
    const file = "source-playback-" + index + ".wav";
    copyFileSync(join(prior, clip.file), join(dir, file));
    return { ...clip, file };
  });
timeline.audio = [
  ...inherited,
  ...timeline.audio.map((clip) => ({
    ...clip,
    at: clip.at + shift,
  })),
];
timeline.sync = source.sync;
timeline.evidence = {
  ...source.evidence,
  ...timeline.evidence,
  sourceApiEvidence: source.evidence,
};
timeline.evidence.creation = source.evidence.creation || {
  generatedCharacterName: source.evidence.generatedCharacterName,
  roster: source.evidence.roster,
};
timeline.composition = {
  creationSource: previous,
  through,
  previousComposition: source.composition || null,
  playSource: output,
  prefixDuration,
  shift,
  oldOffset,
  newOffset,
  note: "Only the selected completed chapters are reused. Original run logs, later failures and raw takes are preserved. The appended capture is a separate disposable session. Beat/audio times are normalized to the joined recording; original wall-clock metadata is retained in timeline-take.json.",
};
writeFileSync(join(dir, "timeline.json"), JSON.stringify(timeline, null, 2));
console.log(
  "Joined verified creation chapters with the new play/continuity recording.",
);
