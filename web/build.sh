#!/bin/sh
# Builds the iPhone web app into a new directory $1: web/, the assets it reads (about 22 MB) and sw.js with a hash of
# every file, so an installed copy downloads only what changed. Needs node 20+.
set -eu
out=${1:?usage: web/build.sh <new output directory>}
case $out in /*) ;; *) out=$PWD/$out ;; esac
[ ! -e "$out" ] || { echo "$out already exists" >&2; exit 1; }
cd "$(dirname "$0")/.."

node web/check.js assets
mkdir -p "$out/assets"
cp web/index.html web/style.css web/dex.js web/app.js web/manifest.webmanifest web/icon.png "$out/"
# Not read on the web: looks, Smogon sets and type icons.
cp -r assets/sprites assets/art assets/pixel assets/shiny assets/shiny-art assets/shiny-pixel assets/items assets/learn assets/info assets/names assets/lang "$out/assets/"
for f in pokemon merged evo moves abilities items natures games types past; do cp "assets/$f.tsv" "$out/assets/"; done

node -e '
const fs = require("fs");
const crypto = require("crypto");
const out = process.argv.at(-1);
const sha = data => crypto.createHash("sha1").update(data).digest("hex").slice(0, 8);
const files = {};
for (const f of fs.readdirSync(out, { recursive: true }).sort()) {
  if (fs.statSync(out + "/" + f).isFile()) files[f] = sha(fs.readFileSync(out + "/" + f));
}
const list = JSON.stringify(files);
fs.writeFileSync(out + "/sw.js", fs.readFileSync("web/sw.js", "utf8").replace("%VERSION%", sha(list)).replace("/*FILES*/{}", () => list));
console.log(Object.keys(files).length + " files, version " + sha(list));
' "$out"
