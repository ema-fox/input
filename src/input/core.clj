(ns input.core
  (:refer-clojure :exclude [read-string])
  (:require [clojure.string :as str]
            [clojure.edn :as edn :refer [read-string]]
            [clojure.pprint :refer [pprint]]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clj-http.client :as client]
            [duratom.core :refer [duratom]]
            [ring.adapter.undertow :refer [run-undertow]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.reload :refer [wrap-reload]]
            [ring.middleware.cookies :refer [wrap-cookies]]
            [ring.util.response :refer [response set-cookie redirect]]
            [reitit.ring :refer [ring-handler router routes create-resource-handler create-default-handler]]
            [hiccup.page :as p]
            [hiccup2.core :as h2]
            [hiccup.util :as hu]))

(defn now! []
  (java.util.Date.))

(def START-SCORE 1000)

(defonce yt-api-key (slurp "api-key"))

(defonce !settings
  (duratom :local-file
           :file-path "settings.edn"
           :init {}))

(defonce !last-watched (atom {}))

(defn conjs [s x]
  (conj (set s) x))

(defn set-diff [old new]
  [(apply disj old new)
   (apply disj new old)])

(defn updates [init f ks]
  (reduce (fn [m k]
            (update m k f))
          init
          ks))

(defn updates-map [m f m2]
  (reduce (fn [m [k v]]
            (update m k f v))
          m
          m2))

(defn fancy-merge [base raw]
  (let [cooked (dissoc raw :meta/update)]
    (-> base
        (merge cooked)
        (updates-map conjs (:conjs (:meta/update raw)))
        (updates-map disj (:disj (:meta/update raw))))))

