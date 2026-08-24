(ns bareprobe.conformance-test
  "Conformance tests for the BareBuild facts BareProbe copies or depends on. `bareprobe.core`
  copies which events answer a request and where each kind's in-flight request is held, both
  private in `barebuild.resource`. `bareprobe.live` copies the request id format and where it
  rides the URL, and reaches both edges through BareBuild's own seams: the global fetch, which
  `serve!` wraps, and the request decorator, which `fault!` installs into. `core/coverage` reads
  the declared enum out of an accepted shape. A BareBuild change to any of them is silent here
  without these."
  (:require [barebuild.elements.server-resource.consumers :as consumers]
            [barebuild.elements.server-resource.model :as sr-model]
            [barebuild.resource :as resource]
            [barebuild.transport :as transport]
            [barebuild.wire :as wire]
            [bareprobe.core :as core]
            [bareprobe.live :as live]
            [cljs.test :refer [async deftest is testing use-fixtures]]
            [clojure.string :as str]
            [goog.object :as gobj]))

;; The event vocabulary, read from `resource/transition`. An event BareBuild removes is caught by
;; `every-event-is-known`. An event it adds is not, until this list is updated.
(def ^:private vocabulary
  [:connected :response :intent-patch :refresh :intent-unroutable :url-changed
   :protocol-failed :network-failed :disconnected
   :submit-write :write-ack :write-failed])

(def ^:private base
  (resource/initial {:resource/id "tasks" :endpoint "/api/tasks" :url-intent {}}))

(defn- effect-id
  "The :request/id carried by the first `tag` effect in `effects`."
  [effects tag]
  (some (fn [[t m]] (when (= tag t) (:request/id m))) effects))

(defn- effect-url
  "The :url carried by the first `tag` effect in `effects`."
  [effects tag]
  (some (fn [[t m]] (when (= tag t) (:url m))) effects))

;; A read and a write in flight at once, both opened through BareBuild's own transitions so the
;; ids come from its effects rather than from the slot names under test.
(def ^:private connected (resource/step base [:connected {:embed nil}]))
(def ^:private submitted (resource/step (:resource connected)
                                        [:submit-write {:op :update :id "1"
                                                        :record {"title" "t"}}]))
(def ^:private in-flight (:resource submitted))
(def ^:private read-id (effect-id (:effects connected) :fetch))
(def ^:private write-id (effect-id (:effects submitted) :write))

(defn- diagnostic-code
  "The :code of the first diagnostic effect in `effects`."
  [effects]
  (some (fn [[t m]] (when (= :diagnostic t) (:code m))) effects))

(defn- stale-for
  "The kind BareBuild reports when `event-k` names a request that is not in flight, or nil."
  [event-k]
  (let [{:keys [effects]} (resource/step in-flight [event-k {:request/id "no-such-request"}])]
    (some (fn [[t {:keys [code detail]}]]
            (when (and (= :diagnostic t) (= :stale-answer code)) (:for detail)))
          effects)))

(defn- answering?
  "True when `core/answer` treats `event-k` as an answering event."
  [event-k]
  (try
    (core/answer in-flight event-k {})
    true
    (catch :default e
      (not= :not-an-answering-event (:probe/code (ex-data e))))))

(defn- reaching-transition
  "An event of kind `event-k` that reaches BareBuild's transition. An answering event carries the
  id of the request in flight, because `step` drops an uncorrelated one at the stale guard and
  never dispatches it."
  [event-k]
  (if (answering? event-k)
    (core/answer in-flight event-k {})
    [event-k {}]))

(deftest fixtures-are-what-the-tests-assume
  (testing "both kinds are in flight, with ids taken from BareBuild's effects"
    (is (some? read-id))
    (is (some? write-id))
    (is (not= read-id write-id))))

