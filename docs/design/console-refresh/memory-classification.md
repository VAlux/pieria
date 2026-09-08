# Memory classification in the console

The primary type owns the colored rail and filled badge. A subtype uses a separate,
neutral outlined badge; execution status is a third, text-labeled signal. This keeps
new classifications from competing for colors or changing the canonical memory types.

`memory-presentation.js` is the shared presentation adapter for list, recall, and
drawer. It recognizes trace events as “Tool call” and trace instructions as “Trace
recipe”, using `payload.source` with legacy topic-key fallback. Future producers can
provide `payload.subtype`: unknown values receive a readable neutral badge and appear
automatically in the subtype filter. Add specialized previews to the adapter when a
new subtype has structured fields worth displaying. Do not infer classifications
from incidental words in the content.

Cards prioritize content, then labeled provenance. Tool calls show descriptions,
commands or file paths, explicit outcomes, exit codes, and captured failure messages.
Unknown outcomes remain unknown even if an exit code is present. Full invocation
arguments, original content, topic keys, session IDs and raw payloads remain available
in the drawer. Content is rendered as text, never executable HTML or shell input.

Card timestamps prefer occurred time, then stated time, then stored time; their
tooltip identifies the chosen time and also shows stored time. Sorting retains the
existing store-time semantics. The same cards are used in recall results, without
changing their ranking.

Run the presentation regression checks with:

```sh
node --experimental-default-type=module --test modules/daemon/src/test/js/memory-presentation.test.mjs
```

For browser checks use sample data covering all four primary types, trace success,
failure and unknown outcomes, a future subtype, malformed payloads, long commands,
and missing metadata. Verify filtering, keyboard access to details, full raw-content
disclosure, and narrow screens. Keep browser fixtures separate from stored memories.
