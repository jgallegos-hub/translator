// Pure text helpers of the relay (no server start on import, so they can be unit-tested).

const OPEN = "<say>";
const CLOSE = "</say>";

/**
 * Streams only what Haiku puts inside <say>…</say>; anything else never reaches the voice.
 * Bench 2026-11-05: on garbled transcripts Haiku reasons in its reply 9/45 times without the tag,
 * 0/45 with it. Holds back a partial tag split across deltas.
 */
export class SayFilter {
  private pending = "";
  private inside = false;
  closed = false;

  push(delta: string): string {
    if (this.closed) return "";
    this.pending += delta;
    if (!this.inside) {
      const open = this.pending.indexOf(OPEN);
      if (open < 0) {
        // Keep only a tail that could still become "<say>".
        this.pending = this.pending.slice(-(OPEN.length - 1));
        return "";
      }
      this.inside = true;
      this.pending = this.pending.slice(open + OPEN.length);
    }
    const close = this.pending.indexOf(CLOSE);
    if (close >= 0) {
      const out = this.pending.slice(0, close);
      this.pending = "";
      this.closed = true;
      return out;
    }
    let hold = 0;
    for (let n = Math.min(CLOSE.length - 1, this.pending.length); n > 0; n--) {
      if (CLOSE.startsWith(this.pending.slice(-n))) { hold = n; break; }
    }
    const out = this.pending.slice(0, this.pending.length - hold);
    this.pending = this.pending.slice(this.pending.length - hold);
    return out;
  }

  /** End of stream: an unterminated <say> still speaks; a held-back partial tag is dropped. */
  flush(): string {
    if (this.closed || !this.inside) return "";
    const out = CLOSE.startsWith(this.pending) ? "" : this.pending;
    this.pending = "";
    return out;
  }

  get sawTag(): boolean {
    return this.inside || this.closed;
  }
}

/** A comma only cuts a very long run-on: ElevenLabs phrases a whole sentence far better than
 *  pieces, and Haiku finishes a sentence ~100 ms after starting it (bench 2026-11-05). */
const LONG_CLAUSE_WORDS = 18;
const words = (s: string) => s.trim().split(/\s+/).length;

/** Next piece to synthesise: a closed sentence (>= 3 words) or a long clause ending in a comma. */
export function takeClause(buf: string): [string, string] | null {
  const re = /([.!?;:]|,)(\s+)|[!?.]$/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(buf)) !== null) {
    const end = m.index + 1;
    const clause = buf.slice(0, end).trim();
    const n = words(clause);
    const isComma = m[1] === ",";
    if (isComma ? n >= LONG_CLAUSE_WORDS : n >= 3) {
      return [clause, buf.slice(end + (m[2]?.length ?? 0))];
    }
  }
  return null;
}

/**
 * Hedged first response: if the first stream yields nothing within [hedgeMs], start a second one
 * and keep whichever yields first (the loser is aborted). Haiku's first token is usually ~0.5 s but
 * spiked past 2 s on 2 of 31 phrases in device round r26, which pushed the app to its local voice.
 */
export async function* hedged<T>(
  start: () => { items: AsyncIterable<T>; abort: () => void },
  hedgeMs: number,
  onHedge: () => void = () => {},
): AsyncGenerator<T> {
  type Step = { run: Run; res: IteratorResult<T> };
  type Run = { abort: () => void; it: AsyncIterator<T>; next: Promise<Step> };
  const launch = (): Run => {
    const handle = start();
    const it = handle.items[Symbol.asyncIterator]();
    const run = { abort: handle.abort, it } as Run;
    run.next = it.next().then((res) => ({ run, res }));
    return run;
  };
  const first = launch();
  let timer: number | undefined;
  const timeout = new Promise<"hedge">((r) => (timer = setTimeout(() => r("hedge"), hedgeMs)));
  const early = await Promise.race([first.next, timeout]);
  clearTimeout(timer);
  let winner: Step;
  if (early === "hedge") {
    onHedge();
    const second = launch();
    // Promise.any: a failed attempt must not beat one that may still answer.
    try {
      winner = await Promise.any([first.next, second.next]);
    } catch (e) {
      throw e instanceof AggregateError ? e.errors[0] : e;
    }
    (winner.run === first ? second : first).abort();
  } else {
    winner = early;
  }
  let res = winner.res;
  while (!res.done) {
    yield res.value;
    res = await winner.run.it.next();
  }
}
