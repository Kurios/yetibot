(ns yetibot.api.jira
  (:require
    [taoensso.timbre :refer [info warn error]]
    [clojure.string :as s]
    [clj-http.client :as client]
    [clojure.core.memoize :as memo]
    [yetibot.core.config :refer [config-for-ns conf-valid?]]
    [yetibot.core.util.http :refer [get-json fetch]]))

(def config (config-for-ns))
(def configured? (conf-valid?))
(defn project-keys [] (->> config :project-keys))
(defn project-keys-str [] (->> (project-keys) (s/join ",")))

(def ^:private base-uri (str "https://" (:domain config)))
(def ^:private api-uri (str base-uri "/rest/api/latest"))
(def ^:private auth (map config [:user :password]))
(def ^:private client-opts {:as :json
                            :basic-auth auth
                            :throw-entire-message? true
                            :insecure? true})
(def ^:private error-handling-opts {:coerce :always
                                    :throw-exceptions false})

(defn endpoint [& fmt-with-args]
  (str api-uri (apply format fmt-with-args)))

;; helpers

(defn GET [& fmt-with-args] (client/get (apply endpoint fmt-with-args) client-opts))

;; formatters

(defn url-from-key [k]
  (str base-uri "/browse/" k))

(defn format-issue [issue-data]
  (let [fs (:fields issue-data)]
    [(-> fs :summary)
     (str "Assignee: " (-> fs :assignee :displayName))
     (str "Status: " (-> fs :status :name))
     (url-from-key (:key issue-data))]))

(defn format-issue-short [issue-data]
  (let [fs (:fields issue-data)]
    (format "%s: %s %s"
            (-> fs :status :name)
            (-> fs :summary)
            (url-from-key (:key issue-data)))))

;; issues

(defn issue-create-meta [] (GET "/issue/createmeta"))

(defn get-transitions [i]
  (client/get (endpoint "/issue/%s/transitions?transitionId" i)
              client-opts))

(def ^:private find-resolve (partial filter #(= "Resolve Issue" (:name %))))

(defn- transition-issue [i transition-id comment]
  (let [params {:update {:comment [{:add {:body comment}}]}
                :fields {:resolution {:name "Fixed"}}
                :transition transition-id}]
    (client/post
      (endpoint "/issue/%s/transitions?transitionId" i)
      (merge client-opts
             {:form-params params :content-type :json}))))

(defn resolve-issue
  "Transition an issue to the resolved state. If it is unable to make that
   transition nil will be returned"
  [i comment]
  (let [ts (:body (get-transitions i))
        resolve-t (find-resolve (:transitions ts))]
    (when-let [t (first resolve-t)]
      (transition-issue i (:id t) comment))))

(defn get-issue
  "Fetch json for a given JIRA"
  [i]
  (let [uri (endpoint "/issue/%s" i)]
    (try
      (:body (client/get uri client-opts))
      (catch Exception _ nil))))

(def fetch-and-format-issue-short (comp format-issue-short get-issue))

(defn find-project [pk]
  (try
    (:body (client/get (endpoint "/project/%s" pk) client-opts))
    (catch Exception _
      nil)))

(defn priorities []
  (:body (client/get (endpoint "/priority") client-opts)))

(defn find-priority-by-key [k]
  (let [kp (re-pattern (str "(?i)" k))]
    (first (filter #(re-find kp (:name %)) (priorities)))))

(defn issue-types []
  (:body (client/get (endpoint "/issuetype") client-opts)))

(defn update-issue
  [issue-key {:keys [summary component-ids assignee priority-key desc timetracking]}]
  (let [pri-id (if priority-key (:id (find-priority-by-key priority-key)))
        params {:fields
                (merge
                  {}
                  (when summary {:summary summary})
                  (when assignee {:assignee assignee})
                  (when component-ids {:components (map #(hash-map :id %) component-ids)})
                  (when desc {:description desc})
                  (when timetracking {:timetracking timetracking})
                  (when pri-id {:priority {:id pri-id}}))}]
    (client/put
      (endpoint "/issue/%s" issue-key)
      (merge client-opts
             {:coerce :always
              :throw-exceptions false
              :form-params params
              :content-type :json}))))


(defn create-issue
  "This thing is a beast"
  [{:keys [summary component-ids assignee priority-key desc project-key
           timetracking issue-type-id parent]
    :or {desc "" assignee "-1"
         issue-type-id (if parent
                         (:sub-task-issue-type-id config)
                         (:default-issue-type-id config))
         project-key (first (:project-keys config))}}]
  (if-let [prj (find-project project-key)]
    (if-let [priority (if priority-key
                        (find-priority-by-key priority-key)
                        (first (priorities)))]
      (let [pri-id (:id priority)
            prj-id (:id prj)
            params {:fields
                    (merge {:assignee {:name assignee}
                            :project {:id prj-id}
                            :summary summary
                            :components (map #(hash-map :id %) component-ids)
                            :description desc
                            :issuetype {:id issue-type-id}
                            :priority {:id pri-id}}
                           (when timetracking {:timetracking timetracking})
                           (when parent {:parent {:id parent}}))}]
        (client/post
          (endpoint "/issue")
          (merge client-opts
                 {:coerce :always
                  :throw-exceptions false
                  :form-params params
                  :content-type :json})))
      (warn "Could not find a priority for key " priority-key))
    (warn "Could not find project" project-key)))

(defn assign-issue
  [issue-key assignee]
  (client/put
    (endpoint "/issue/%s/assignee" issue-key)
    (merge client-opts
           ; error-handling-opts
           {:content-type :json
            :form-params {:name assignee}})))

;; components

(defn component [project-key]
  (client/get
    (endpoint "/project/%s/components" project-key)
    client-opts))

(def all-components
  (memo/ttl #(map component (project-keys))
            :ttl/threshold 3600000))

(defn find-component-like
  "Match components across all projects"
  [pattern-str]
  (let [re (re-pattern (str "(?i)" pattern-str))]
    (filter #(re-find re (:name %)) (mapcat :body (all-components)))))

;; users

(defn get-users []
  (:body
    (client/get
      (endpoint "/user/assignable/multiProjectSearch")
      (merge client-opts
             {:query-params
              {"projectKeys" (project-keys-str)}}))))


;; search

(defn- projects-jql [] (str "(project in (" (project-keys-str) "))"))

(defn search [jql]
  (info "JQL search" jql)
  (client/get
    (endpoint "/search")
    (merge client-opts
           {:coerce :always
            :throw-exceptions false
            :query-params
            {:jql jql
             :startAt 0
             :maxResults 5
             :fields ["summary" "status" "assignee"]}})))

(defn search-in-projects [jql]
  (search (str (projects-jql) " AND (" jql ")")))

(defn search-by-query [query]
  (search-in-projects
    (str
      "(summary ~ \"" query "\" OR description ~ \"" query
      "\" OR comment ~ \"" query "\")")))

(defn recent [] (search (projects-jql)))

;; prime cache

(future (all-components))
