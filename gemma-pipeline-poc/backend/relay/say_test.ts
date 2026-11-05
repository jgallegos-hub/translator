import { assertEquals } from "jsr:@std/assert@1.0.13";
import { hedged, SayFilter, takeClause } from "./say.ts";

const run = (deltas: string[]) => {
  const f = new SayFilter();
  const out = deltas.map((d) => f.push(d)).join("") + f.flush();
  return { out, sawTag: f.sawTag };
};

Deno.test("speaks only what is inside the tags", () => {
  assertEquals(run(["<say>Hello there.</say>"]).out, "Hello there.");
  assertEquals(run(["Hmm, this is garbled.\n\n<say>Tell me.</say> Wait, no."]).out, "Tell me.");
});

Deno.test("tags split across deltas", () => {
  assertEquals(run(["<s", "ay>Hel", "lo.</", "say>"]).out, "Hello.");
  assertEquals(run(["<say>a < b</sa", "y>"]).out, "a < b");
});

Deno.test("no tag means silence; empty tag means silence", () => {
  assertEquals(run(["This looks like a maintenance instruction."]), { out: "", sawTag: false });
  assertEquals(run(["<say></say>"]), { out: "", sawTag: true });
});

Deno.test("cut off before the closing tag still speaks, without the partial tag", () => {
  assertEquals(run(["<say>Hello there"]).out, "Hello there");
  assertEquals(run(["<say>Hello there</sa"]).out, "Hello there");
});

Deno.test("sentences are cut, normal commas are not", () => {
  assertEquals(takeClause("Well, I don't know, you tell me. And"), ["Well, I don't know, you tell me.", "And"]);
  assertEquals(takeClause("Well, I don't know, you tell"), null);
  assertEquals(takeClause("What do you think?"), ["What do you think?", ""]);
});

Deno.test("a very long run-on is cut at a comma", () => {
  const long = "one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen,";
  assertEquals(takeClause(long + " and more"), [long, "and more"]);
});

Deno.test("short sentences wait for more text", () => {
  assertEquals(takeClause("Thank you. And"), null);
});

const fake = (delayMs: number, items: string[], log: string[], name: string) => ({
  items: (async function* () {
    await new Promise((r) => setTimeout(r, delayMs));
    yield* items;
  })(),
  abort: () => log.push(`abort ${name}`),
});
const collect = async (g: AsyncGenerator<string>) => {
  const out: string[] = [];
  for await (const x of g) out.push(x);
  return out;
};

Deno.test({
  name: "hedged: a fast first attempt never starts a second",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    const log: string[] = [];
    let starts = 0;
    const out = await collect(hedged(() => (starts++, fake(5, ["a", "b"], log, "x")), 100));
    assertEquals(out, ["a", "b"]);
    assertEquals(starts, 1);
  },
});

Deno.test({
  name: "hedged: a stuck first attempt loses to the second and is aborted",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    const log: string[] = [];
    let starts = 0;
    let hedges = 0;
    const out = await collect(hedged(
      () => (starts++ === 0 ? fake(100, ["slow"], log, "first") : fake(5, ["fast"], log, "second")),
      20,
      () => hedges++,
    ));
    assertEquals(out, ["fast"]);
    assertEquals(hedges, 1);
    assertEquals(log, ["abort first"]);
  },
});

Deno.test({
  name: "hedged: a failed first attempt falls through to the second",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    const log: string[] = [];
    let starts = 0;
    const failing = () => ({
      items: (async function* (): AsyncGenerator<string> {
        await new Promise((r) => setTimeout(r, 30));
        throw new Error("boom");
      })(),
      abort: () => log.push("abort failing"),
    });
    const out = await collect(hedged(() => (starts++ === 0 ? failing() : fake(60, ["ok"], log, "second")), 10));
    assertEquals(out, ["ok"]);
  },
});
