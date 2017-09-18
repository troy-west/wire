(ns troy-west.wire
  (:require [clojure.tools.namespace.dependency :as dep]
            [rhizome.viz :as viz]))

(defn compile-graph
  [dep-map]
  {:wire/dep-graph (reduce (fn [g [k [deps _]]]
                             (reduce
                              (fn [g d]
                                (dep/depend g k d))
                              g deps))
                           (dep/graph) dep-map)
   :wire/dep-map dep-map})

(defn free-variables
  ([{:keys [:wire/dep-graph]}]
   (free-variables dep-graph {}))
  ([{:keys [dependents dependencies]} args]
   (clojure.set/difference
    (into #{} (keys dependents))
    (clojure.set/union
     (into #{} (keys dependencies))
     (into #{} (keys args))))))

(defn resolve-arg [results dep]
  (get results dep))

(defn resolve-args [results deps]
  (map (partial resolve-arg results) deps))

(declare execute-graph)

(defn execute-node [dep-map results node-key]
  (let [[deps f] (get dep-map node-key)
        args     (resolve-args results deps)]
    (apply f args)))

(defn execute-graph
  [{:keys [:wire/dep-graph :wire/dep-map]} arg-map]
  (let [free-vars (free-variables dep-graph arg-map)]
    (if (not (empty? free-vars))
      (throw (java.lang.IllegalArgumentException.
              (str "The arguments " free-vars " are not bound. "
                   "You may need to pass them as an argument while executing the graph.")))
      (let [topo (->> (dep/topo-sort dep-graph)
                      (filter keyword?)
                      (remove (into #{} (keys arg-map))))]
        (reduce
         (fn [results k]
           (assoc results k (execute-node dep-map results k)))
         arg-map topo)))))

(defn build-and-execute
  [graph args]
  (execute-graph (compile-graph graph) args))

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
  [m replacer]
  (reduce-kv
   (fn [m k v]
     (let [replace-fn replacer]
       (assoc m
              (replace-fn k)
              (update-in v [0] (partial mapv replace-fn)))))
   {} m))

(defn replace-keys
  [m key-map]
  (replace-keys* m #(get key-map % %)))

(defn replace-ns
  [m ns replacement-ns]
  (replace-keys* m
                 (fn [k] (if (= (namespace k) (name ns))
                           (keyword (name replacement-ns) (name k))
                           k))))

(defn append-ns
  ([m ns]
   (append-ns m ns {}))
  ([m ns {:keys [include exclude only]}]
   (let [include-ns (if (seq only)
                      (set (map name only))
                      (-> (set (concat include
                                       (map namespace
                                            (concat (keys m)
                                                    (flatten (map first (vals m)))))))
                          (clojure.set/difference (set (map name exclude)))))]
     (replace-keys* m
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
