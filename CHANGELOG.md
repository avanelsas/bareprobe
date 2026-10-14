# Changelog

## 0.2.0

Depends on BareDOM 4.5.0, BareBuild 0.8.0 and BareReplay 0.5.0, rather than 3.8.0, 0.7.0 and 0.4.0.
Nothing BareProbe reads changed between those versions, so every verb answers as before. An app
that takes BareDOM through BareProbe moves to the 4.x line with it.

## 0.1.0

First release. BareProbe reads a BareBuild app's recorded log from a REPL, and answers with data.

- **The span between a click and the pixels is one value.** A round trip carries what the app asked,
  what it sent, what the server answered, what consumers were handed, and what the page shows now.
- **Every answer is data.** Filter it, thread it, or lift it into a test. `bareprobe.core` takes its
  log as an argument, so it needs no running app.
- **It reads the live page.** Attributes and properties come back as maps. No verb returns an
  element.
- **It answers questions the app never asked.** Invent an event and read what it would have done.
  Author the server's reply and make a failure happen when you want one.

`docs/VERBS.md` names every verb. `src/bareprobe/session.cljs` is a bug hunt as an ordered
procedure, and is where usage is taught.

BareProbe needs the exact versions of BareDOM, BareBuild and BareReplay named in `deps.edn`. It
reads parts of BareBuild that BareBuild does not promise to keep. A different version can make
BareProbe answer wrongly without failing.