;; The fact `bareprobe.live` copies. `serving-resource` reads the resource id off the front of a
;; request id, `request-kind` reads the kind off the prefix that follows, and `request-id-of`
;; reads that id out of the URL query against a placeholder base. A format change makes `serve!`
;; match nothing and every request go to the real server, silently.
(deftest a-request-id-names-its-resource-and-rides-the-url
  (testing "the id begins with the resource id and a colon"
    (is (str/starts-with? read-id "tasks:"))
    (is (str/starts-with? write-id "tasks:")))
  (testing "a write id carries w before its number and a read id does not"
    (is (str/starts-with? write-id "tasks:w"))
    (is (not (str/starts-with? read-id "tasks:w"))))
  (testing "the url carries it under requestId"
    (is (str/includes? (effect-url (:effects connected) :fetch) (str "?requestId=" read-id)))
    (is (str/includes? (effect-url (:effects submitted) :write) (str "?requestId=" write-id))))
  (testing "and the url is relative, which is why parsing it needs a base"
    (is (str/starts-with? (effect-url (:effects connected) :fetch) "/api/tasks"))
    (is (str/starts-with? (effect-url (:effects submitted) :write) "/api/tasks"))))

(deftest every-event-is-known
  (testing "no event in the vocabulary falls through to :unknown-event"
    (doseq [event-k vocabulary]
      (is (not= :unknown-event
                (diagnostic-code (:effects (resource/step in-flight
                                                          (reaching-transition event-k)))))
          (str event-k " is no longer in BareBuild's vocabulary")))))

(deftest an-uncorrelated-answer-never-reaches-the-transition
  (testing "which is why every-event-is-known correlates the answering events"
    (doseq [event-k [:response :protocol-failed :network-failed :write-ack :write-failed]]
      (is (= :stale-answer
             (diagnostic-code (:effects (resource/step in-flight [event-k {}]))))
          (str event-k " no longer stops at the stale guard")))))

(defn- label-for
  "The label `summary` gives a record of `event`."
  [event]
  (:probe/label (first (core/summary [{:resource/id "tasks" :event event
                                       :before base :after base :effects []}]))))

(deftest every-event-has-a-label
  (testing "no event in the vocabulary falls through to its own key, on a payload carrying nothing"
    (doseq [event-k vocabulary]
      (is (not= (str event-k) (label-for [event-k {}]))
          (str event-k " has no label of its own")))))

;; A label names a key inside the payload, and BareBuild's transition reads that same key. A rename
;; upstream leaves the label with nothing after its prefix while every other test passes, so each
;; case asserts both sides. The three here are every event whose label names a key BareBuild reads.
;; `:url-changed` carries the intent as its whole payload, and `:intent-unroutable` hands its
;; payload to a diagnostic untouched.
(deftest a-label-reads-the-payload-key-barebuild-reads
  (testing "the intent patch key the label prints is the one apply-intent-patch merges"
    (let [event              [:intent-patch {:query-patch {:search "x"}}]
          {:keys [resource]} (resource/step base event)]
      (is (not= (:url-intent base) (:url-intent resource)))
      (is (str/includes? (label-for event) "search"))))
  (testing "the response outcome key the label prints is the one on-response cases on"
    (let [event                      (core/answer in-flight :response {:outcome :rejected})
          {:keys [resource effects]} (resource/step in-flight event)]
      (is (not= :unknown-outcome (diagnostic-code effects)))
      (is (= :rejected (:cause (:last-failure resource))))
      (is (= "response rejected" (label-for event)))))
  (testing "the write ack outcome key the label prints is the one that settles the write"
    (let [event              (core/answer in-flight :write-ack {:outcome :rejected})
          {:keys [resource]} (resource/step in-flight event)]
      (is (= :rejected (get-in resource [:last-write :status])))
      (is (= "write-ack rejected" (label-for event))))))

(deftest answering-events-match-barebuilds-guard
  (testing "the copied set is exactly the set BareBuild guards with :stale-answer"
    (is (= (set (filter stale-for vocabulary))
           (set (filter answering? vocabulary))))))

(deftest answer-correlates-to-the-request-in-flight
  (testing "read answers carry the in-flight read id"
    (doseq [event-k [:response :protocol-failed :network-failed]]
      (is (= read-id (:request/id (second (core/answer in-flight event-k {}))))
          (str event-k " did not resolve to the read in flight"))))
  (testing "write answers carry the in-flight write id"
    (doseq [event-k [:write-ack :write-failed]]
      (is (= write-id (:request/id (second (core/answer in-flight event-k {}))))
          (str event-k " did not resolve to the write in flight")))))

