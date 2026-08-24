(ns bareprobe.session
  "A bug hunt as an ordered procedure. Each step asks one question, has one form, and says what a
  wrong answer means. No code, only the comment block: requiring this namespace does nothing and
  compiles to nothing.")

(comment
  ;; ═══ Before you start ═══════════════════════════════════════════════════════════════
  ;; Set these first, every session. They are per-runtime and a page reload loses them. Without
  ;; them a single envelope prints every row it carries, and the answer you want is off screen.
  (set! *print-length* 10)
  (set! *print-level* 3)

  (require '[bareprobe.core :as core] '[bareprobe.live :as live]
           '[baredom.dev.pick :as pick] :reload)

  ;; Work in a buffer and eval in place. Write what you learn beside the form, including what you
  ;; ruled out. What you ruled out is what saves you the second time.

  ;; Large values go to shadow's UI at http://localhost:9631, under Inspect. What is tapped is
  ;; navigable: a row's :probe/position opens the record, a key in :probe/moved-keys opens that
  ;; key's [before after] pair, and a diagnostic's position opens the record that reported it.
  ;; Clicking beats typing a position into `at`.
  (tap> (live/summary))

  ;; ═══ Where to start ═════════════════════════════════════════════════════════════════
  ;; Do not walk the whole thing every time. The symptom names the step.
  ;;
  ;;   almost anything                               step 4
  ;;   you did something and nothing happened        step 3, then 4
  ;;   it failed and you do not know why             step 2
  ;;   it fires too often, or too slowly             step 1
  ;;
  ;; Steps 0 and 1 are cheap. Run them anyway.

  ;; ═══ 0. Reproduce, and check the log is worth reading ═══════════════════════════════
  (live/clear!)
  ;; Now drive the app: the fewest actions that show the symptom.
  (live/status)

  ;; Does the log still mean what it meant when it was recorded? Empty is the answer you want. A
  ;; non-empty answer names records whose event no longer reproduces its own recorded result, and
  ;; makes every reading below suspect. Stop and read it.
  (live/disagreements)

  ;; ═══ 1. What happened, in order ═════════════════════════════════════════════════════
  ;; One line per round trip: what was asked, whether it went out, what came back, whether
  ;; consumers were told, and what was reported. Scan it for a line that is missing something.
  ;; The trip itself is step 4, opened once a line looks wrong.
  (live/table!)

  ;; The trips as data, to hand on or to filter. `summary` is the flatter reading, one row per
  ;; record rather than one per trip.
  (live/round-trips)
  (live/summary)

  ;; ═══ 2. Did any step report something ═══════════════════════════════════════════════
  ;; A diagnostic often names the fault outright. An empty :probe/diagnostics vector is truthy,
  ;; which is why this predicate exists.
  (filter core/diagnosed? (live/summary))

  ;; ═══ 3. Did a gesture land and do nothing ═══════════════════════════════════════════
  ;; A row that moved nothing where you acted is a gesture that was lost, unroutable, or declined.
  ;; `state` alone would show a healthy resource and no sign of it.
  (remove :probe/moved? (live/summary))

  ;; Narrow to the keys you are chasing. `moved-any` takes the keys and answers a predicate, so the
  ;; keys go here and the result goes to `filter`.
  (filter (core/moved-any [:last-failure]) (live/summary))
  (filter (core/moved-any [:last-write :active-write]) (live/summary))

  ;; Two conditions is `every-pred`. A set is a predicate, so selecting on the event key needs no
  ;; verb of its own.
  (filter (every-pred (core/moved-any [:last-failure]) (comp #{"tasks"} :resource/id))
          (live/summary))
  (filter (comp #{:submit-write :write-ack :write-failed} :probe/event-key) (live/summary))

  ;; ═══ 4. Read the round trip ═════════════════════════════════════════════════════════
  ;; One value per event and what it caused: what you asked, what it sent, what came back, and the
  ;; view consumers were handed. A request and its answer are one trip, so nothing here needs a
  ;; position typed or two rows correlated by hand.
  ;;
  ;; Take one. A trip carries whole envelopes, so asking for all of them at once is a wall of text.
  ;; Step 1 is how you choose which one.
  (last (live/round-trips))
  (nth (live/round-trips) 2)

  ;; The most recent one, and what the page shows for it now. The four recorded legs are the past
  ;; and :probe/rendered is the present, which agree only because this is the latest trip.
  (live/latest)

  ;; Still too much on screen? Read one leg. Every trip carries all of them, nil where it has
  ;; none, so nothing here needs a `contains?`.
  (:probe/asked (live/latest))
  (:probe/answered (live/latest))
  (:probe/rendered (live/latest))

  ;; The page on its own, for any resource rather than only the latest trip.
  (live/rendered "tasks")

  ;; A position navigates to the record it names, so a data browser opens it without typing.
  (tap> (live/round-trips))

  ;; A trip has five legs, one per stage between your gesture and the pixels. Read them in this
  ;; order and stop at the first that is wrong, because everything after it is downstream of the
  ;; fault and will look wrong too.
  ;;
  ;;   :probe/asked     what the app decided you asked for, so the gesture was translated wrongly
  ;;   :probe/sent      what went to the server, so the request was built wrongly
  ;;   :probe/answered  what came back, so the server is at fault
  ;;   :probe/handed    what consumers were given, so the projection is at fault
  ;;   :probe/rendered  what is on screen now, so a consumer is at fault
  ;;
  ;; A trip carrying no :probe/sent opened no request, which is a gesture that was lost, declined
  ;; or unroutable. A value on screen that appears under a different key of :probe/answered is a
  ;; consumer reading the wrong part of a value that is right.
  ;;
  ;; These legs judge one trip. A fault in how trips relate to each other, such as paging, ordering
  ;; or how often a request fires, leaves every trip looking plausible on its own. Step 10 is where
  ;; those show, and step 1 is where an odd cadence shows.

  ;; Narrowing, when the log is long.
  (filter (comp #{"tasks"} :resource/id) (live/round-trips))
  (filter (comp seq :probe/diagnostics) (live/round-trips))

  ;; ═══ 5. One record, verb by verb ════════════════════════════════════════════════════
  ;; Reach for these when a trip is not enough: the whole record, what moved, the state there.
  (live/at 14)
  (live/diff 15)
  (:probe/diagnostics (live/state 15))
  (live/view 15)

  ;; `diff` names the key that moved. It cannot say what changed inside a large value, so reach for
  ;; a path when the moved key holds an envelope.
  (get-in (live/resource "tasks" 15) [:last-accepted :value])

  ;; One number, two readings: `at` and a one-position `diff` name a record, while `state`,
  ;; `resource` and a two-position `diff` count applied events.
  (live/diff 12 18)

  ;; ═══ 9. What does the page show ═════════════════════════════════════════════════════
  ;; Cross into the DOM only once the log has answered all it can. Click the thing that looks
  ;; wrong, then read it. A consumer rendering the wrong key of a correct value leaves the wrong
  ;; answer in that value, so the bug is which key the element matches.
  ;; Picking is BareDOM's, required directly. It answers an id, and `live` turns an id into maps.
  (pick/pick!)
  (pick/picked)                     ; nil until you click
  (pick/cancel!)                    ; changed your mind, leave select mode

  ;; What the element was given, versus what it holds now. For a control these differ: an attribute
  ;; is the initial value, so text the user typed appears only under props.
  (live/attrs (pick/picked))        ; a search box with "He" typed shows no value at all
  (live/props (pick/picked))        ; => {"value" "He" … :probe/skipped ["validity" "labels"]}

  ;; `props` answers for a component. A plain element declares nothing of its own, so it is refused
  ;; and `attrs` is what reads it.

  ;; A withheld name resolves on demand. tap> the map and click one, or ask for it here.
  (require '[clojure.datafy :refer [nav]])
  (nav (:probe/skipped (live/props (pick/picked))) 0 "validity")

  ;; And back the other way, when you have an id and want to know where it is on the page.
  (pick/show! "x-form-field#7")
  (pick/hide!)

  ;; ═══ 10. Is it the only one ═════════════════════════════════════════════════════════
  ;; The general form of a bug is often "something mishandles part of the shape". This says what
  ;; the traffic showed of each shape, so a field nothing ever filled or an enum value never
  ;; exercised is visible.
  (live/coverage)
  ;; => {"tasks" {:probe/distinct-rows 37
  ;;              :probe/never-filled  #{}
  ;;              :probe/enum-unseen   {"status" #{"done"}}}}
  ;;
  ;; Read :probe/distinct-rows first. If the traffic showed ten rows of thirty-seven, every other
  ;; key means little. What it cannot say is which consumer read a field, which the log never sees.
  ;;
  ;; Once you have paged through everything, :probe/distinct-rows should equal the :total-count the
  ;; server claims. Short means the traffic never delivered what it said it had. The page-info is a
  ;; claim and the rows are the evidence, and no single trip can tell them apart, because each page
  ;; is consistent on its own.
  (map (fn [t] (get-in t [:probe/answered 1 :page-info])) (live/round-trips))

  ;; ═══ 11. Would the fix have worked ══════════════════════════════════════════════════
  ;; `step` is a pure function of a resource and an event, so a counterfactual is an evaluation.
  ;; Use it once you have a hypothesis, not before. Pick a position where the request is still in
  ;; flight: a :submit-write row, read at its own position, before its ack.
  (def r (live/resource "tasks" 13))

  (def rejection (core/answer r :write-ack
                              {:outcome :rejected
                               :error   {:code    :invalid-field
                                         :message "end must not precede start"
                                         :details {"field" "end"}}}))

  (core/read-step r rejection)
  ;; => {:probe/moved? true :probe/diagnostics []}

  (tap> (core/step r rejection))
  (get-in (:resource (core/step r rejection))
          [:last-failure :response :error :details "field"])

  ;; The same event without the correlation. `answer` fills in the :request/id the resource has in
  ;; flight. An event built without it is dropped, reports :stale-answer, and changes nothing.
  (core/read-step r [:write-ack {:outcome :rejected}])
  ;; => {:probe/moved? false
  ;;     :probe/diagnostics [{:probe/code   :stale-answer
  ;;                          :probe/detail {:for :write :event :write-ack}}]}

  ;; Mapping over candidates. One event carries both write failures: `:error` is the network one,
  ;; `:protocol-failure` the answer that could not be read.
  (map #(:probe/moved? (core/read-step r %))
       [(core/answer r :write-ack {:outcome :accepted})
        (core/answer r :write-failed {:error {:kind :offline}})
        (core/answer r :write-failed {:protocol-failure {:reason :malformed-envelope}})])

  ;; ═══ 12. Keep it ════════════════════════════════════════════════════════════════════
  ;; The `core` forms lift into a `deftest` unchanged. Hold the entries in a name, and every `core`
  ;; verb reads them without a running app. The log does not survive the session, so lift what you
  ;; want to keep before you close it.
  (def saved (live/log))
  (core/round-trips saved)

  ;; ═══ Reference: paths worth knowing ═════════════════════════════════════════════════
  ;; Every one of these came up while investigating a real defect.
  (:last-failure (live/resource "tasks"))            ; :cause and :for are the discriminators
  (get-in (live/resource "tasks") [:last-failure :response :error :details "field"])
  (get-in (live/resource "tasks") [:last-failure :query])       ; the query the request was for
  (get-in (live/resource "tasks") [:last-write :payload :record])     ; what was actually sent
  (get-in (live/resource "tasks") [:last-write :payload :submitter])  ; which consumer wrote
  (get-in (live/resource "tasks") [:last-write :status])  ; :in-flight :accepted :rejected :failed
  (get-in (live/resource "tasks") [:last-accepted :page-info])
  (get-in (live/resource "tasks") [:last-accepted :shape :fields])

  ;; :url-intent is the one path here that does not survive to the end of the log. An accepted
  ;; response adopts the server's query echo, so a parameter the server ignored is gone by the next
  ;; row. Read it at the position of the patch.
  (:url-intent (live/resource "tasks" 14))           ; what the resource believed it asked for

  ;; The general form of that. On a :response row, :url-intent moving means the server did not
  ;; honour the query as asked. A healthy response moves [:active-request :last-accepted] alone.
  (filter (every-pred (core/moved-any [:url-intent])
                      (comp #{:response} :probe/event-key))
          (live/summary))

  ;; ═══ Reference: be the server ═══════════════════════════════════════════════════════
  ;; Rather than waiting for a failure, author one. The envelope is the wire form, string keys,
  ;; exactly as the server would have sent it. The request id is filled in, so the answer
  ;; correlates and `stale-answer` does not drop it.
  ;;
  ;; Pin the kind. A read and a write carry their request id the same way, so an unpinned envelope
  ;; is taken by whichever arrives first, and a background read would swallow this one.
  (live/serve! "tasks"
               {"outcome" "rejected"
                "revision" "r-41"
                "error"   {"code"    "invalid-range"
                           "message" "End date must be on or after the start date."
                           "details" {"field" "end"}}}
               {:kind :write})
  ;; …now save a task. The rejection arrives every time, with the payload you wrote.
  (live/serving)                           ; what is still queued
  (filter live/injected? (live/summary))   ; which rows BareProbe answered
  (live/clear-serving!)                    ; drop the queues and put the original fetch back

  ;; ═══ Reference: making a failure ════════════════════════════════════════════════════
  ;; :last-failure has four causes. BareBuild's demo/dev-server/server.clj has a lever for each.
  ;;
  ;; :rejected / :for :write
  ;;   In the task form, set the end date earlier than the start and save. The server answers
  ;;   HTTP 200 with {:code "invalid-range" :details {"field" "end"}}. Clearing a required field
  ;;   (title, owner, start) gives "missing-required".
  ;;
  ;; The rest are URL levers, on `<resource-id>.`-prefixed keys. They persist in the intent until
  ;; removed, so every later read carries them too.
  ;;
  ;; :rejected / :for :read   ?tasks.sort=banana
  ;;                          sortable is id, title, owner, start, end, est, status
  ;; :contract                ?tasks.fixture=contract
  ;; :protocol                ?tasks.fixture=bad-outcome
  ;;                          ?tasks.fixture=missing-shape
  ;;                          ?tasks.fixture=unparseable
  ;; :network                 ?tasks.fixture=500
  ;;                          or (live/fault! {:fail-every 1}), which sends nothing
  ;;
  ;; ?tasks.fixture=slow is a 3s delay rather than a failure. The fixture is checked before the
  ;; unsupported-sort check, so do not combine the two.

  ;; ═══ Reference: the request edge ════════════════════════════════════════════════════
  ;; Under latency, an :intent-patch row that moves :url-intent but not :request-count is a read
  ;; that `open-read` declined. Arming restarts the request count, so a fail-every cycle counts
  ;; from the call that named it.
  (live/fault! {:latency 800})
  (live/status)
  (live/fault! {:latency 800 :fail-every 3})
  (live/fault! nil)
  )
