# The verbs

Every verb BareProbe publishes. The docstrings are the reference, and `(dir bareprobe.live)` is
the live index. This is the map.

`bareprobe.core` is detached: every verb takes its entries as an argument and performs no effect.
`bareprobe.live` is attached: it owns the ambient log, the live page and the request edge.

A test pins this document to what the two namespaces actually publish.

## bareprobe.core

Reading the log.

| Verb | Answers |
|---|---|
| `(at entries n)` | The record at position `n`, payload and all. |
| `(view entries n)` | The projection that record handed to consumers, or nil when it notified none. |
| `(round-trips entries)` | One value per event and what it caused: asked, sent, answered, and handed to consumers. |
| `(state entries)` `(state entries n)` | The resources as recorded at `n`, and every diagnostic reported up to it. |
| `(resource entries rid)` `(resource entries rid n)` | What one resource held at `n`. The way in when you do not want the whole state. |
| `(diff entries n)` `(diff entries a b)` | What moved, as `{resource-id {key [before after]}}`. One record, or between two states. |
| `(label event)` | A short readable phrase for one event. |
| `(summary entries)` | One row per record: position, ids, event key, label, the keys that moved, the diagnostics. |
| `(disagreements entries)` | The positions where the recorded event no longer reproduces its own record. Empty is the answer you want. |
| `(coverage entries)` | What the recorded traffic showed of each resource's declared shape. |

Asking what-if. These take a resource rather than the log.

| Verb | Answers |
|---|---|
| `(step resource event)` | `barebuild.resource/step`, re-exported verbatim. The resource the event leaves behind, and the effects. |
| `(read-step resource event)` | Whether the event moves the resource, and the diagnostics the step reports. |
| `(answer resource event-k payload)` | An answering event carrying the `:request/id` the resource has in flight. |

Selecting rows. These take a summary row.

| Verb | Answers |
|---|---|
| `(moved-any ks)` | A predicate: did the row move any of these keys? |
| `(diagnosed? row)` | Does the row carry diagnostics? |

The one diagnostic shape. A step reports one, a verb throws one, and both read the same.

| Verb | Answers |
|---|---|
| `(refuse message code detail)` | Throws, carrying `code` and `detail` under `:probe/code` and `:probe/detail`. |

`moved-any` answers a predicate, so the keys go to it and the result goes to `filter`. Selecting on
the event key is `(comp #{:write-ack :write-failed} :probe/event-key)`. A predicate is a value and
composes through `every-pred` and `some-fn`.

## bareprobe.live

**Every `core` verb whose first argument is the log appears here at one arity lower, supplying the
ambient log.** That is `at`, `view`, `round-trips`, `state`, `resource`, `diff`, `summary`,
`disagreements` and `coverage`. They are sugar and nothing more. The rest take a resource or a summary row, so there
is no log to supply.

The log itself.

| Verb | Answers |
|---|---|
| `(log)` | The recorded entries. |
| `(clear!)` | Drops everything recorded so far, and forgets which answers BareProbe gave. |
| `(status)` | What is recorded, and what is armed at the edge. |
| `(rendered rid)` | What that resource's consumers show now, by consumer id. Reading stamps pick ids. |
| `(latest)` | The most recent round trip with `rendered` on it. The one verb that reads past and present together. |
| `(table!)` `(table! rows)` `(table! ks rows)` | Prints rows as an aligned table. With no arguments, one line per round trip. The one verb that prints. |

The page. Ids in, maps out: nothing here returns an element.

| Verb | Answers |
|---|---|
| `(attrs id)` | The attributes of the element the id names. What it was given. |
| `(props id)` | The properties its own classes declare. What it holds now, including text the user typed. Refuses an id naming anything but a component. |

The id comes from `baredom.dev.pick`, which a session requires directly. It publishes the picking
verbs, and BareProbe re-exports none of them: `pick!` enters click-to-select and answers an id,
`picked` reads that answer afterwards, `cancel!` leaves select mode, `show!` outlines the element
an id names, and `hide!` takes the outline off. `(doc pick/pick!)` is their reference.

The request edge.

| Verb | Answers |
|---|---|
| `(serve! rid envelope)` `(serve! rid envelope {:kind …})` | Queues an envelope as the answer to the next request for that resource. `:kind` pins it to `:read` or `:write`. |
| `(serving)` | What is queued. |
| `(clear-serving!)` `(clear-serving! rid)` | Drops what is queued, all of it or one resource's. |
| `(injected? row)` | A predicate: did BareProbe answer the request this row records? |
| `(fault! faults)` | Arms the request edge with `{:latency ms :fail-every n}`, or clears it with nil. |
