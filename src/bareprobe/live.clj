(ns bareprobe.live
  "The macro behind `live`'s log-supplying verbs."
  (:require [cljs.analyzer.api :as ana]))

(defn- sugared-arglists
  "The arglists `verb` declares with its first parameter dropped. The analyzer quotes a single
  arity and leaves several unquoted."
  [verb]
  (let [declared (:arglists verb)]
    (map (comp vec rest) (if (= 'quote (first declared)) (second declared) declared))))

(defmacro defsugar
  "Def `sym` as the `bareprobe.core` verb of the same name, with the ambient log supplied, one
  arity per entry in `arglists`. The docstring is the core verb's own, and the arglists are checked
  against it."
  [sym & arglists]
  (let [core-sym (symbol "bareprobe.core" (name sym))
        verb     (ana/resolve &env core-sym)]
    (when-not verb
      (throw (ex-info (str core-sym " does not exist, name a verb bareprobe.core publishes")
                      {:probe/code :no-such-verb :probe/detail {:verb core-sym}})))
    (when-not (:doc verb)
      (throw (ex-info (str core-sym " has no docstring to carry, give it one")
                      {:probe/code :no-docstring-to-carry :probe/detail {:verb core-sym}})))
    (let [expected (sugared-arglists verb)]
      (when-not (= expected (seq arglists))
        (throw (ex-info (str core-sym " with the log supplied is " (pr-str expected) ", not "
                             (pr-str arglists))
                        {:probe/code   :arglists-disagree
                         :probe/detail {:verb core-sym :expected expected :named arglists}}))))
    `(defn ~sym
       ~(:doc verb)
       ~@(map (fn [args] `(~args (~core-sym (log) ~@args))) arglists))))