(deftest answer-builds-an-event-step-accepts
  (testing "an event built by answer is not dropped by the stale guard"
    (is (true? (:probe/moved? (core/read-step in-flight (core/answer in-flight :write-ack
                                                                     {:outcome :rejected})))))))

(deftest answer-refuses-when-nothing-is-in-flight
  (testing "a resource with no request open cannot answer one"
    (is (thrown? js/Error (core/answer base :write-ack {}))))
  (testing "and the message names the slot that would have to be set"
    (is (re-find #":active-write"
                 (try (core/answer base :write-ack {}) (catch :default e (ex-message e))))))
  (testing "an event that answers nothing is refused"
    (is (thrown? js/Error (core/answer in-flight :intent-patch {}))))
  (testing "and the message names the events that would have worked"
    (is (re-find #":write-ack"
                 (try (core/answer in-flight :intent-patch {})
                      (catch :default e (ex-message e)))))))

;; The shape key `core/coverage` reads that an installed envelope does not pin on its own. A shape
;; missing :id-key or :fields is refused, so any accepted envelope proves those two names. An enum
;; is declared on the wire and read on the write side, never checked against a read row, so a
;; rename leaves every envelope accepted and :probe/enum-unseen empty for ever. Parsed here through
;; `wire/parse-envelope`, which is what puts the shape into :last-accepted, and then read the way
;; `coverage` reads it.

(defn- wire-envelope
  "A js accepted envelope declaring `status` as an enum, carrying `values` as its rows."
  [values]
  #js {"outcome"   "accepted"
       "requestId" "tasks:1"
       "revision"  "tasks:v1"
       "query"     #js {}
       "pageInfo"  #js {"page" 1 "pageSize" 10 "totalPages" 1 "totalCount" 1}
       "shape"     #js {"idKey"  "id"
                        "fields" #js [#js {"key" "status" "type" "string"
                                           "enum" #js ["todo" "done"]}]}
       "value"     (clj->js values)})

