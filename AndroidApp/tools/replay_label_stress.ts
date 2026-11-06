import { resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

if (!process.argv[2]) throw new Error("Usage: bun tools/replay_label_stress.ts <serverAPI directory>");
const { compileRules } = await import(pathToFileURL(resolve(process.argv[2], "src/rules.ts")).href);
const root = fileURLToPath(new URL("../", import.meta.url));
const bundle = await Bun.file(resolve(root, "app/src/main/assets/label-rules.json")).json();
const rules = compileRules(bundle);
const records = await Bun.file(resolve(root, "build/label-stress/reads.json")).json();
const manifest = await Bun.file(resolve(root, "build/label-stress/manifest.json")).json();
const cases = new Map<string, any>(manifest.cases.map((item: any) => [item.file, item]));
let before = 0, after = 0;
for (const record of records) {
    const values = rules.apply(record.read.codes, record.read.text, record.read.ocrWords ?? [], record.read.ocrLines ?? []);
    record.reparsedValues = values;
    record.reparsedRulesUpdatedAt = bundle.updatedAt;
    let oldCorrect = true, newCorrect = true;
    for (const [field, expected] of Object.entries(cases.get(record.file).expected)) {
        const actual = (input: any[]) => [...new Set(input.filter(value => value.field === field).map(value => value.value))].sort();
        const wanted = expected === null ? [] : [expected];
        const oldMatch = JSON.stringify(actual(record.read.values)) === JSON.stringify(wanted);
        const newMatch = JSON.stringify(actual(values)) === JSON.stringify(wanted);
        oldCorrect &&= oldMatch;
        newCorrect &&= newMatch;
        if (oldMatch && !newMatch) throw new Error(`Regression: ${record.file} ${field}`);
        if (!newMatch) console.log(record.file, field, JSON.stringify(actual(values)), "expected", expected);
    }
    before += Number(oldCorrect);
    after += Number(newCorrect);
}
await Bun.write(resolve(root, "build/label-stress/reads-reparsed.json"), JSON.stringify(records, null, 2));
console.log("All fields correct:", before, "->", after, "/", records.length);
