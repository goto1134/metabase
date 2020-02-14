(ns metabase.query-processor.middleware.resolve-referenced
  (:require [metabase.models.card :refer [Card]]
            [metabase.query-processor.middleware
             [resolve-fields :as qp.resolve-fields]
             [resolve-source-table :as qp.resolve-tables]]
            [metabase.util.i18n :refer [tru]]
            [schema.core :as s]
            [toucan.db :as db]
            [weavejester.dependency :as dep])
  (:import clojure.lang.ExceptionInfo))

(defn tags-referenced-cards
  "Returns Card instances referenced by the given native `query`."
  [query]
  (->> (get-in query [:native :template-tags])
       (keep (comp :card val))
       (mapv #(db/select-one 'Card :id %))))

(defn- check-query-database-id=
  [query database-id]
  (when-not (= (:database query) database-id)
    (throw (ex-info "Referenced query is from a different database"
                    {:referenced-query     query
                     :expected-database-id database-id}))))

(s/defn ^:private resolve-referenced-card-resources* :- clojure.lang.IPersistentMap
  [query]
  (doseq [referenced-card (tags-referenced-cards query)
          :let [referenced-query (:dataset_query referenced-card)]]
    (check-query-database-id= referenced-query (:database query))
    (qp.resolve-tables/resolve-source-tables* referenced-query)
    (qp.resolve-fields/resolve-fields* referenced-query))
  query)

(defn- query->template-tags
  [query]
  (vals (get-in query [:native :template-tags])))

(defn- query->tag-card-ids
  [query]
  (keep :card (query->template-tags query)))

(defn- card-subquery-graph
  ([card-id]
   (card-subquery-graph (dep/graph) card-id))
  ([graph card-id]
   (let [card-query (db/select-one-field :dataset_query Card :id card-id)]
     (reduce
      (fn [g sub-card-id]
        (card-subquery-graph (dep/depend g card-id sub-card-id)
                        sub-card-id))
      graph
      (query->tag-card-ids card-query)))))

(defn- circular-ref-error
  [from-card to-card]
  (tru
   "Your query includes circular referencing sub-queries between cards \"{0}\" and \"{1}\"."
   (db/select-one-field :name Card :id from-card)
   (db/select-one-field :name Card :id to-card)))

(defn- check-for-circular-references
  [query]
  ;; `card-subquery-graph` will throw if there are circular references
  (try
   (reduce card-subquery-graph (dep/graph) (query->tag-card-ids query))
   (catch ExceptionInfo e
     (let [{:keys [reason node dependency]} (ex-data e)]
       (if (= reason :weavejester.dependency/circular-dependency)
         (throw (ex-info (circular-ref-error node dependency) {:original-exception e}))
         (throw e)))))
  query)

(defn resolve-referenced-card-resources
  "Resolves tables and fields referenced in card query template tags."
  [qp]
  (comp qp resolve-referenced-card-resources* check-for-circular-references))