const fs = require("node:fs");
const path = require("node:path");

const root = path.resolve(__dirname, "..");
const requested = (process.argv[2] || "").trim().replace(/^v/i, "");
const match = requested.match(/^(\d+)\.(\d+)\.(\d+)$/);

if (!match) {
    console.error("Usage: npm run version:set -- 1.0.1");
    process.exit(1);
}

const [, majorText, minorText, patchText] = match;
const major = Number(majorText);
const minor = Number(minorText);
const patch = Number(patchText);
const version = `${major}.${minor}.${patch}`;

function writeJson(filePath, update) {
    const fullPath = path.join(root, filePath);
    const json = JSON.parse(fs.readFileSync(fullPath, "utf8"));
    update(json);
    fs.writeFileSync(fullPath, JSON.stringify(json, null, 2) + "\n");
}

writeJson("package.json", json => {
    json.version = version;
});

writeJson("package-lock.json", json => {
    json.version = version;
    if (json.packages && json.packages[""]) {
        json.packages[""].version = version;
    }
});

console.log(`WeWatch Windows version set to ${version}.`);
