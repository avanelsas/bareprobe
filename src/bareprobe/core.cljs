(ns bareprobe.core
  "Pure functions over a recorded BareBuild log. Every verb takes its entries as an argument. No
  atom, no DOM, no effect performed.

  `.cljs` rather than `.cljc`: it requires `barebuild.resource`, which BareBuild ships as `.cljs`.
  That is its one upstream."
  (:require [barebuild.resource :as barebuild]
            [clojure.core.protocols :as p]
            [clojure.set :as set]))

;; Diagnostics -------------------------------------------------------------------------
;; BareBuild's effect: [:diagnostic {:code c}] or [:diagnostic {:code c :detail d}].
;; A refusal carries the same shape in its ex-data, so a code reads the same whether a step
;; reported it or a verb threw it.

(defn refuse
  "Throws `message`, carrying `code` and `detail` in the one diagnostic shape. Public because
  `live` refuses in the same shape, and one constructor is what keeps them one shape."
  [message code detail]
  (throw (ex-info message {:probe/code code :probe/detail detail})))

(defn- diagnostics
  "The :diagnostic effects in `effects`, as {:probe/code … :probe/detail …}."
  [effects]
  (into []
        (comp (filter (fn [[tag]] (= :diagnostic tag)))
              (map (fn [[_ {:keys [code detail]}]]
                     (cond-> {:probe/code code}
                       (some? detail) (assoc :probe/detail detail)))))
        effects))

;; A single step ------------------------------------------------------------------------

