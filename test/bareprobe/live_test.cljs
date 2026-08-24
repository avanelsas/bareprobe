(ns bareprobe.live-test
  "Tests for the sugar over the ambient log, the request edge and the properties. `serve!` wraps
  the global fetch, so a stub installed before it is what BareProbe keeps as the original, and
  nothing here reaches a network."
  (:require [barebuild.decorator :as decorator]
            [barebuild.resource :as resource]
            [baredom.dev.pick]
            [bareprobe.core :as core]
            [bareprobe.live :as live]
            [barereplay.store :as store]
            [clojure.datafy :as datafy]
            [cljs.test :refer [async deftest is testing use-fixtures]]))

(def ^:private node-fetch (.-fetch js/globalThis))

(defn- stub-fetch!
  "Replace fetch with one that records the urls it is given, and return that record. It answers an
  empty JSON body, so a request BareProbe fails to answer fails an assertion rather than the run."
  []
  (let [sent (atom [])]
    (set! (.-fetch js/globalThis)
          (fn [url _init]
            (swap! sent conj url)
            (js/Promise.resolve (js/Response. "{}" #js {:status 200}))))
    sent))

(use-fixtures :each
  {:after (fn []
            (live/clear!)
            (live/clear-serving!)
            (live/fault! nil)
            (set! (.-fetch js/globalThis) node-fetch))})

;; The sugar over the ambient log -------------------------------------------------------
;; The log is BareReplay's store, which is writable, so a record put there is what `live` reads.

(def ^:private tasks-0
  (resource/initial {:resource/id "tasks" :endpoint "/api/tasks" :url-intent {}}))

(defn- record!
  "Step `r` through `event` and record it as the runtime would. Answers the resource it left."
  [r event]
  (let [{:keys [resource effects]} (resource/step r event)]
    (store/record! {:resource/id "tasks" :el nil :event event
                    :before r :after resource :effects effects})
    resource))

(deftest the-sugar-answers-what-core-answers-of-the-ambient-log
  (live/clear!)
  (record! tasks-0 [:connected {:embed nil}])
  (let [entries (live/log)]
    (is (= 1 (count entries)) "the record reached the log the sugar reads")
    (testing "every arity supplies the log and changes nothing else"
      (is (= (core/state entries) (live/state)))
      (is (= (core/state entries 0) (live/state 0)))
      (is (= (core/summary entries) (live/summary)))
      (is (= (core/disagreements entries) (live/disagreements)))
      (is (= (core/coverage entries) (live/coverage)))
      (is (= (core/at entries 1) (live/at 1)))
      (is (= (core/diff entries 1) (live/diff 1)))
      (is (= (core/diff entries 0 1) (live/diff 0 1)))
      (is (= (core/resource entries "tasks") (live/resource "tasks")))
      (is (= (core/resource entries "tasks" 0) (live/resource "tasks" 0)))))
  (testing "and a refusal comes through it unchanged"
    (is (thrown? js/Error (live/at 99)))))

;; Restated here rather than required from `live.clj`: a test that reuses the implementation's own
;; helper asserts nothing.

(defn- arities
  "The arglists `v` declares, the quote a single arity is wrapped in removed."
  [v]
  (let [declared (:arglists (meta v))]
    (if (= 'quote (first declared)) (second declared) declared)))

(deftest a-sugar-verb-publishes-the-core-arities-without-the-log
  (doseq [[sugar core] [[#'live/state #'core/state]
                        [#'live/disagreements #'core/disagreements]
                        [#'live/summary #'core/summary]
                        [#'live/resource #'core/resource]
                        [#'live/at #'core/at]
                        [#'live/diff #'core/diff]
                        [#'live/coverage #'core/coverage]]]
    (is (= (map rest (arities core)) (seq (arities sugar)))
        (str (:name (meta core)) " and its sugar name different arities"))))

;; The request edge ---------------------------------------------------------------------

(def ^:private envelope
  {"outcome" "rejected"
   "revision" "r-41"
   "error"   {"code" "invalid-range" "details" {"field" "end"}}})

(defn- body-of
  "A promise of `response`'s body as a CLJS map."
  [response]
  (.then (.text response) #(js->clj (js/JSON.parse %))))

(deftest a-queued-envelope-answers-the-next-request
  (async done
    (let [sent (stub-fetch!)]
      (live/serve! "tasks" envelope)
      (-> (js/fetch "http://elsewhere/api/tasks?requestId=tasks:4&page=2" nil)
          (.then body-of)
          (.then (fn [answered]
                   (testing "the envelope is what the caller authored"
                     (is (= "rejected" (get answered "outcome")))
                     (is (= "end" (get-in answered ["error" "details" "field"]))))
                   (testing "carrying the request id the caller never typed"
                     (is (= "tasks:4" (get answered "requestId"))))
                   (testing "and nothing went out"
                     (is (= [] @sent)))
                   (testing "and the envelope is spent"
                     (is (nil? (get (live/serving) "tasks"))))
                   (done)))))))

(deftest a-relative-url-is-answered
  (async done
    (let [sent (stub-fetch!)]
      (live/serve! "tasks" envelope)
      ;; The shape barebuild.utils.request builds: no host, and the runtime sends nothing else.
      (-> (js/fetch "/api/tasks?requestId=tasks:1&page=2" nil)
          (.then body-of)
          (.then (fn [answered]
                   (is (= "rejected" (get answered "outcome")))
                   (is (= "tasks:1" (get answered "requestId")))
                   (is (= [] @sent))
                   (done)))))))

(deftest an-envelope-pinned-to-a-kind-waits-for-that-kind
  (async done
    (let [sent (stub-fetch!)]
      (live/serve! "tasks" envelope {:kind :write})
      (-> (js/fetch "/api/tasks?requestId=tasks:3" nil)
          (.then (fn [_]
                   (testing "a read is passed over and goes out as it would have"
                     (is (= ["/api/tasks?requestId=tasks:3"] @sent))
                     (is (= 1 (count (get (live/serving) "tasks")))))
                   (js/fetch "/api/tasks?requestId=tasks:w3" nil)))
          (.then body-of)
          (.then (fn [answered]
                   (testing "and the write it was queued for takes it"
                     (is (= "rejected" (get answered "outcome")))
                     (is (nil? (live/serving))))
                   (done)))))))

(deftest an-envelope-pinned-to-a-read-is-passed-over-by-a-write
  (async done
    (let [sent (stub-fetch!)]
      (live/serve! "tasks" envelope {:kind :read})
      (-> (js/fetch "/api/tasks?requestId=tasks:w1" nil)
          (.then (fn [_]
                   (is (= ["/api/tasks?requestId=tasks:w1"] @sent))
                   (is (= 1 (count (get (live/serving) "tasks"))))
                   (done)))))))

(deftest an-unpinned-envelope-answers-either-kind
  (async done
    (let [sent (stub-fetch!)]
      (live/serve! "tasks" envelope)
      (-> (js/fetch "/api/tasks?requestId=tasks:w7" nil)
          (.then body-of)
          (.then (fn [answered]
                   (is (= "rejected" (get answered "outcome")))
                   (is (= [] @sent))
                   (is (nil? (live/serving)))
                   (done)))))))

(deftest serve-refuses-a-kind-it-does-not-know
  (is (thrown? js/Error (live/serve! "tasks" envelope {:kind :both})))
  (testing "and the message names the kinds that would have worked"
    (is (re-find #":read"
                 (try (live/serve! "tasks" envelope {:kind :both})
                      (catch :default e (ex-message e))))))
  (testing "and queues nothing"
    (is (nil? (live/serving)))))

(deftest an-envelope-naming-a-request-keeps-its-own
  (async done
    (let [sent (stub-fetch!)]
      (live/serve! "tasks" (assoc envelope "requestId" "tasks:1"))
      (-> (js/fetch "http://elsewhere/api/tasks?requestId=tasks:9" nil)
          (.then body-of)
          (.then (fn [answered]
                   (testing "so a deliberately stale answer stays possible"
                     (is (= "tasks:1" (get answered "requestId")))
                     (is (= [] @sent)))
                   (done)))))))

(deftest a-request-nobody-queued-for-goes-out
  (async done
    (let [sent (stub-fetch!)]
      (live/serve! "tasks" envelope)
      (-> (js/fetch "http://elsewhere/api/projects?requestId=projects:1" nil)
          (.then (fn [_]
                   (testing "another resource is not answered from the tasks queue"
                     (is (= ["http://elsewhere/api/projects?requestId=projects:1"] @sent)))
                   (testing "and the queue is untouched"
                     (is (= 1 (count (get (live/serving) "tasks")))))
                   (done)))))))

(deftest queued-envelopes-answer-in-turn
  (async done
    (let [sent (stub-fetch!)]
      (live/serve! "tasks" (assoc envelope "revision" "first"))
      (live/serve! "tasks" (assoc envelope "revision" "second"))
      (is (= 2 (count (get (live/serving) "tasks"))))
      (-> (js/fetch "http://elsewhere/api/tasks?requestId=tasks:1" nil)
          (.then body-of)
          (.then (fn [answered]
                   (is (= "first" (get answered "revision")))
                   (js/fetch "http://elsewhere/api/tasks?requestId=tasks:2" nil)))
          (.then body-of)
          (.then (fn [answered]
                   (is (= "second" (get answered "revision")))
                   (is (= [] @sent))
                   (is (nil? (get (live/serving) "tasks")))
                   (done)))))))

(deftest injected-rows-are-the-ones-bareprobe-answered
  (async done
    (stub-fetch!)
    (live/serve! "tasks" envelope)
    (-> (js/fetch "http://elsewhere/api/tasks?requestId=tasks:4" nil)
        (.then (fn [_]
                 (is (live/injected? {:request/id "tasks:4"}))
                 (testing "a request that went out is not marked"
                   (is (not (live/injected? {:request/id "tasks:5"}))))
                 (testing "nor is a row naming no request"
                   (is (not (live/injected? {:request/id nil}))))
                 (testing "and clearing the log forgets them, so ids reused later are not marked"
                   (live/clear!)
                   (is (not (live/injected? {:request/id "tasks:4"}))))
                 (done))))))

(deftest a-resource-does-not-answer-for-one-whose-name-it-prefixes
  (async done
    (let [sent (stub-fetch!)]
      (live/serve! "task" envelope)
      (-> (js/fetch "http://elsewhere/api/tasks?requestId=tasks:1" nil)
          (.then (fn [_]
                   (is (= ["http://elsewhere/api/tasks?requestId=tasks:1"] @sent))
                   (is (= 1 (count (get (live/serving) "task"))))
                   (done)))))))

(deftest a-resource-id-carrying-a-colon-is-answered
  (async done
    (let [sent (stub-fetch!)]
      (live/serve! "a:b" envelope)
      (-> (js/fetch "/api/x?requestId=a:b:1" nil)
          (.then body-of)
          (.then (fn [answered]
                   (testing "the last colon separates the id from the number, not the first"
                     (is (= "rejected" (get answered "outcome")))
                     (is (= [] @sent))
                     (is (nil? (live/serving))))
                   (done)))))))

(deftest clearing-puts-the-original-fetch-back
  (stub-fetch!)
  (let [stub (.-fetch js/globalThis)]
    (live/serve! "tasks" envelope)
    (is (not= stub (.-fetch js/globalThis)) "the wrapper is installed")
    (live/clear-serving!)
    (is (= stub (.-fetch js/globalThis)) "and taken back off")
    (is (nil? (live/serving)))))

(deftest the-wrapper-comes-off-when-the-last-envelope-is-taken
  (async done
    (stub-fetch!)
    (let [stubbed (.-fetch js/globalThis)]
      (live/serve! "tasks" envelope)
      (live/serve! "tasks" envelope)
      (-> (js/fetch "http://elsewhere/api/tasks?requestId=tasks:1" nil)
          (.then (fn [_]
                   (testing "one envelope left, so the wrapper stays"
                     (is (not= stubbed (.-fetch js/globalThis))))
                   (js/fetch "http://elsewhere/api/tasks?requestId=tasks:2" nil)))
          (.then (fn [_]
                   (testing "nothing owed, so nothing is left installed"
                     (is (= stubbed (.-fetch js/globalThis)))
                     (is (nil? (live/serving))))
                   (done)))))))

(deftest clearing-one-resource-leaves-the-others-queued
  (stub-fetch!)
  (let [stubbed (.-fetch js/globalThis)]
    (live/serve! "tasks" envelope)
    (live/serve! "projects" envelope)
    (live/clear-serving! "tasks")
    (testing "the named resource is dropped and the other is not"
      (is (nil? (get (live/serving) "tasks")))
      (is (= 1 (count (get (live/serving) "projects")))))
    (testing "and the wrapper stays while one resource is still owed"
      (is (not= stubbed (.-fetch js/globalThis))))
    (live/clear-serving! "projects")
    (testing "dropping the last one takes it off"
      (is (nil? (live/serving)))
      (is (= stubbed (.-fetch js/globalThis))))))

(deftest a-pinned-envelope-at-the-head-holds-the-queue
  (async done
    (let [sent (stub-fetch!)]
      (live/serve! "tasks" envelope {:kind :write})
      (live/serve! "tasks" envelope {:kind :read})
      (-> (js/fetch "/api/tasks?requestId=tasks:1" nil)
          (.then (fn [_]
                   (testing "a read is not answered from behind the write at the head"
                     (is (= ["/api/tasks?requestId=tasks:1"] @sent))
                     (is (= 2 (count (get (live/serving) "tasks")))))
                   (done)))))))

;; The fault edge -----------------------------------------------------------------------

(deftest fault-arms-the-decorator-and-clearing-puts-the-old-one-back
  (let [before (decorator/current)]
    (testing "arming installs a decorator and answers what is armed"
      (is (= {:latency 5} (live/fault! {:latency 5})))
      (is (not= before (decorator/current))))
    (testing "rearming replaces the faults without installing a second time"
      (let [armed (decorator/current)]
        (is (= {:latency 5 :fail-every 2} (live/fault! {:latency 5 :fail-every 2})))
        (is (= armed (decorator/current)))))
    (testing "clearing answers nil and restores the decorator installed before"
      (is (nil? (live/fault! nil)))
      (is (= before (decorator/current))))))

(deftest arming-restarts-the-request-count
  (async done
    (live/fault! {})
    (-> (js/Promise.resolve)
        (.then (fn [] ((decorator/current) {})))
        (.then (fn [_]
                 (is (= 1 (:probe/requests-since-armed (live/status))))
                 (testing "arming again counts from that call, so a fail-every cycle starts there"
                   (live/fault! {:fail-every 3})
                   (is (= 0 (:probe/requests-since-armed (live/status)))))
                 (done))))))

;; The decorator is called the way `transport/request-init` calls it, inside a promise chain, so a
;; throw and a rejection both reach the same catch.
(deftest an-injected-fault-carries-the-one-diagnostic-shape
  (async done
    (live/fault! {:fail-every 1})
    (-> (js/Promise.resolve)
        (.then (fn [] ((decorator/current) {})))
        (.then (fn [_] (is false "the fault did not throw")))
        (.catch (fn [e]
                  (testing "the same two keys a refusal carries"
                    (is (= :injected-fault (:probe/code (ex-data e))))
                    (is (= {:fault :fail-every :requests-since-armed 1}
                           (:probe/detail (ex-data e)))))))
        (.then (fn [_] (done))))))

(deftest status-reads-the-log-and-both-edge-machines
  (live/clear!)
  (record! tasks-0 [:connected {:embed nil}])
  (live/serve! "tasks" envelope)
  (live/fault! {:latency 1})
  (let [{:keys [probe/entries probe/faults probe/requests-since-armed
                probe/serving probe/injected]} (live/status)]
    (is (= 1 entries))
    (is (= {:latency 1} faults))
    (is (= 0 requests-since-armed))
    (is (= 1 (count (get serving "tasks"))))
    (is (= 0 injected)))
  (testing "and clearing the edge drops the faults it reports"
    (live/fault! nil)
    (is (nil? (:probe/faults (live/status))))))

(deftest table-prints-the-rows-and-answers-nil
  (live/clear!)
  (record! tasks-0 [:connected {:embed nil}])
  (testing "the no-argument arity prints one line per round trip"
    (let [printed (with-out-str (live/table!))]
      (is (re-find #"probe/positions" printed))
      (is (re-find #"probe/sent\?" printed))
      (is (re-find #"connected" printed))))
  (testing "ks selects the columns"
    (is (re-find #"probe/label" (with-out-str (live/table! [:probe/label] (live/summary))))))
  (testing "and no rows prints nothing, so a filter that matched none is not an error"
    (is (nil? (live/table! [])))))

;; Attributes ---------------------------------------------------------------------------
;; `attrs` reads `.attributes`, an array-like of name and value, which under Node is any array of
;; objects carrying the two.

(defn- attrs-of
  "`live/attrs` against an element carrying `pairs` as its attributes, whatever id it is asked for."
  [pairs]
  (let [el (when pairs
             #js {:attributes (into-array (map (fn [[n v]] #js {:name n :value v}) pairs))})]
    (with-redefs [baredom.dev.pick/element-for (fn [_] el)]
      (live/attrs "x-form-field#4"))))

(deftest attrs-answers-the-attributes-as-a-map-of-name-to-value
  (is (= {"error-field" "start" "label" "Start date"}
         (attrs-of [["error-field" "start"] ["label" "Start date"]])))
  (testing "an element carrying none answers an empty map"
    (is (= {} (attrs-of []))))
  (testing "an id that resolves to nothing answers nil, which an empty map would not say"
    (is (nil? (attrs-of nil)))))

;; Properties ---------------------------------------------------------------------------
;; `props` needs an element, which under Node is any object carrying a tag name whose prototype
;; carries getters, since that is all `component?` and `getter-names` read. The tag name is a value
;; property rather than a getter, so the walk does not report it as one of the element's own.

(defn- stub-proto
  "A prototype below `base` declaring `getters`, plus one method and a hyphenated tag name, as a
  component's does."
  [base getters]
  (let [proto (js/Object.create base)]
    (doseq [[k f] getters]
      (js/Object.defineProperty proto k #js {:get f :configurable true :enumerable true}))
    (js/Object.defineProperty proto "focus"
                              #js {:value (fn []) :writable true :configurable true})
    (js/Object.defineProperty proto "tagName"
                              #js {:value "X-SEARCH-FIELD" :configurable true})
    proto))

(defn- stub-element
  "An object whose prototype declares `getters`, plus one method, as a component's does."
  [getters]
  (js/Object.create (stub-proto js/Object.prototype getters)))

(defn- props-of
  "`live/props` against `el`, whatever id it is asked for."
  [el]
  (with-redefs [baredom.dev.pick/element-for (fn [_] el)]
    (live/props "x-search-field#1")))

(deftest props-keeps-the-scalars-and-ignores-methods
  (is (= {"value" "He" "debounce" 250 "disabled" false "owner" nil}
         (props-of (stub-element {"value"    (fn [] "He")
                                  "debounce" (fn [] 250)
                                  "disabled" (fn [] false)
                                  "owner"    (fn [] nil)})))))

(deftest props-reads-what-a-base-class-declares
  (testing "a component extending another component answers both classes' properties"
    (let [base (stub-proto js/Object.prototype {"owner" (fn [] "Bob")})
          el   (js/Object.create (stub-proto base {"value" (fn [] "He")}))]
      (is (= {"value" "He" "owner" "Bob"} (props-of el)))))
  (testing "and a name the subclass redeclares is answered once"
    (let [base (stub-proto js/Object.prototype {"value" (fn [] "base")})
          el   (js/Object.create (stub-proto base {"value" (fn [] "own")}))]
      (is (= {"value" "own"} (props-of el))))))

(deftest props-names-what-it-skipped
  (let [m (props-of (stub-element {"value"    (fn [] "He")
                                   "labels"   (fn [] #js [])
                                   "validity" (fn [] #js {})}))]
    (is (= "He" (get m "value")))
    (is (= ["labels" "validity"] (vec (:probe/skipped m))))
    (testing "the skipped ones are not in the map"
      (is (= #{"value" :probe/skipped} (set (keys m)))))))

(deftest props-says-nothing-when-nothing-was-skipped
  (is (not (contains? (props-of (stub-element {"value" (fn [] "He")})) :probe/skipped)))
  (testing "an element declaring no properties answers an empty map"
    (is (= {} (props-of (stub-element {}))))))

(deftest props-answers-nil-for-an-id-that-resolves-to-nothing
  (is (nil? (props-of nil))))

(defn- plain-element
  "An object carrying `tag` as its tag name, as an element the platform declares every property of."
  [tag]
  (let [el (js/Object.create js/Object.prototype)]
    (js/Object.defineProperty el "tagName" #js {:value tag :configurable true})
    el))

(deftest props-refuses-an-element-that-is-not-a-component
  (testing "a tag without a hyphen names no custom element, so the walk would read the platform's"
    (is (thrown? js/Error (props-of (plain-element "DIV")))))
  (testing "and the message names the verb that reads one"
    (is (re-find #"attrs" (try (props-of (plain-element "DIV"))
                               (catch :default e (ex-message e))))))
  (testing "carrying the one diagnostic shape, with the tag that was refused"
    (let [{:keys [probe/code probe/detail]}
          (try (props-of (plain-element "svg")) (catch :default e (ex-data e)))]
      (is (= :not-a-component code))
      (is (= "svg" (:tag detail))))))

(deftest a-skipped-name-navigates-to-what-the-property-holds
  (let [labels (js/Array. "a")
        m      (props-of (stub-element {"value" (fn [] "He") "labels" (fn [] labels)}))]
    (is (= ["labels"] (vec (:probe/skipped m))))
    (testing "navigating resolves the element by id rather than one held from before"
      (let [el (stub-element {"labels" (fn [] labels)})]
        (with-redefs [baredom.dev.pick/element-for (fn [_] el)]
          (is (identical? labels (datafy/nav (:probe/skipped m) 0 "labels"))))))))

(deftest navigation-reads-the-element-the-id-names-now
  (let [m  (props-of (stub-element {"labels" (fn [] (js/Array. "first"))}))
        el (stub-element {"labels" (fn [] (js/Array. "second"))})]
    (with-redefs [baredom.dev.pick/element-for (fn [_] el)]
      (is (= ["second"] (vec (datafy/nav (:probe/skipped m) 0 "labels")))))))
