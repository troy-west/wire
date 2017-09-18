(ns troy-west.wire
  (:require [clojure.tools.namespace.dependency :as dep]
            [rhizome.viz :as viz]))

(defn compile-graph
  "
  Given a `dep-map` returns a `compiled graph` that is a map that contains
  keys :wire/dep-graph and :wire/dep-map. :wire/dep-graph is a `DependencyGraph`
  (provided by clojure.tools.namespace.dependency) and :wire/dep-map,
  the original unchanged `dep-map`.

  A `dep-map` describes a graph of dependent function calls, composing
  a function graph explicitly. A `dep-map` maps keyword names to arguments
  and functions that can be used to resolve values for those names.

  An example graph may look like:

  {:foo/c [[:foo/a :foo/b]
           *]
   :foo/d [[:foo/c]
           inc]
   :foo/e [[:foo/a :foo/c :foo/d]
           +]}

  In this example :foo/a and :foor/b are free variables, whilst :foo/c, :foo/d
  and :foo/e are all bound and depend on the evaluation of the arguments listed
  in the first element of the vector they map to.
  e.g. :foo/e depends on :foo/a, :foo/c and :foo/d
  "
  [dep-map]
  {:wire/dep-graph (reduce (fn [g [k [deps _]]]
                             (reduce
                              (fn [g d]
                                (dep/depend g k d))
                              g deps))
                           (dep/graph) dep-map)
   :wire/dep-map dep-map})