(deftest a-declared-enum-survives-the-wire-under-the-key-coverage-reads
  (let [parsed (wire/parse-envelope (wire-envelope [{"id" "1" "status" "todo"}]))]
    (testing "the declared values arrive as :enum on the field"
      (is (= ["todo" "done"] (:enum (first (get-in parsed [:shape :fields]))))))
    (testing "and coverage names the one the rows never carried"
      (let [record {:resource/id "tasks" :el nil :event [:response parsed]
                    :before base :after (assoc base :last-accepted parsed) :effects []}]
        (is (= {"status" #{"done"}}
               (:probe/enum-unseen (get (core/coverage [record]) "tasks"))))))))

;; The two facts `core/view` copies: the effect tag, and the key the projection rides under.
;; Neither is checked by anything else, so a rename upstream makes `view` answer nil and read as
;; "no view was handed over". Asserted against `resource/project`, which is BareBuild's own
;; projection, rather than against a second copy of the extraction.

(deftest the-view-is-the-projection-barebuild-notifies-with
  (let [envelope                   (wire/parse-envelope
                                    (wire-envelope [{"id" "1" "status" "todo"}]))
        event                      (core/answer in-flight :response envelope)
        {:keys [resource effects]} (resource/step in-flight event)
        record                     {:resource/id "tasks" :el nil :event event
                                    :before in-flight :after resource :effects effects}]
    (testing "the step appended a :notify-consumers effect"
      (is (some (fn [[tag]] (= :notify-consumers tag)) effects)))
    (testing "and view answers BareBuild's own projection of the resource the step left"
      (is (= (resource/project resource) (core/view [record] 1))))))

(deftest view-answers-nil-when-a-record-notified-nobody
  (testing "a record carrying no :notify-consumers effect, which a step that moved no view leaves"
    (is (nil? (core/view [{:resource/id "tasks" :el nil :event [:refresh]
                           :before base :after base :effects []}]
                         1)))))

;; The three facts `live/latest` copies to reach the page. It finds the resource element by tag and
;; by the attribute carrying the resource id, then reads the consumers the element cached. A rename
;; of any of them leaves `:probe/rendered` empty, which reads as a resource nobody renders.

(deftest the-resource-element-is-found-the-way-live-finds-it
  (testing "the tag live queries for"
    (is (= "server-resource" sr-model/tag-name)))
  (testing "and the attribute it matches a resource id on"
    (is (= "resource-id" sr-model/attr-resource-id))))

(deftest consumers-are-cached-under-the-key-live-reads
  (let [host #js {}
        node #js {:applyResource (fn [_ _])}]
    (consumers/install! host "tasks" [node] (fn [_ _]))
    (testing "install! caches the consumers under the key live reads them from"
      (is (= [node] (vec (gobj/get host "__xConsumers")))))
    (testing "as a CLJS vector rather than a js array, which is what live may call seq on"
      (is (vector? (gobj/get host "__xConsumers"))))
    (testing "and keeps only the nodes that answer applyResource"
      (let [bare #js {}]
        (consumers/install! bare "tasks" [#js {}] (fn [_ _]))
        (is (= [] (vec (gobj/get bare "__xConsumers"))))))))

(deftest diagnostic-shape-matches-barebuilds-effect
  (testing "code and detail are lifted from [:diagnostic {:code c :detail d}]"
    (is (= {:probe/moved?      false
            :probe/diagnostics [{:probe/code   :stale-answer
                                 :probe/detail {:for :write :event :write-ack}}]}
           (core/read-step in-flight [:write-ack {:request/id "no-such-request"}])))))

;; The two seams `live` reaches through -----------------------------------------------------
;; Both are driven through `transport/perform!`, the pipeline BareBuild sends every read and every
;; write down, rather than through the wrapper and the decorator directly. Called that way they
;; assert the seam and not only BareProbe's own half of it. BareBuild capturing fetch at load, or
;; sending by any other means, makes `serve!` answer nothing and every request go to the real
;; server, silently.

(def ^:private node-fetch (.-fetch js/globalThis))

(defn- refuse-to-send!
  "Replace fetch with one that rejects, so a request BareProbe fails to answer fails loudly."
  []
  (set! (.-fetch js/globalThis)
        (fn [url _init] (js/Promise.reject (js/Error. (str "went out: " url))))))

(use-fixtures :each
  {:before refuse-to-send!
   :after  (fn []
             (live/clear-serving!)
             (live/fault! nil)
             (set! (.-fetch js/globalThis) node-fetch))})

(defn- performed
  "A promise of what `transport/perform!` resolves to for a GET carrying `request-id`."
  [request-id]
  (transport/perform! {:url (str "/api/tasks?requestId=" request-id) :method "GET"}
                      (js/AbortController.)))

(deftest a-served-envelope-reaches-barebuild-through-the-global-fetch
  (async done
    (live/serve! "tasks" {"outcome"  "rejected"
                          "revision" "r-41"
                          "error"    {"code" "invalid-range" "details" {"field" "end"}}})
    (-> (performed "tasks:1")
        (.then (fn [envelope]
                 (testing "BareBuild's own parse of the answer BareProbe queued"
                   (is (= :rejected (:outcome envelope)))
                   (is (= :invalid-range (get-in envelope [:error :code]))))
                 (testing "carrying the request id BareProbe stamped, which is what correlates it"
                   (is (= "tasks:1" (:request/id envelope))))
                 (done))))))

;; The console.error this prints is BareBuild reporting the failed decorator, and is the test
;; working rather than a fault in it.
(deftest an-armed-fault-stops-the-request-at-barebuilds-decorator-seam
  (async done
    (live/fault! {:fail-every 1})
    (-> (performed "tasks:2")
        (.then (fn [result]
                 (testing "the throw becomes a network failure naming the decorator, unsent"
                   (is (= {:network-failure {:kind :decorator}} result)))
                 (done))))))
