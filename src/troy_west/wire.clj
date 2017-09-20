(ns troy-west.wire
  "
  ## Description

  A small Clojure library for explicitly wiring together functions into
  declarative computation graphs.

  This approach has some similarities to [Dataflow Programming](https://en.wikipedia.org/wiki/Dataflow_programming).
  Unlike Dataflow Programming this library simply provides an approach to
  composing pure functions, it does not provide Dataflow variable or try to
  solve any concurrency related problem. By default it does not provide any
  memoization, caching, parrallel computation, partial evaluation or reactive
  programming.

  This library was inspired from working with formula, either given in hand
  written form or via a spreadsheet. These can be tricky to work with for a
  number of reasons:

  * All variables/cells have global scope
  * Understanding dependents/dependencies can be difficult
  * Reporting/debugging intermediate values in a computation can force
    awkward restructuring of code
  * It is difficult to know how to best structure your code
    * Calling dependent functions leads to repeated computation
    * Large let blocks don't compose

  By making the workflow of the computations explicit with a declarative
  graph it helps handle many of these issues:

  * Within the scope of a graph values can be accessed by any other part of
    the graph
  * The flow of dependencies is made very clear and can be easily visualised
  * The result of executing a graph is simply a map of all the values, this
    assists with reporting and debugging intermediate values
  * Graphs written as maps can compose with `merge` and other simple
    utility functions
  * Functions are written in a dependency injection style leading to
    good decoupling
  * As requirements change restructuring of code mainly consists of composing
    in new graphs into the flow or rewiring functions at the graph declaration
    level
  * Namespaced keywords enable composing and reusing subgraphs in larger flows

  ## Usage

  Functions can be declaratively wired together into a dependency map like such:

  ```clojure
  => (def dep-map {:foo/c [[:foo/a :foo/b]
                           *]
                   :foo/d [[:foo/c]
                           inc]
                   :foo/e [[:foo/a :foo/c :foo/d]
                           +]})
  ```

  The dependency map can be compiled to a graph as follows:

  ```clojure
  => (require '[troy-west.wire :as wire])
  => (wire/compile-graph dep-map)
  {:wire/dep-graph #clojure.tools.namespace.dependency.MapDependencyGraph
   {:dependencies {:foo/c #{:foo/a :foo/b},
                   :foo/d #{:foo/c},
                   :foo/e #{:foo/a :foo/c :foo/d}},
    :dependents {:foo/a #{:foo/c :foo/e},
                 :foo/b #{:foo/c},
                 :foo/c #{:foo/d :foo/e},
                 :foo/d #{:foo/e}}},
   :wire/dep-map {:foo/c [[:foo/a :foo/b] #<Fn@33ac9a84 clojure.core/_STAR_>],
                  :foo/d [[:foo/c] #<Fn@5103b66c clojure.core/inc>],
                  :foo/e [[:foo/a :foo/c :foo/d]
                          #<Fn@72cd95df clojure.core/_PLUS_>]}}
  ```

  You can find out if there are any unbound variables within the graph using
  `free-variables`:

  ```clojure
  => (def graph (wire/compile-graph dep-map))
  => (wire/free-variables (:wire/dep-graph graph))
  #{:foo/b :foo/a}
  ```

  To execute a computation using the graph you will need to provide values for
  the free variables:

  ```clojure
  => (wire/execute-graph graph {:foo/a 15 :foo/b 3})
  #:foo{:a 15, :b 3, :c 45, :d 46, :e 106}
  ```

  You can also compile and execute in one step:

  ```clojure
  => (wire/compile-and-execute dep-map {:foo/a 15 :foo/b 3})
  #:foo{:a 15, :b 3, :c 45, :d 46, :e 106}
  ```

  Bound values can be overwritten with values in the `args` map,
  useful for testing:

  ```clojure
  => (wire/compile-and-execute dep-map {:foo/a 15 :foo/b 3 :foo/c 20})
  #:foo{:a 15, :b 3, :c 20, :d 21, :e 56}
  ```

  Dependency maps can be composed with `merge`:

  ```clojure
  => (wire/compile-and-execute (merge dep-map {:foo/b [[:foo/a] dec]})
                               {:foo/a 15})
  #:foo{:a 15, :b 14, :c 210, :d 211, :e 436}
  ```

  There are three built in functions for visualising graphs,
  `viz-graph-names`, `viz-graph-results` and `viz-graph-fns`.

  Each can be called like:

  ```clojure
  => (wire/viz-graph-names graph {:foo/a 15 :foo/b 3})
  ```
  ![View graph with names](img/viz-graph-names.png)
  ```clojure
  => (wire/viz-graph-results graph {:foo/a 15 :foo/b 3})
  ```
  ![View graph with results](img/viz-graph-results.png)
  ```clojure
  => (wire/viz-graph-fns graph {:foo/a 15 :foo/b 3})
  ```
  ![View graph with functions](img/viz-graph-fns.png)

  "
  (:require [clojure.tools.namespace.dependency :as dep]
            [rhizome.viz :as viz]))

(defn compile-graph
  "
  Given a `dep-map` returns a `graph` that is a map that contains keys
  :wire/dep-graph and :wire/dep-map. :wire/dep-graph is a `DependencyGraph`
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
  Given a `graph` and some `args` resolve all the free variables in the
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
;; Fns to help with composing and manipulating `dep-map` structures.
;;

(defn- add-ns
  [k ns]
  (if (qualified-keyword? k) k (keyword (name ns) (name k))))

(defn with-ns
  "
  Add context to a given `dep-map` by adding the `ns` (namespace) to any keys
  within `dep-map` that don't already contain namespaces.

  e.g.

  => (with-ns {:foo/c [[:a :b]
                       *]
               :foo/d [[:foo/c]
                       inc]
               :foo/e [[:a :foo/c :foo/d]
                       +]}
              :bar)

  {:foo/c [[:bar/a :bar/b]
           #function[clojure.core/*]]
   :foo/d [[:foo/c]
           #function[clojure.core/inc]]
   :foo/e [[:bar/a :foo/c :foo/d]
           #function[clojure.core/+]]}
  "
  [dep-map ns]
  (reduce-kv
   (fn [m k v]
     (assoc m
            (add-ns k ns)
            (update-in v [0] (partial mapv #(add-ns % ns)))))
   {} dep-map))

(defn replace-keys*
  [dep-map replacer]
  (reduce-kv
   (fn [dep-map k v]
     (let [replace-fn replacer]
       (assoc dep-map
              (replace-fn k)
              (update-in v [0] (partial mapv replace-fn)))))
   {} dep-map))

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

(defn filter-ns
  "
  Filter the `dep-map` keys by `ns`.

  e.g.

  => (filter-ns {:foo/c [[:bar/a :bar/b]
                         *]
                 :bar/d [[:foo/c]
                         inc]
                 :foo/e [[:foo/a :foo/c :bar/d]
                         +]}
                :bar)

  {:bar/d [[:foo/c]
           inc]}
  "
  [dep-map ns]
  (select-keys dep-map (filter #(= (namespace %) (name ns)) (keys dep-map))))

(defn re-filter-ns
  "
  Filter the `dep-map` keys using a regex `re`.

  e.g.

  => (re-filter-ns {:foo/c [[:bar/a :bar/b]
                            *]
                    :bar/d [[:foo/c]
                            inc]
                    :foo/e [[:foo/a :foo/c :bar/d]
                            +]}
                   #\"f.*\")

  {:foo/c [[:bar/a :bar/b]
           *]
   :foo/e [[:foo/a :foo/c :bar/d]
           +]}
  "
  [dep-map re]
  (select-keys dep-map (filter #(re-matches re (namespace %)) (keys dep-map))))

(defn list-namespaces
  "
  List the namespaces in the keys of `dep-map`.

  e.g.

  => (list-namespace {:foo/c [[:bar/a :bar/b]
                              *]
                      :bar/d [[:foo/c]
                              inc]
                      :foo/e [[:foo/a :foo/c :bar/d]
                              +]})
  #{\"foo\" \"bar\"}
  "
  [dep-map]
  (set (map namespace (keys dep-map))))

;;
;; Fns for use in visualising graphs
;;

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
  "
  Low level function for using rhizome.viz functions to visualise graphs.
  "
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
           :options {:rankdir :BT}
           (apply concat (-> opts
                             (dissoc :node->descriptor)
                             (assoc :edge->descriptor (fn [_ _] {:dir :back})))))))

(defn viz-graph-names
  "
  Visualise a graph of the names (keywords) of the nodes.

  The 2 arity version of this function will execute the `graph` with the given
  `args`. The 3 arity version allows you to provided an `opts` map containing
  a `:results` map of the execution to avoid this function executing the graph.
  "
  ([graph args]
   (view-graph-names graph args (execute-graph graph args)))
  ([graph args {:keys [viz-fn results]
                :or   {viz-fn  viz/view-graph
                       results {}}
                :as   opts}]
   (viz-graph* viz-fn graph args results opts)))

(defn viz-graph-results
  "
  Visualise a graph of the names of the nodes along with their resolved value.

  The 2 arity version of this function will execute the `graph` with the given
  `args`. The 3 arity version allows you to provided an `opts` map containing
  a `:results` map of the execution to avoid this function executing the graph.
  "
  ([graph args]
   (view-graph-results graph args {:results (execute-graph graph args)}))
  ([graph args {:keys [viz-fn results]
                :or   {viz-fn  viz/view-graph
                       results {}}
                :as   opts}]
   (viz-graph* viz-fn graph args results (assoc opts
                                                :node->descriptor
                                                describe-with-result))))

(defn viz-graph-fns
  "
  Visualise a graph of the names of the nodes along with the function associated with
  each node.

  The 2 arity version of this function will execute the `graph` with the given
  `args`. The 3 arity version allows you to provided an `opts` map containing
  a `:results` map of the execution to avoid this function executing the graph.
  "
  ([graph args]
   (view-graph-fns graph args {:results (execute-graph graph args)}))
  ([graph args {:keys [viz-fn results]
                :or   {viz-fn  viz/view-graph
                       results {}}
                :as   opts}]
   (viz-graph* viz-fn graph args results (assoc opts
                                                :node->descriptor
                                                describe-with-fn))))