(defn free-variables
  "
  Given a `dep-graph` (`DependencyGraph`) and optional some `args` return the
  argment names within the graph that are not been bound.
  "
  ([dep-graph]
   (free-variables dep-graph {}))
  ([{:keys [dependents dependencies]} args]
   (clojure.set/difference
    (into #{} (keys dependents))
    (clojure.set/union
     (into #{} (keys dependencies))
     (into #{} (keys args))))))

(defn resolve-arg
  [results dep]
  (get results dep))

(defn resolve-args
  [results deps]
  (map (partial resolve-arg results) deps))

(declare execute-graph)

(defn execute-node
  [dep-map results node-key]
  (let [[deps f] (get dep-map node-key)
        args     (resolve-args results deps)]
    (apply f args)))

(defn execute-graph
  "
  Given a `compiled map` and some `args` resolve all the free variables in the
  graphs. A map a keywords to their resolved values will be returned.
  "
  [{:keys [:wire/dep-graph :wire/dep-map]} args]
  (let [free-vars (free-variables dep-graph args)]
    (if (not (empty? free-vars))
      (throw (java.lang.IllegalArgumentException.
              (str "The arguments " free-vars " are not bound. "
                   "You may need to pass them as an argument while executing the graph.")))
      (let [topo (->> (dep/topo-sort dep-graph)
                      (filter keyword?)
                      (remove (into #{} (keys args))))]
        (reduce
         (fn [results k]
           (assoc results k (execute-node dep-map results k)))
         args topo)))))

(defn compile-and-execute
  "
  Convenience fn, given a `dep-map` and `args` compile and execute the graph in
  one step.
  "
  [dep-map args]
  (execute-graph (compile-graph dep-map) args))


;;
;; helper functions to deal with namespaced keys within graphs
;;

(defn add-ns
  [k ns]
  (if (qualified-keyword? k) k (keyword (name ns) (name k))))

(defn with-ns
  [m ns]
  (reduce-kv
   (fn [m k v]
     (assoc m
            (add-ns k ns)
            (update-in v [0] (partial mapv #(add-ns % ns)))))
   {} m))

(defn filter-ns
  [m ns]
  (select-keys m (filter #(= (namespace %) (name ns)) (keys m))))

(defn re-filter-ns
  [m re]
  (select-keys m (filter #(re-matches re (namespace %)) (keys m))))

(defn list-namespaces
  [m]
  (set (map namespace (keys m))))

(defn replace-keys*
  [dep-map replacer]
  (reduce-kv
   (fn [dep-map k v]
     (let [replace-fn replacer]
       (assoc dep-map
              (replace-fn k)
              (update-in v [0] (partial mapv replace-fn)))))
   {} dep-map))

;;
;; Public interface
;;

(defn replace-keys
  "
  Replaces keywords in the given `dep-map` by mapping to new keywords using
  `key-map`. The keywords to be replaced can either be in the key position or
  one of the arguments.

  e.g.

  => (replace-keys {:foo/c [[:foo/a :foo/b]
                            *]
                    :foo/d [[:foo/c]
                            inc]
                    :foo/e [[:foo/a :foo/c :foo/d]
                            +]}
                    {:foo/c :bar/z})

  {:bar/z [[:foo/a :foo/b]
           #function[clojure.core/*]]
   :foo/d [[:bar/z]
           #function[clojure.core/inc]]
   :foo/e [[:foo/a :bar/z :foo/d]
           #function[clojure.core/+]]}
  "
  [dep-map key-map]
  (replace-keys* dep-map #(get key-map % %)))

(defn replace-namespaces
  "
  Replaces namespaces in the given `dep-map` by mapping to new namespaces using
  `ns-map`. The namespaces to be replaced can either be on the keyword in the
  key position or on one of the arguments.

  e.g.

  => (replace-namespaces {:foo/c [[:bar/a :bar/b]
                                  *]
                          :bar/d [[:foo/c]
                                  inc]
                          :foo/e [[:foo/a :foo/c :bar/d]
                                  +]}
                          {:bar :baz})

  {:foo/c [[:baz/a :baz/b]
           #function[clojure.core/*]]
   :baz/d [[:foo/c]
           #function[clojure.core/inc]]
   :foo/e [[:foo/a :foo/c :baz/d]
           #function[clojure.core/+]]}
  "
  [dep-map ns-map]
  (replace-keys* dep-map
                 (fn [k]
                   (let [replacement (ns-map (keyword (namespace k)))]
                     (if replacement
                       (keyword (name replacement) (name k))
                       k)))))

(defn append-ns
  "
  Appends an extra namespace, `ns`, to the existing namespaces in the `dep-map`.
  The `ns` with be appended with a '.' delimiter to existing namespaces.

  e.g.

  => (append-ns {:foo/c [[:bar/a :bar/b]
                         *]
                 :bar/d [[:foo/c]
                         inc]
                 :foo/e [[:foo/a :foo/c :bar/d]
                         +]}
                :baz)

  {:foo.baz/c [[:bar.baz/a :bar.baz/b]
               #function[clojure.core/*]]
   :bar.baz/d [[:foo.baz/c]
               #function[clojure.core/inc]]
   :foo.baz/e [[:foo.baz/a :foo.baz/c :bar.baz/d]
               #function[clojure.core/+]]}

  There are also options to `exclude` or `only` append to certain
  namespaces.

  e.g.

  => (append-ns {:foo/c [[:bar/a :bar/b]
                         *]
                 :bar/d [[:foo/c]
                         inc]
                 :foo/e [[:foo/a :foo/c :bar/d]
                         +]}
                :baz
                {:exclude #{:bar}})

  {:foo.baz/c [[:bar/a :bar/b]
               #function[clojure.core/*]]
   :bar/d     [[:foo.baz/c]
               #function[clojure.core/inc]]
   :foo.baz/e [[:foo.baz/a :foo.baz/c :bar/d]
               #function[clojure.core/+]]}

  => (append-ns {:foo/c [[:bar/a :bar/b]
                         *]
                 :bar/d [[:foo/c]
                         inc]
                 :foo/e [[:foo/a :foo/c :bar/d]
                         +]}
                :baz
                {:only #{:bar}})

  {:foo/c     [[:bar.baz/a :bar.baz/b]
               #function[clojure.core/*]]
   :bar.baz/d [[:foo/c]
               #function[clojure.core/inc]]
   :foo/e     [[:foo/a :foo/c :bar.baz/d]
               #function[clojure.core/+]]}
  "
  ([dep-map ns]
   (append-ns dep-map ns {}))
  ([dep-map ns {:keys [exclude only]}]
   (let [include-ns (if (seq only)
                      (set (map name only))
                      (-> (set (map namespace
                                    (concat (keys dep-map)
                                            (flatten (map first (vals dep-map))))))
                          (clojure.set/difference (set (map name exclude)))))]
     (replace-keys* dep-map
                    (fn [k] (if (contains? include-ns (namespace k))
                              (keyword (str (namespace k) "." (name ns))
                                       (name k))
                              k))))))

(defn format-value
  ([form] (format-value nil))
  ([form filter-keys]
   (clojure.walk/postwalk
    (fn [x] (cond
              (number? x) (format "%.2f" (float x))
              (map? x) (for [[k v] x]
                         (if (contains? filter-keys k) k [k v]))
              (seq? x) (into [] x)
              (nil? x) ::free
              :else x))
    form)))

(defn describe-with-result
  [_ args results]
  (fn [n]
    {:label [n (format-value (n results) args)]}))

(defn describe-with-fn
  [graph args results]
  (fn [n]
    {:label [n (format-value (-> graph :wire/dep-map n second) args)]}))

(defn viz-graph*
  [viz-fn graph args results {:keys [node->descriptor]
                              :or   {node->descriptor (fn [_ _ _]
                                                        (fn [n] {:label n}))}
                              :as   opts}]
  (let [deps (-> graph :wire/dep-graph :dependencies)]
    (apply viz-fn
           (concat (keys deps) (keys args))
           deps
           :node->descriptor
           (node->descriptor graph args results)
           (apply concat (dissoc opts :node->descriptor)))))

(defn view-graph-nodes
  ([graph args]
   (view-graph-results graph args (execute-graph graph args)))
  ([graph args results]
   (viz-graph* viz/view-graph
               graph
               args
               (execute-graph graph args))))

(defn view-graph-results
  ([graph args]
   (view-graph-results graph args (execute-graph graph args)))
  ([graph args results]
   (viz-graph* viz/view-graph
               graph
               args
               (execute-graph graph args)
               {:node->descriptor describe-with-result})))

(defn view-graph-fns
  ([graph args]
   (view-graph-fns graph args (execute-graph graph args)))
  ([graph args results]
   (viz-graph* viz/view-graph
               graph
               args
               (execute-graph graph args)
               {:node->descriptor describe-with-fn})))