(defn ingest-video [st {:yt/keys [id channel] :as entry}]
  (-> st
      (update :id->video update id fancy-merge entry)
      (cond-> channel
        (update :channel->ids update channel conjs id))
      (update :tag->ids (fn [tag->ids]
                          (let [[rem add] (set-diff (get-in st [:id->video id :tags]) (:tags entry))]
                            (-> tag->ids
                                (updates #(disj % id) rem)
                                (updates #(conjs % id) add)))))))

(defn expected-result [p1 p2]
  (let [exp (/ (- p2 p1) 400)]
    (/ 1 (inc (Math/pow 10 exp)))))

(defn game-over [scores winner loser]
  (let [k 20
        adjustment (* k (- 1 (expected-result (scores winner) (scores loser))))]
    (-> scores
        (update winner + adjustment)
        (update loser - adjustment))))

(defn game-over2 [videos winner loser]
  (let [k 20
        adjustment (* k (- 1 (expected-result (:de/score (videos winner) START-SCORE)
                                              (:de/score (videos loser) START-SCORE))))]
    (-> videos
        (update-in [winner :de/score] (fnil + START-SCORE) adjustment)
        (update-in [loser :de/score] (fnil - START-SCORE) adjustment))))

(defn from-keys [ks f]
  (into {} (map (juxt identity f)) ks))

(defn ingest-channel [st ch]
  (-> st
      (update :id->channel update (:yt/id ch) fancy-merge ch)))

(defn ingest-comparison [st cmp]
  (-> st
      (update-in [:videos :id->video] game-over2
              (:hard cmp)
              (:easy cmp))
      (update :comparisons conjs [(:by cmp) #{(:hard cmp) (:easy cmp)}])))

(defn ingest-user [st u]
 (-> st
     (update :id->user update (:id u) fancy-merge u)))

(defn ingest-tag [st t]
  (-> st
      (update :tag->info update (:tag t) fancy-merge t)))

(def ingesters
  {:video (fn [st v]
            (update st :videos ingest-video v))
   :channel (fn [st ch]
              (update st :channels ingest-channel ch))
   :comparison ingest-comparison
   :user (fn [st u]
           (update st :users ingest-user u))
   :tag (fn [st t]
          (update st :tags ingest-tag t))
   :do/make-admin (fn [st {:keys [user-id]}]
                    [{:kind :user
                                :id user-id
                                :roles (conjs (get-in st [:users :id->user user-id :roles])
                                              :admin)}])})

(defn ingest [st entry]
  (let [res ((ingesters (:kind entry)) st entry)]
    (cond (map? res)
          res

          (sequential? res)
          (reduce ingest st res)

          :else
          (throw (ex-info "ingester broken" {:return-val res})))))

(defn read-state []
  (try
    (with-open [f (java.io.PushbackReader. (io/reader "log.edn"))]
      (let [log (take-while identity (repeatedly #(edn/read {:eof nil} f)))]
        (reduce ingest nil log)))
    (catch java.io.FileNotFoundException e
      nil)))

(defonce !state (atom (read-state)))

(defn get-video-state []
  (:videos @!state))

(defn reread-state! []
  (reset! !state (read-state)))

(defn write&ingest! [st entry]
  (with-open [f (io/writer "log.edn" :append true)]
    (binding [*out* f]
      (prn entry)))
  (ingest st entry))

(defn transact! [f]
  (swap! !state (fn [st]
                  (reduce write&ingest! st (f st)))))

(defn make-entry! [kind by & {:as entry}]
  (cond-> (assoc entry :kind kind :by by)
    (not (:at entry))
    (assoc :at (now!))))

(defn log! [kind by & {:as entry}]
  (transact! (constantly [(make-entry! kind by entry)])))

(defn ensure-channel! [ch-id title]
  (transact! (fn [st]
               (when-not (get-in st [:channels :id->channel ch-id])
                 [(make-entry! :channel :system  :yt/id ch-id :yt/title title)]))))

(defn $get-video-info [yt-id]
  (-> (client/get (str "https://www.googleapis.com/youtube/v3/videos?part=snippet&key=" yt-api-key "&id=" yt-id))
      :body
      (json/read-str :key-fn (partial keyword "youtube"))
      :youtube/items
      first))

(defn get-video [yt-id]
  (get-in @!state [:videos :id->video yt-id]))

(defn $add-video-info [yt-id by]
  (when-let [info (:youtube/snippet ($get-video-info yt-id))]
    (log! :video by
          :yt/id yt-id)
    (log! :video :system
          :yt/id yt-id
          :yt/title (:youtube/title info)
          :yt/channel (:youtube/channelId info))
    (ensure-channel! (:youtube/channelId info)
                     (:youtube/channelTitle info))))

(defn $ensure-video-info [yt-id by]
  (when-not (every? (or (get-video yt-id) {}) [:yt/title :yt/channel])
    ($add-video-info yt-id by)))

(defn approved-tags-for-user [user-id]
  (let [{:keys [users tags]} @!state]
    (set (concat (get-in users [:id->user user-id :used-tags])
                 (->> (:tag->info tags)
                      (filter (comp :approved? second))
                      (map first))))))

(defn page [user-id & body]
  (response
   (p/html5 {:encoding "UTF-8" :xml? true}
            [:head
             [:title "Comprehensible Input"]
             [:link {:rel "stylesheet" :href "/asset/style.css"}]
             [:script {:src "https://unpkg.com/htmx.org@2.0.4"
                       :integirty "sha384-HGfztofotfshcF7+8n44JQL2oJmowVChPTg48S+jvZoztPfvwD79OC/LTtG6dMp+"}]]
            [:body #_{:hx-boost "true"}
             [:a {:href "/"} "home"]
             [:div
              body]])))

(defn half-compare [this-id other-id]
  [:form
   [:input {:type "hidden" :name "hard" :value this-id}]
   [:input {:type "hidden" :name "easy" :value other-id}]
   [:button {:type "submit"
             :hx-post "/compare"
             :hx-target "#compare"}

   [:img {:src (str "https://img.youtube.com/vi/" this-id "/hqdefault.jpg")}]]])

(defn video-list [vs & {:as opts}]
  (for [video vs]
    [:a {:href (cond-> (str "/watch/" (:yt/id video))
                 (:context opts) (str "?" (:context opts)))}
     [:div {:style {:display "grid"
                    :grid-template-areas "\"stack\""}}
      [:img {:src (str "https://img.youtube.com/vi/" (:yt/id video) "/hqdefault.jpg")
             :style {:grid-area  "stack" } }]
      [:div {:style {#_#_:position "absolute"
                     :grid-area  "stack"}}
       [:div {:style {:background "white"
                      :width "fit-content"
                      :margin "1ch"
                      :padding "0.5ch"
                      :border-radius "0.5ch"}}
        (str (int (:de/score video START-SCORE)))]]]
     [:h4
      (hu/escape-html (:yt/title video))]]))

(defn tags-list [tags]
  [:div.tags
   (for [tag (sort tags)]
     [:a {:href (str "/tag/" tag)} tag])])

(defn tags [yt-id user-id]
  (let [video (get-video yt-id)]
    [:div {:style {:display "flex"
                    :gap "1ch"}}
     (tags-list (filter (approved-tags-for-user user-id) (:tags video)))
     [:button {:type "submit"
               :hx-get (str "/update-tags?yt-id=" yt-id)
               :hx-target "#tags"}
      "update tags"]]))


(defn tags-form [yt-id user-id]
  (let [video (get-video yt-id)]
    [:div
     (for [tag (->> (:tags video)
                    (filter (approved-tags-for-user user-id))
                    sort)]
       [:form
        [:input {:type "hidden" :name "yt-id" :value yt-id}]
        [:input {:type "hidden" :name "tag" :value tag}]
        [:span {:hx-post "/remove-tag"
                :hx-target "#tags"}
         (hu/escape-html tag) " X"]])
     [:form
      [:datalist {:id "tags-list"}
       (for [tag (-> @!state
                     :videos
                     :tag->ids
                     keys
                     (->> (filter (approved-tags-for-user user-id)))
                     sort)]
         [:option {:value tag}])]
      [:input {:type "hidden" :name "yt-id" :value yt-id}]
      [:input {:type "text" :name "tag"
               :list "tags-list"}]
      [:button {:hx-post "/add-tag"
                :hx-target "#tags"}
       "Add tag"]]]))

(defn get-side-videos [yt-id & {:as opts}]
  (let [st (get-video-state)
        video (get-in st [:id->video yt-id])
        videos (cond (:channel opts)
                     (map (:id->video st) (get-in st [:channel->ids (:channel opts)]))

                     (:tag opts)
                     (map (:id->video st) (get-in st [:tag->ids (:tag opts)]))

                     :else
                     (vals (:id->video st)))
        reference-score (:de/score video START-SCORE)]
      (->> videos
           (remove (comp #{yt-id} :yt/id))
           (sort-by #(Math/abs (- reference-score (:de/score % START-SCORE))))
           (take 5))))

(defn channel-title [ch-id]
  (hu/escape-html (:yt/title (get-in @!state [:channels :id->channel ch-id]) ch-id)))

(defn watch [user-id yt-id side-videos & {:as opts}]
  (let [video (get-video yt-id)]
    [:div.h {:style {:display "flex"}}
     [:div {:style {:flex-grow "1"}}
      [:iframe.yt-player
       {:src (str "https://www.youtube.com/embed/" yt-id)
        :allowfullscreen true}]
      [:h2 (hu/escape-html (:yt/title video))]
      [:a {:href (str "/channel/" (:yt/channel video))}
       [:h4 (channel-title (:yt/channel video))]]
      [:div#tags
       (tags yt-id user-id)]


      (let [[lw1 lw2] (@!last-watched user-id)]
        (if (and lw1 lw2 (not (get-in @!state [:comparisons [user-id #{lw1 lw2}]])))
          [:div
           [:div#compare
            "Which did you find more difficult?"
            [:div.h
             (half-compare lw1 lw2)
             "or"
             (half-compare lw2 lw1)]]]))]
     [:div
      (video-list side-videos opts)]]))

(defn get-channel-videos [ch-id]
  (let [st (get-video-state)]
    (->> (get (:channel->ids st) ch-id)
         (map (:id->video st))
         (sort-by :de/score))))

(defn channel [ch-id]
  (video-list (get-channel-videos ch-id)
              :context (str "channel=" ch-id)))

(defn tag [tag]
  (let [st (get-video-state)]
    (video-list (->> (get (:tag->ids st) tag)
                     (map (:id->video st))
                     (sort-by :de/score))
                :context (str "tag=" tag))))

(defn admin?
  ([user-id]
   (admin? @!state user-id))
  ([st user-id]
   (get-in st [:users :id->user user-id :roles :admin])))

(defn stats [user-id]
   (let [st (get-video-state)]
    [:div
      (hu/escape-html
       (with-out-str
         (pprint user-id)))
     [:h4 "videos by number of tags"]
     [:div (for [v (->> (vals (:id->video st))
                        (sort-by (comp count :tags)))]
             [:div (count (:tags v))
              " "
              [:a {:href (str "/watch/" (:yt/id v))}
               (hu/escape-html (:yt/title v))]])]
     [:h4 "channels by number of videos"]
     [:div (for [[ch n]  (-> (:channel->ids st)
                             (update-vals count)
                             (->> (sort-by second)))]
             [:div n
              " "
              [:a {:href (str "/channel/" ch)}
               (channel-title ch)]])]
     (when (admin? @!state user-id)
       [:div.admin
        [:h4 "tags by number of videos"]
        (for [[t n] (-> (:tag->ids st)
                             (update-vals count)
                             (->> (sort-by second)))]
               [:div.h
                (if (get-in @!state [:tags :tag->info t :approved?])
                  " "
                  [:form
                   [:input {:type "hidden" :name "tag" :value t}]
                   [:button {:hx-post "/approve-tag"}
                    "approve!"]])
                n
                [:a {:href (str "/tag/" t)}
                 (hu/escape-html t)]])])
     #_
     [:div {:style {:white-space "pre"}}
      (hu/escape-html
       (with-out-str
         (pprint (-> (:tag->ids st)
                     (update-vals count)
                     (->> (sort-by second))))))]]))

(defn front-page [user-id]
  (let [st (get-video-state)]
    [:div
     [:form {:method "POST" :action "/add"}
      [:input {:type "text" :name "url" :placeholder "https://www.youtube.com/watch?v=..."}]
      [:button "Add video"]]
     (tags-list (filter (approved-tags-for-user user-id) (keys (:tag->ids st))))
     [:div {:style {:display "grid"
                    :gap "1em"
                    :grid-template-columns "repeat(auto-fill, 500px)"}}
      (video-list (sort-by :de/score (vals (:id->video st))))]]))

(defn wrap-user-id [handler]
  (fn [req]
    (if-let [user-id (some-> (:value (get (:cookies req) "user-id"))
                             parse-uuid)]
      (handler (assoc req :user-id user-id))
      (let [user-id (random-uuid)]
        (-> (handler (assoc req :user-id user-id))
            (set-cookie "user-id" (str user-id)
                        {:max-age (* 60 60 24 365)}))))))

(def handler
  (-> (ring-handler
       (router
        ["/"
         [""
          {:get (fn [{:keys [user-id]}]
                  (page user-id (front-page user-id)))}]
         ["stats"
          {:get (fn [{:keys [user-id]}]
                  (page user-id (stats user-id)))}]
         ["add"
          {:post (fn [{:keys [user-id params]}]
                   (let [url (:url params)
                         yt-id (second (re-find #"v=([a-zA-Z0-9_-]{11})" url))]
                     (if yt-id
                       (do
                         ($ensure-video-info yt-id user-id)
                         (redirect (str "/watch/" yt-id) :see-other))
                       ;; TODO error message
                       (redirect "/" :see-other))))}]
         ["compare"
          {:post (fn [{:keys [user-id params] :as x}]
                   (log! :comparison user-id :easy (:easy params) :hard (:hard params))
                   (response (str (h2/html [:div "thank you for your help."]))))}]
         ["update-tags"
          {:get (fn [{:keys [user-id params] :as x}]
                  (response (str (h2/html (tags-form (:yt-id params) user-id)))))
           #_#_:post (fn [{:keys [user-id params]}]
                   (log! :video user-id :yt/id (:yt-id params) :tags (set (str/split (str/trim (:tags params)) #"\s+")))
                   (response (str (h2/html (tags (:yt-id params))))))}]
         ["add-tag"
          {:post (fn [{:keys [user-id params]}]
                   (let [yt-id (:yt-id params)]
                     (transact! (fn [st]
                                  [(make-entry! :video user-id :yt/id yt-id
                                                :tags (conjs (get-in st [:videos :id->video yt-id :tags]) (:tag params)))
                                   ;; TODO only make entry when tag is new in used-tags
                                   (make-entry! :user user-id :id user-id
                                                :used-tags (conjs (get-in st [:user :id->user user-id :used-tags]) (:tag params)))]))
                     (response (str (h2/html (tags (:yt-id params) user-id))))))}]
         ["remove-tag"
          {:post (fn [{:keys [user-id params]}]
                   (let [yt-id (:yt-id params)]
                     (transact! (fn [st]
                                  [(make-entry! :video user-id :yt/id yt-id
                                                :tags (disj (get-in st [:videos :id->video yt-id :tags]) (:tag params)))]))
                     (response (str (h2/html (tags (:yt-id params) user-id))))))}]
         ["approve-tag"
          {:post (fn [{:keys [user-id params]}]
                   (when (admin? user-id)
                     (log! :tag user-id :tag (:tag params) :approved? true))
                   (response ""))}]
         ["watch/:yt-id"
          {:get (fn [{:keys [user-id path-params params] :as a}]
                  (let [yt-id (:yt-id path-params)]
                    (swap! !last-watched update user-id (comp (partial take 2) distinct conj) yt-id)
                    (page user-id (watch user-id yt-id (get-side-videos yt-id
                                                                        :channel (:channel params)
                                                                        :tag (:tag params))
                                         (cond
                                           (:channel params) {:context (str "channel=" (:channel params))}
                                           (:tag params) {:context (str "tag=" (:tag params))})))))}]
         ["channel/:ch-id"
          {:get  (fn [{:keys [user-id path-params]}]
                   (page user-id (channel (:ch-id path-params))))}]
         ["tag/:tag"
          {:get  (fn [{:keys [user-id path-params]}]
                   (page user-id (tag (:tag path-params))))}]
         ["asset/*"
          (create-resource-handler)]
      ])
       (routes
        (create-default-handler)))
      wrap-user-id
      wrap-keyword-params
      wrap-params
      wrap-cookies))

#_
(defn remove! [id]
  (swap! !videos #(into (empty %) (remove (comp #{id} :yt/id)) %)))

(defn -main [& args]
  (run-undertow (wrap-reload #'handler) {:host "0.0.0.0" :port (or (some-> (first args) Long.) 8080)}))
