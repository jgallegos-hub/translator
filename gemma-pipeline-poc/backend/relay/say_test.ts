import { assertEquals } from "jsr:@std/assert@1.0.13";
import { SayFilter, takeClause } from "./say.ts";

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
