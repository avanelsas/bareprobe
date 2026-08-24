(ns bareprobe.live
  "Where the effects are: the ambient log, the live page, the request edge, and the one verb that
  prints. The namespace alias marks the boundary, not the `!` suffix. A verb here can read the
  page without writing it, and `core` performs no effect at all."
  (:require-macros [bareprobe.live :refer [defsugar]])
  (:require [baredom.dev.pick :as pick]
            [barebuild.decorator :as decorator]
            [bareprobe.core :as core]
            [barereplay.store :as store]
            [cljs.pprint :as pp]
            [clojure.core.protocols :as p]
            [clojure.string :as str]
            [goog.object :as gobj]))

;; The two places -----------------------------------------------------------------------
;; `fault-edge` is the request decorator: the standing instructions, the decorator installed before
;; ours, and what the decorator has seen. :armed? is a flag rather than the installed function,
;; because `faulting` is redefined on every hot reload while this atom is not. Comparing against
;; the function would rearm on top of itself. :seen counts what reached the decorator since the
;; faults were last set, so a request a fault drops is counted and not sent. A `fail-every` cycle
;; starts at the call that named it.
;;
;; `server-edge` is the fetch wrapper: the queued envelopes, the fetch installed before ours, and
;; the request ids BareProbe answered.

(defonce ^:private fault-edge
  (atom {:faults nil :previous nil :armed? false :seen 0}))

