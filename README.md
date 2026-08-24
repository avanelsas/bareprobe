# BareProbe

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="public/assets/bare_darkmode.svg">
    <source media="(prefers-color-scheme: light)" srcset="public/assets/bare_lightmode.svg">
    <img alt="Project Logo" src="public/assets/bare_lightmode.svg" width="160">
  </picture>
</p>

**A REPL surface over [BareBuild](https://github.com/avanelsas/barebuild)'s recorded log.**

A bug in a BareBuild app is somewhere between the click and the pixels. BareProbe hands you that
whole span as one value: what the app decided you asked for, what it sent, what the server
answered, what consumers were handed, and what the page shows right now. You read those legs in
order and stop at the first one that is wrong.

**Part of a set.** [BareDOM](https://github.com/avanelsas/baredom) is a comprehensive web component
library. [BareBuild](https://github.com/avanelsas/barebuild) is a small runtime that drives web
components from server state alone. [BareReplay](https://github.com/avanelsas/barereplay) is a
time-travel replay debugger for BareBuild-based apps. BareProbe reads the same log from a REPL.

## What it gives you

**The entire BareBuild cycle is a single map.** A gesture becomes an intent, an intent
becomes a request, a request is answered, the answer moves a resource, the resource projects a
view, and consumers write the DOM. Every one of those hops is a place a bug can live, and every one
of them is a key you can read. `live/round-trips` is the log as those values, and `live/latest`
puts what is on screen beside the most recent one.

**It answers with data rather than a view.** Every answer is a value you can filter, thread, diff
or hand on. `bareprobe.core` takes its log as an argument, so a question you asked at a REPL lifts
into a `deftest` without being rewritten.

**It reads the page, not only the log.** Click an element and get its attributes and its properties
back as maps, or ask what a resource's consumers are showing without clicking at all. No verb hands
back a live element, which would go stale the moment the page re-projects.

**It answers questions the app never asked.** Invent an event the app never sent and read what it
would have done. Author the server's reply and make a failure happen on demand rather than waiting
for one.

## What it is not

BareReplay reconstructs a state and re-projects it onto live components. BareProbe reads the log as
data. There is no dock, no overlay, and no visual surface of any kind.

It works against any BareBuild app. It has no demo of its own, and is exercised inside BareBuild's.

## Install

BareProbe is a development-time tool. Add it beside your app's own dependencies.

```clojure
;; deps.edn
com.github.avanelsas/bareprobe {:mvn/version "0.1.0"}

;; shadow-cljs.edn
[com.github.avanelsas/bareprobe "0.1.0"]
```

**BareProbe reads the log BareReplay records**, so the recorder has to be in place before the first
event. If you already run BareReplay, you have it. If you want the log without the dock, install the
recorder alone.

```clojure
(ns your-app
  (:require [barebuild.recorder :as recorder]
            [barereplay.store :as store]
            [barebuild.core :as barebuild]))

(recorder/install! store/record!)   ; or (barereplay.init/init!) for the dock as well
(barebuild/init)
```

**Nothing in your app requires BareProbe.** You require it at the REPL, in the session you are
debugging, so the compiler never reaches it from application code and it cannot enter a release
build.

## How to use it

Attach a ClojureScript REPL to your build.

**Work in a buffer rather than at a prompt.** Open a `.cljs` file, evaluate forms in place, and
write what you learn beside them. The investigation accumulates into a file you can keep next to the
fix, which a prompt's history does not.

**[src/bareprobe/session.cljs](src/bareprobe/session.cljs) is the recipe.** It is a bug hunt as an
ordered procedure: a table mapping a symptom to the step it starts at, then one question per step
with one form and what a wrong answer means. Copy it into a buffer and work down it.

**[docs/VERBS.md](docs/VERBS.md) is the map.** It names every verb both namespaces publish, and a
test pins it, so a verb cannot be added without appearing there. The docstrings are the reference.

## The two halves

`bareprobe.core` is pure. Every verb takes its entries as an argument and performs no effect, so it
needs no running app.

`bareprobe.live` owns the ambient log, the live page, and the request edge.

## Status

First release. 0.1.0 on Clojars.

**The version pins are exact requirements rather than floors.** BareProbe reads parts of BareBuild
that BareBuild does not promise to keep, and an unknown event no-ops rather than throwing. A
different version can make BareProbe answer wrongly without failing, so do not relax the pins to
ranges. Conformance tests cover each of the things it reads, driven through BareBuild's own
pipeline.

## Build and test

```
npm test                       # unit tests under Node
clj-kondo --lint src test      # zero warnings and errors
clojure -T:build jar           # target/bareprobe-VERSION.jar
```

The jar carries `src/` without `session.cljs`, which is a worked session rather than code.

## Disclaimer

- The code for BareProbe has been written by me.
- I used Claude as a brainstorming tool to sharpen my thoughts and ideas.
- I used Claude to write tests, review the code, and help write some of the docs