(def ^{:arglists '([resource event])} step
  "`barebuild.resource/step`, re-exported verbatim."
  barebuild/step)

(defn read-step
  "Whether `event` moves `resource`, and the diagnostics the step reports. A step that moves
  nothing still returns a resource that reads as though the event was handled."
  [resource event]
  (let [{:keys [effects] :as result} (barebuild/step resource event)]
    {:probe/moved?      (not= resource (:resource result))
     :probe/diagnostics (diagnostics effects)}))

;; Positions, and the record at one ------------------------------------------------------
;; Two kinds of position, sharing one integer. A fold position counts applied events and runs 0
;; through the count. A record position names one record and runs 1 through the count, so the
;; record at position n is (nth entries (dec n)).

(defn- check-position
  [entries lo n]
  (let [hi (count entries)]
    (when-not (and (int? n) (<= lo n hi))
      (refuse (str "no such position " (pr-str n) ", the log holds " hi
                   (if (< hi lo)
                     " and answers none"
                     (str " and answers " lo " through " hi)))
              :no-such-position
              {:position n :lowest lo :highest hi}))))

(defn- check-fold-position
  "Throws unless `n` is a fold position, meaning 0 through the number of entries. Position 0 is
  before the first event."
  [entries n]
  (check-position entries 0 n))

(defn- check-record-position
  "Throws unless `n` is a record position, meaning 1 through the number of entries."
  [entries n]
  (check-position entries 1 n))

(defn at
  "The record at position `n`, payload and all."
  [entries n]
  (check-record-position entries n)
  (nth entries (dec n)))

;; The view a record handed over ----------------------------------------------------------

(defn- view-of
  "The view `record` handed to consumers, or nil when it notified none."
  [record]
  (some (fn [[tag m]] (when (= :notify-consumers tag) (:view m))) (:effects record)))

(defn view
  "The projection the record at position `n` handed to consumers, or nil when it notified none.
  A step appends that effect only when the view moved, so nil means nothing was handed over rather
  than an empty view."
  [entries n]
  (view-of (at entries n)))

;; Keys that moved ----------------------------------------------------------------------

(defn- moved-keys
  "The keys whose value differs between `before` and `after`, in a stable order."
  [before after]
  (into []
        (remove #(= (get before %) (get after %)))
        (sort-by str (distinct (concat (keys before) (keys after))))))

;; Navigation ---------------------------------------------------------------------------
;; Every reference BareProbe hands back is a position or a key.

(defn- position-nav
  "Nav resolving :probe/position to the record it names. A position naming no record is its own
  value, so position 0 and a truncated log do not throw."
  [entries]
  (fn [_m k v]
    (if (and (= k :probe/position) (int? v) (<= 1 v (count entries)))
      (at entries v)
      v)))

(defn- with-nav
  "`m` carrying `navigate` as its nav."
  [navigate m]
  (with-meta m {`p/nav navigate}))

(defn- navigable-keys
  "The keys that moved between `before` and `after`, each navigating to its own pair."
  [before after]
  (with-nav (fn [_ks _i k] [(get before k) (get after k)])
    (moved-keys before after)))

;; The recorded state -------------------------------------------------------------------

(defn- seeds
  "Each resource id mapped to its state before its first recorded event. Read from the whole log
  rather than from a prefix."
  [entries]
  (reduce (fn [acc {rid :resource/id :keys [before]}]
            (if (contains? acc rid) acc (assoc acc rid before)))
          {}
          entries))

(defn- diagnostics-through
  "Every diagnostic reported up to position `n`, each carrying and navigating to the position that
  reported it."
  [navigate entries n]
  (into []
        (mapcat (fn [[i {:keys [effects]}]]
                  (map #(with-nav navigate (assoc % :probe/position (inc i)))
                       (diagnostics effects))))
        (map-indexed vector (take n entries))))

(defn- resources-at
  "Each resource id mapped to what it held at position `n`."
  [entries n]
  (reduce (fn [acc {rid :resource/id :keys [after]}] (assoc acc rid after))
          (seeds entries)
          (take n entries)))

(defn state
  "The resources as recorded at position `n`, and every diagnostic reported up to it. `n` counts
  applied events, and defaults to the whole log. Reads what each step left behind rather than
  replaying to it, so it reports what happened rather than what would happen now."
  ([entries] (state entries (count entries)))
  ([entries n]
   (check-fold-position entries n)
   (let [navigate (position-nav entries)]
     (with-nav navigate
       {:probe/position    n
        :probe/resources   (resources-at entries n)
        :probe/diagnostics (diagnostics-through navigate entries n)}))))

(defn resource
  "What `rid` held at position `n`, defaulting to the end of the log. The way in when you want one
  resource rather than the whole of `state`."
  ([entries rid] (resource entries rid (count entries)))
  ([entries rid n]
   (check-fold-position entries n)
   (get (resources-at entries n) rid)))

;; Replaying ----------------------------------------------------------------------------

(defn- disagreement
  "How stepping `record`'s event over its own :before differs from what the record holds, or nil
  when it does not."
  [navigate position {rid :resource/id :keys [event before after effects]}]
  (let [{stepped :resource replayed :effects} (barebuild/step before event)]
    (when (or (not= stepped after) (not= replayed effects))
      (with-nav navigate
        (cond-> {:probe/position position :resource/id rid}
          (not= stepped after)    (assoc :probe/disagreed-keys (navigable-keys after stepped))
          (not= replayed effects) (assoc :probe/disagreed-effects [effects replayed]))))))

(defn disagreements
  "The positions where a recorded event no longer reproduces its own record. Each names the
  resource keys that disagree, and the effects as [recorded replayed]. Empty when the log still
  means what it meant when it was recorded, under the BareBuild on the classpath."
  [entries]
  (let [navigate (position-nav entries)]
    (into []
          (keep-indexed (fn [i record] (disagreement navigate (inc i) record)))
          entries)))

;; Diffs --------------------------------------------------------------------------------

(defn- changes
  "{key [before after]} over the keys that moved."
  [before after]
  (into {}
        (map (fn [k] [k [(get before k) (get after k)]]))
        (moved-keys before after)))

(defn diff
  "What moved, as {resource-id {key [before after]}}. A resource that did not move is absent. Given
  one position, what moved in the record there. Given two, what moved between the states at each."
  ([entries n]
   (let [{:keys [before after] rid :resource/id} (at entries n)
         moved                                   (changes before after)]
     (if (seq moved) {rid moved} {})))
  ([entries a b]
   (check-fold-position entries a)
   (check-fold-position entries b)
   (let [ra (resources-at entries a)
         rb (resources-at entries b)]
     (into {}
           (keep (fn [rid]
                   (let [moved (changes (get ra rid) (get rb rid))]
                     (when (seq moved) [rid moved]))))
           (distinct (concat (keys ra) (keys rb)))))))

;; The log as rows ------------------------------------------------------------------------
;; A write label reads :op and :id off the write payload, which the app owns and BareBuild carries
;; untouched. An app whose writes carry neither gets "write " and nothing after it.

(defn- named
  "`v` as a label fragment, whatever it is."
  [v]
  (if (keyword? v) (name v) (str v)))

(defn label
  "A short readable phrase for `event`."
  [[k payload]]
  (case k
    :connected         "connected"
    :disconnected      "disconnected"
    :refresh           "refresh"
    :intent-patch      (str "intent " (:query-patch payload))
    :intent-unroutable (str "intent unroutable " (:resource/id payload))
    :url-changed       (str "url " payload)
    :response          (str "response " (named (:outcome payload)))
    :protocol-failed   "protocol failed"
    :network-failed    "network failed"
    :submit-write      (str "write " (named (:op payload))
                            (when (:id payload) (str " #" (:id payload))))
    :write-ack         (str "write-ack " (named (:outcome payload)))
    :write-failed      "write failed"
    (str k)))

(defn summary
  "One row per record: position, resource id, request id, event key, label, the keys that moved,
  and the diagnostics. The request id is nil for an event that names no request. `round-trips`
  pairs a request with its answer. The event value itself is reachable through `at`, and through
  `nav`."
  [entries]
  (let [navigate (position-nav entries)]
    (into []
          (map-indexed
           (fn [i {rid :resource/id :keys [event before after effects]}]
             (let [moved (navigable-keys before after)]
               (with-nav navigate
                 {:probe/position    (inc i)
                  :resource/id       rid
                  :request/id        (:request/id (second event))
                  :probe/event-key   (first event)
                  :probe/label       (label event)
                  :probe/moved?      (boolean (seq moved))
                  :probe/moved-keys  moved
                  :probe/diagnostics (diagnostics effects)}))))
          entries)))

;; Round trips ------------------------------------------------------------------------------
;; One value per event and what it caused. A request and its answer are two records sharing a
;; :request/id, which is the only thing pairing them, so the trip is derived rather than recorded.

(def ^:private request-tags
  "The effects that open a request."
  #{:fetch :write})

(defn- request-effect
  "The :fetch or :write effect `record` emitted, or nil."
  [record]
  (some (fn [[tag m]] (when (request-tags tag) m)) (:effects record)))

(defn- answered-id
  "The :request/id `record`'s event answers, or nil."
  [record]
  (:request/id (second (:event record))))

(defn- replies
  "Each request id the log opened, mapped to the [position record] answering it. A record answering
  a request the log does not hold is not one of these, so it stays a trip of its own."
  [numbered]
  (let [opened (into #{} (keep (fn [[_ r]] (:request/id (request-effect r)))) numbered)]
    (into {}
          (keep (fn [[n r]]
                  (when-let [id (answered-id r)]
                    (when (opened id) [id [n r]]))))
          numbered)))

(defn- trip
  "The trip opening at position `n`, with `answer` as [position record] when one came back."
  [navigate n record answer]
  (let [[an arecord] answer
        records      (cond-> [record] arecord (conj arecord))
        sent         (request-effect record)]
    {:resource/id       (:resource/id record)
     :probe/positions   (with-nav navigate (cond-> [n] an (conj an)))
     :request/id        (:request/id sent)
     :probe/asked       (:event record)
     :probe/sent        sent
     :probe/answered    (:event arecord)
     :probe/handed      (some view-of (rseq records))
     :probe/diagnostics (into [] (mapcat #(diagnostics (:effects %))) records)}))

(defn round-trips
  "One value per event and what it caused: what was asked, what was sent, what came back, and the
  view consumers were handed, each nil where the trip has none. Every record appears in exactly one
  trip, and a position navigates to the record it names."
  [entries]
  (let [numbered (map-indexed (fn [i r] [(inc i) r]) entries)
        answered (replies numbered)
        replied  (into #{} (map (fn [[_ [n _]]] n)) answered)
        navigate (fn [_positions _i n] (at entries n))]
    (into []
          (comp (remove (fn [[n _]] (contains? replied n)))
                (map (fn [[n r]]
                       (trip navigate n r (get answered (:request/id (request-effect r)))))))
          numbered)))

;; Coverage -----------------------------------------------------------------------------------
;; There is no "declared but never present" here. BareBuild's contract check refuses an envelope
;; whose rows omit a declared field, so such a payload never becomes an accepted one.

(defn- installed-envelopes
  "The envelopes each resource installed, as {resource-id [envelope …]}. Read from :last-accepted
  moving, which is the runtime's verdict rather than the server's claim."
  [entries]
  (reduce (fn [acc {rid :resource/id :keys [before after]}]
            (let [installed (:last-accepted after)]
              (if (and installed (not= installed (:last-accepted before)))
                (update acc rid (fnil conj []) installed)
                acc)))
          {}
          entries))

(defn- blank?
  "True when `v` carries no value. An empty form field arrives as a blank string, and a zero is a
  value."
  [v]
  (or (nil? v) (= "" v)))

(defn- distinct-ids
  "The distinct row ids across `envelopes`, each read with its own envelope's id-key."
  [envelopes]
  (into #{}
        (mapcat (fn [{:keys [value] {:keys [id-key]} :shape}]
                  (keep #(get % id-key) value)))
        envelopes))

(defn- filled-keys
  "The keys carrying a value in any of `rows`."
  [rows]
  (into #{}
        (mapcat (fn [row] (keep (fn [[k v]] (when-not (blank? v) k)) row)))
        rows))

(defn- enum-fields
  "Each declared field that names an enum, mapped to the values it allows."
  [fields]
  (into {}
        (keep (fn [{:keys [key enum]}] (when (seq enum) [key (set enum)])))
        fields))

(defn- enum-values-seen
  "Each key of `enums` mapped to the values `rows` carried for it."
  [enums rows]
  (reduce (fn [acc row]
            (reduce-kv (fn [acc k v]
                         (if (and (contains? enums k) (not (blank? v)))
                           (update acc k (fnil conj #{}) v)
                           acc))
                       acc
                       row))
          {}
          rows))

(defn- unseen-enum-values
  "Each key of `enums` mapped to the allowed values `rows` never carried, the exhausted ones
  absent."
  [enums rows]
  (let [seen (enum-values-seen enums rows)]
    (into {}
          (keep (fn [[k allowed]]
                  (let [missing (set/difference allowed (get seen k #{}))]
                    (when (seq missing) [k missing]))))
          enums)))

(defn- shape-coverage
  "What `envelopes` showed of the shape they declare."
  [envelopes]
  (let [fields   (into [] (mapcat #(get-in % [:shape :fields])) envelopes)
        id-keys  (into #{} (keep #(get-in % [:shape :id-key])) envelopes)
        declared (into id-keys (map :key) fields)
        rows     (into [] (mapcat :value) envelopes)]
    {:probe/distinct-rows (count (distinct-ids envelopes))
     :probe/never-filled  (set/difference declared (filled-keys rows))
     :probe/enum-unseen   (unseen-enum-values (enum-fields fields) rows)}))

(defn coverage
  "What the recorded traffic showed of each resource's declared shape, as {resource-id {…}}. A
  resource with no installed envelope is absent. :probe/distinct-rows counts rows by the shape's
  id-key, so paging over the same row twice counts once. It is the denominator that says whether
  the rest means anything."
  [entries]
  (into {}
        (map (fn [[rid envelopes]] [rid (shape-coverage envelopes)]))
        (installed-envelopes entries)))

;; Building an answering event --------------------------------------------------------------

(def ^:private answered-kind
  {:response        :read
   :protocol-failed :read
   :network-failed  :read
   :write-ack       :write
   :write-failed    :write})

(def ^:private in-flight-slot
  {:read :active-request :write :active-write})

(defn answer
  "An answering event of kind `event-k`, carrying `payload` and the :request/id that `resource` has
  in flight. Throws when `event-k` answers nothing, or when nothing of that kind is in flight. An
  answering event built without that id is dropped by `step`, which reports :stale-answer and
  changes nothing."
  [resource event-k payload]
  (if-let [kind (answered-kind event-k)]
    (let [slot (in-flight-slot kind)]
      (if-let [req (get resource slot)]
        [event-k (assoc payload :request/id (:request/id req))]
        (refuse (str "nothing of that kind is in flight, answer at a position where "
                     slot " is set")
                :nothing-in-flight
                {:event event-k :kind kind :slot slot})))
    (refuse (str "not an answering event, the answering events are "
                 (pr-str (vec (sort (keys answered-kind)))))
            :not-an-answering-event
            {:event event-k :answering (set (keys answered-kind))})))

;; Selecting summary rows ----------------------------------------------------------------------

(defn moved-any
  "A predicate on a summary row: did it move any of `ks`? Takes the keys, answers the predicate,
  so the keys go here and the result goes to `filter`."
  [ks]
  (when-not (or (sequential? ks) (set? ks))
    (refuse "name the keys in a set or a vector" :not-keys {:given ks}))
  (let [ks (set ks)]
    (fn [row] (boolean (some ks (:probe/moved-keys row))))))

(defn diagnosed?
  "True when a summary row carries diagnostics. A row that reported nothing carries `[]`, which is
  truthy, so a bare keyword lookup matches every row."
  [row]
  (boolean (seq (:probe/diagnostics row))))