(defonce ^:private server-edge
  (atom {:queues nil :real-fetch nil :served #{}}))

;; The recorded log ---------------------------------------------------------------------
;; barereplay's store as it is today: unbounded, holding a live :el per entry.

(defn log
  "The recorded entries."
  []
  (store/entries))

(defn clear!
  "Drop everything recorded so far, and forget which answers BareProbe gave. Positions after the
  clear stay exact, positions before it are gone, and a resource with no traffic after it drops
  out of `state`. The log is BareReplay's, so its dock loses the same records."
  []
  (store/clear!)
  (swap! server-edge assoc :served #{})
  nil)

;; Sugar over core, supplying the ambient log -------------------------------------------
;; Every core verb whose first argument is the log, and no others. The rest take a resource or a
;; summary row, so there is no log to supply.

(defsugar state [] [n])
(defsugar disagreements [])
(defsugar summary [])
(defsugar resource [rid] [rid n])
(defsugar at [n])
(defsugar view [n])
(defsugar round-trips [])
(defsugar diff [n] [a b])
(defsugar coverage [])

;; Reading it ---------------------------------------------------------------------------
;; The one place here that prints. The no-argument arity prints one line per round trip, each
;; column a scalar.

(def ^:private trip-columns
  [:probe/positions :resource/id :probe/label :probe/sent? :probe/handed? :probe/reported])

(defn- arc
  "The phrase a trip travelled, from what was asked to what came back."
  [{:keys [probe/asked probe/answered]}]
  (cond-> (core/label asked)
    answered (str " -> " (core/label answered))))

(defn- trip-row
  "One trip as a line."
  [trip]
  {:probe/positions (:probe/positions trip)
   :resource/id     (:resource/id trip)
   :probe/label     (arc trip)
   :probe/sent?     (some? (:probe/sent trip))
   :probe/handed?   (some? (:probe/handed trip))
   :probe/reported  (mapv :probe/code (:probe/diagnostics trip))})

(defn table!
  "Print `rows` as an aligned table, one row per line, and return nil. With no arguments, the
  current log as one line per round trip. `ks` selects and orders the columns."
  ([]        (table! trip-columns (map trip-row (round-trips))))
  ([rows]    (pp/print-table rows))
  ([ks rows] (pp/print-table ks rows)))

;; The DOM ------------------------------------------------------------------------------
;; Ids in, maps out. Nothing here returns an element, and an id re-resolves on every call.
;;
;; The id comes from `baredom.dev.pick`, which a session requires directly. `pick!`, `picked`,
;; `cancel!`, `show!` and `hide!` are its verbs, and appear nowhere here.

(defn- attrs-of
  "A map of name to value for `el`'s attributes."
  [^js el]
  (into {} (map (fn [^js a] [(.-name a) (.-value a)])) (array-seq (.-attributes el))))

(defn attrs
  "A map of name to value for the attributes of the element `id` names, or nil when the id
  resolves to nothing. An attribute holds what the element was given, not what a control holds
  now, which `props` reads."
  [id]
  (when-let [^js el (pick/element-for id)]
    (attrs-of el)))

(def ^:private platform-proto
  "Where the walk up the prototype chain stops. Under Node there is no HTMLElement, so it is
  Object's."
  (if (exists? js/HTMLElement) (.-prototype js/HTMLElement) (.-prototype js/Object)))

(defn- component?
  "True when `el` is a custom element, whose tag name carries a hyphen."
  [^js el]
  (let [tag (.-tagName el)]
    (and (string? tag) (str/includes? tag "-"))))

(defn- own-getter-names
  "The names `proto` itself declares with a getter."
  [^js proto]
  (into []
        (filter (fn [k] (some-> ^js (js/Object.getOwnPropertyDescriptor proto k) (.-get))))
        (array-seq (js/Object.getOwnPropertyNames proto))))

(defn- getter-names
  "The names of the properties the class of `el` declares with a getter, and those any class it
  extends declares."
  [^js el]
  (into []
        (comp (take-while #(and % (not (identical? % platform-proto))))
              (mapcat own-getter-names)
              (distinct))
        (iterate #(js/Object.getPrototypeOf %) (js/Object.getPrototypeOf el))))

(defn- scalar?
  "True when `v` is a string, a number, a boolean or nil."
  [v]
  (or (nil? v) (string? v) (number? v) (boolean? v)))

(defn- report
  "Property entries as a map of name to value, with the names of the non-scalar ones under
  `:probe/skipped`."
  [entries]
  (let [{values true skipped false} (group-by (comp scalar? second) entries)]
    (cond-> (into {} values)
      (seq skipped) (assoc :probe/skipped (mapv first skipped)))))

(defn- navigable-skipped
  "The skipped `names`, each navigating to what that property holds on the element `id` names."
  [id names]
  (with-meta names
    {`p/nav (fn [_names _i prop] (some-> ^js (pick/element-for id) (gobj/get prop)))}))

;; :probe/skipped is a keyword among string keys, and compare refuses that pair, so a sorted-map
;; throws on any element with a skipped property.
(defn props
  "A map of name to value for the properties the element `id` names declares, or nil when the id
  resolves to nothing. Names whose property holds anything but a string, number, boolean or nil go
  under `:probe/skipped`, and each navigates to what it holds now.

  Reads what a control holds now, including text the user typed, which never reaches an attribute.
  It calls every getter it reads, so a getter that does work does it here. It reads the element's
  own classes and not the platform's, so `innerHTML` and the rest of the native surface are absent.
  An id naming anything but a component is refused, and `attrs` is what reads one of those."
  [id]
  (when-let [^js el (pick/element-for id)]
    (when-not (component? el)
      (core/refuse "not a component, its properties are all the platform's own, read it with attrs"
                   :not-a-component
                   {:id id :tag (.-tagName el)}))
    (let [read-prop (fn [k] [k (gobj/get el k)])
          m         (report (map read-prop (getter-names el)))]
      (cond-> m
        (contains? m :probe/skipped) (update :probe/skipped #(navigable-skipped id %))))))

;; The request edge ---------------------------------------------------------------------

(defn- sleep
  "A promise that resolves after `ms`."
  [ms]
  (js/Promise. (fn [resolve _] (js/setTimeout resolve ms))))

(defn- faulting
  "The decorator `fault!` installs. Applies the current faults, then defers to the decorator
  installed before it."
  [m]
  (let [{:keys [faults previous]}    @fault-edge
        {:keys [latency fail-every]} faults
        n                            (:seen (swap! fault-edge update :seen inc))]
    (-> (if latency (sleep latency) (js/Promise.resolve))
      (.then (fn []
               ;; A throw reaches `transport/request-init`, which turns it into
               ;; {:network-failure {:kind :decorator}} and sends nothing.
               (when (and fail-every (zero? (mod n fail-every)))
                 (core/refuse "injected fault"
                              :injected-fault
                              {:fault :fail-every :requests-since-armed n}))
               (when previous (previous m)))))))

(defn fault!
  "Arm the request edge, or clear it with nil. Returns what is now armed, and restarts the request
  count, so a `fail-every` cycle counts from this call rather than from an earlier one.

    (fault! {:latency 800})                every request waits 800ms before it is sent
    (fault! {:latency 800 :fail-every 3})  and every third one is never sent
    (fault! nil)                           restore the decorator installed before

  Does not produce a :stale-answer. The runtime never leaves an answer out of step with the
  request it names."
  [faults]
  (if (nil? faults)
    (do (when (:armed? @fault-edge) (decorator/install! (:previous @fault-edge)))
        (swap! fault-edge assoc :faults nil :previous nil :armed? false :seen 0)
        nil)
    (do (when-not (:armed? @fault-edge)
          (swap! fault-edge assoc :previous (decorator/current) :armed? true)
          (decorator/install! faulting))
        (:faults (swap! fault-edge assoc :faults faults :seen 0)))))

;; Being the server ---------------------------------------------------------------------
;; A queued envelope answers the next request for its resource. The decorator seam cannot do this:
;; it returns headers, and the fetch happens after it. So the fetch itself is wrapped, and the
;; original is kept in the atom rather than in a var, because a var does not survive a reload.

(defn- request-id-of
  "The requestId `url` carries, or nil. The base is a placeholder, ignored for an absolute url."
  [url]
  (when (string? url)
    (.get (.-searchParams (js/URL. url "http://bareprobe.invalid")) "requestId")))

(def ^:private kinds
  "What a queued envelope may answer."
  #{:any :read :write})

(defn- request-kind
  "The kind of request the id `suffix` names. A write is numbered with a leading `w`."
  [suffix]
  (if (str/starts-with? suffix "w") :write :read))

(defn- serving-resource
  "The resource id in `serving` whose queued envelope answers `request-id`, or nil. A request id is
  its resource id, a colon, then the kind's prefix and number, and the resource id may carry colons
  of its own."
  [serving request-id]
  (when-let [i (some-> request-id (str/last-index-of ":"))]
    (let [rid             (subs request-id 0 i)
          suffix          (subs request-id (inc i))
          {k :probe/kind} (first (get serving rid))]
      (when (and k (or (= :any k) (= k (request-kind suffix))))
        rid))))

(defn- canned-response
  "A 200 response carrying `envelope` as JSON. `request-id` is stamped unless the envelope names
  one, so an answer correlates by default and a stale one stays possible."
  [envelope request-id]
  (js/Response. (js/JSON.stringify (clj->js (merge {"requestId" request-id} envelope)))
                #js {:status 200 :headers #js {"content-type" "application/json"}}))

(defn- drop-entry
  "`s` with the front of `rid`'s queue removed, and `request-id` recorded as answered."
  [s rid request-id]
  (-> (if (next (get-in s [:queues rid]))
        (update-in s [:queues rid] subvec 1)
        (update s :queues dissoc rid))
      (update :served (fnil conj #{}) request-id)))

(defn- uninstall-when-idle!
  "Put the original fetch back once nothing is queued."
  []
  (let [{:keys [queues real-fetch]} @server-edge]
    (when (and real-fetch (empty? queues))
      (set! (.-fetch js/globalThis) real-fetch)
      (swap! server-edge assoc :real-fetch nil)))
  nil)

(defn- take-entry!
  "Pop the entry `rid` owes, recording the request id it answered."
  [rid request-id]
  (let [[before _] (swap-vals! server-edge drop-entry rid request-id)]
    (first (get-in before [:queues rid]))))

(defn- serving-fetch
  "The fetch BareProbe installs. Answers from the queue when one is owed, and otherwise sends the
  request as it would have gone."
  [url init]
  (let [{:keys [queues real-fetch]} @server-edge
        request-id                  (request-id-of url)
        rid                         (serving-resource queues request-id)]
    (if rid
      (let [{:keys [probe/envelope]} (take-entry! rid request-id)]
        (uninstall-when-idle!)
        (js/Promise.resolve (canned-response envelope request-id)))
      (.call real-fetch js/globalThis url init))))

(defn serving
  "What is queued, as {resource-id [{:probe/envelope … :probe/kind …} …]}, or nil when nothing is.
  The wrapper is installed exactly while this is non-nil."
  []
  (not-empty (:queues @server-edge)))

(defn serve!
  "Queue `envelope` as the answer to the next request for `rid`. The envelope is the wire form,
  with string keys, exactly as the server would have sent it. Returns what is queued.

  A read and a write carry their request id the same way, so by default whichever arrives first
  takes the envelope. The trailing directive pins it to one: `{:kind :read}` or `{:kind :write}`.

  One envelope answers one request and is then spent. Nothing outlives the queue: the wrapper this
  installs comes off as soon as the last envelope is taken."
  ([rid envelope] (serve! rid envelope {:kind :any}))
  ([rid envelope {:keys [kind]}]
   (when-not (kinds kind)
     (core/refuse (str "not a kind of request, try " (pr-str (vec (sort kinds))))
                  :not-a-kind
                  {:kind kind :kinds kinds}))
   (when-not (:real-fetch @server-edge)
     (swap! server-edge assoc :real-fetch (.-fetch js/globalThis))
     (set! (.-fetch js/globalThis) serving-fetch))
   (swap! server-edge update-in [:queues rid] (fnil conj [])
          {:probe/envelope envelope :probe/kind kind})
   (serving)))

(defn clear-serving!
  "Drop what is queued, all of it or one resource's. Returns what is left, as `serving` reads it."
  ([]
   (swap! server-edge assoc :queues nil)
   (uninstall-when-idle!)
   (serving))
  ([rid]
   (swap! server-edge update :queues dissoc rid)
   (uninstall-when-idle!)
   (serving)))

(defn injected?
  "A predicate on a summary row: did BareProbe answer the request this row records?"
  [row]
  (contains? (:served @server-edge) (:request/id row)))

;; The latest round trip, and the page it produced ----------------------------------------
;; Copied from BareBuild: the <server-resource> tag, its resource-id attribute, and the private
;; key it caches its consumers under. A conformance test covers all three.

(def ^:private resource-tag "server-resource")
(def ^:private attr-resource-id "resource-id")
(def ^:private k-consumers "__xConsumers")

(defn- resource-host
  "The resource element carrying `rid`, or nil."
  [rid]
  (when (exists? js/document)
    (some (fn [^js el] (when (= rid (.getAttribute el attr-resource-id)) el))
          (array-seq (.querySelectorAll js/document resource-tag)))))

(defn- consumer-elements
  "The consumer elements the resource element for `rid` drives, in the order it notifies them.
  BareBuild caches them as a CLJS vector rather than a js array, which a conformance test pins."
  [rid]
  (when-let [^js host (resource-host rid)]
    (seq (gobj/get host k-consumers))))

(defn- painted
  "`el` and every custom element in the light DOM under it, each as its id, its tag and its
  attributes. A consumer writes what it projects onto the element it drives, which is where this
  reads."
  [^js el]
  (into []
        (comp (filter component?)
              (map (fn [^js e] {:probe/id    (pick/id-of e)
                                :probe/tag   (str/lower-case (.-tagName e))
                                :probe/attrs (attrs-of e)})))
        (cons el (array-seq (.querySelectorAll el "*")))))

(defn rendered
  "What the consumers of `rid` show now, as each consumer's id mapped to the custom elements under
  it, or nil when the resource drives none. Reading stamps a pick id on every element it reports.
  Every id here is one `attrs`, `props`, `show!` and `pick!` speak."
  [rid]
  (not-empty
   (into {}
         (map (fn [^js c] [(pick/id-of c) (painted c)]))
         (consumer-elements rid))))

(defn latest
  "The most recent round trip, with `rendered` on it under `:probe/rendered`. The recorded legs are
  the past and the rendering is the present, which agree only because this is the most recent
  trip."
  []
  (when-let [trip (last (round-trips))]
    (assoc trip :probe/rendered (rendered (:resource/id trip)))))

(defn status
  "What is recorded, and what is armed at the edge. The request count is what the decorator saw
  since the faults were last set, a fault-dropped request included."
  []
  {:probe/entries              (count (log))
   :probe/faults               (:faults @fault-edge)
   :probe/requests-since-armed (:seen @fault-edge)
   :probe/serving              (serving)
   :probe/injected             (count (:served @server-edge))})

