(ns input.core
  (:refer-clojure :exclude [read-string])
  (:require [clojure.string :as str]
            [clojure.set :as set]
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
            [ring.util.response :refer [response set-cookie redirect] :as response]
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

(defn orf [& xs]
  (some identity xs))

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

(defn bifurcate-by [p xs]
  (let [{as true bs false} (group-by (comp boolean p) xs)]
    [as bs]))

(defn tree-merge [a b]
  (cond (map? b) (merge-with tree-merge a b)
        (set? b) (set/union a b)
        :else b))

(defn merge-heterogenous [fs a b]
  (reduce-kv (fn [m k v]
               (if-let [f (fs k)]
                 (update m k f v)
                 (assoc m k v)))
             a
             b))

(defn soc [m k v]
  (if (nil? v)
    (dissoc m k)
    (assoc m k v)))

(defn index-by
  ([coll keyfn] (index-by coll keyfn identity))
  ([coll keyfn valfn]
   (into {} (map (juxt keyfn valfn)) coll)))

(defn from-keys [ks f]
  (index-by ks identity f))

(defn tree-remove [a b]
  (cond (map? b) (not-empty (reduce-kv (fn [a* k v]
                                         (soc a* k (tree-remove (get a k) v)))
                                       a
                                       b))
        (set? b) (not-empty (set/difference a b))
        :else nil))

(defn tree-difference [a b]
  (cond (= a b) nil
        (map? b) (not-empty (reduce-kv (fn [a* k v]
                                         (soc a* k (tree-difference (get a k) v)))
                                       a
                                       b))
        (set? b) (not-empty (set/difference a b))
        :else a))

(defn simplify-tree-diff [{:keys [add rem]}]
  {:add (tree-difference add rem)
   :rem (tree-difference rem add)})

(defn ingest-video* [{:yt/keys [id channel] :keys [lang tags] :as entry}]
  {:videos
   {:id->video {id entry}
    :lang-> {lang {:channel->ids {channel #{id}}
                   :tag->ids (from-keys tags (constantly #{id}))}}}})

(defn ingest-video [st entry]
  (let [old (get-in st [:videos :id->video (:yt/id entry)])
        new (merge old entry)]
    {:add (ingest-video* new)
     :rem (ingest-video* old)}))

(defn merge-watch-time [a b]
  (merge-heterogenous {:seconds + :ended orf} a b))

(defn update-watch-log [log {:keys [yt-id user-id] :as entry}]
  (if (= yt-id (:yt-id (peek log)))
    (update log (dec (count log))
            #(merge-heterogenous {:seconds + :ended orf} % entry))
    ((fnil conj []) log entry)))

(defn ingest-watch-time [state {:keys [yt-id user-id seconds ended] :as entry}]
  (-> state
      (update-in [:users :id->user user-id :watch-log :lang->
                  (get-in state [:videos :id->video yt-id :lang])]
                 update-watch-log entry)
      (update-in [:videos :id->video yt-id] (partial merge-with +)
                 {:de/watch-seconds seconds
                  :de/times-finished (if ended 1 0)})))

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
        (update-in [winner :de/comparisons] (fnil inc 0))
        (update-in [loser :de/comparisons] (fnil inc 0))
        (update-in [winner :de/score] (fnil + START-SCORE) adjustment)
        (update-in [loser :de/score] (fnil - START-SCORE) adjustment))))

(defn from-keys [ks f]
  (into {} (map (juxt identity f)) ks))

(defn ingest-channel [st ch]
  (-> st
      (update :id->channel update (:yt/id ch) merge ch)))

(defn ingest-comparison [st cmp]
  (-> st
      (update-in [:videos :id->video] game-over2
              (:hard cmp)
              (:easy cmp))
      (update :comparisons conjs [(:by cmp) #{(:hard cmp) (:easy cmp)}])))

(defn ingest-user [st u]
 (-> st
     (update :id->user update (:id u) merge u)))

(defn ingest-tag [st t]
  (-> st
      (update :tag->info update (:tag t) merge t)))

(defn get-videos [{:keys [lang videos]}]
  (->> (get-in videos [:lang-> lang :channel->ids])
       vals
       (apply concat)
       (map (:id->video videos))
       (sort-by #(:de/score % START-SCORE))))

(defn get-level-range [state]
  (let [videos (->> (get-videos state)
                    (filter #(<= 4 (:de/comparisons % 0))))]
    {:slow (:de/score (first videos))
     :hard (:de/score (last videos))}))

(def ingesters
  {:video (fn [st v]
            (let [{:keys [add rem]} (simplify-tree-diff (ingest-video st v))]
              (-> st
                  (tree-remove rem)
                  (tree-merge add))))
   :channel (fn [st ch]
              (update st :channels ingest-channel ch))
   :comparison ingest-comparison
   :user (fn [st u]
           (update st :users ingest-user u))
   :tag (fn [st t]
          (update st :tags ingest-tag t))
   :watch-time ingest-watch-time
   :user-tag-settings (fn [st {:keys [user-id tag influence] :as uts}]
                        [(-> (get-in st [:users :id->user user-id]
                                     {:kind :user
                                      :id user-id})
                             (update :tags-shown-on-card
                                     (if (:show-on-card? uts)
                                       conjs
                                       disj)
                                     tag)
                             (update :tags-prioritize
                                     (if (= :prioritize influence)
                                       conjs
                                       disj)
                                     tag)
                             (update :tags-hide
                                     (if (= :hide influence)
                                       conjs
                                       disj)
                                     tag)
                             (assoc-in [:tag-settings tag] uts))])
   :judgement (fn [st {:keys [user-id yt-id judgement]}]
                (let [{:keys [lang de/score]} (get-in st [:videos :id->video yt-id])
                      {:keys [lang->levels]} (get-in st [:users :id->user user-id])]
                  [{:kind :user
                    :id user-id
                    :lang->levels (update lang->levels lang
                                          (fn [levels]
                                            (case judgement
                                              :slow (merge-with max {:slow score} levels)
                                              :hard (merge-with min {:hard score} levels)
                                              :good (->> levels
                                                         (merge-with min {:good-low score})
                                                         (merge-with max {:good-high score})))))}]))
   :do/reset-level (fn [st {:keys [user-id]}]
                     (let [{:keys [lang lang->levels]} (get-in st [:users :id->user user-id])]
                       [{:kind :user
                         :id user-id
                         :lang->levels (assoc lang->levels lang (get-level-range (assoc st :lang lang)))}]))
   :do/make-admin (fn [st {:keys [user-id]}]
                    [{:kind :user
                      :id user-id
                      :roles (conjs (get-in st [:users :id->user user-id :roles])
                                    :admin)}])})

(defn ingest [st entry]
  (let [ingester (or (ingesters (:kind entry))
                     (fn [_ entry]
                       (throw (ex-info "no ingester found" {:entry entry}))))
        res (ingester st entry)]
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

(defn $add-video-info [yt-id by]
  (when-let [info (:youtube/snippet ($get-video-info yt-id))]
    (let [lang (:youtube/defaultAudioLanguage info)]
      (log! :video by
            :yt/id yt-id)
      (apply log! :video :system
             :yt/id yt-id
             :yt/title (:youtube/title info)
             :yt/channel (:youtube/channelId info)
             :lang (some-> lang (subs 0 2) keyword)
             (when-let [accent-tag (case lang
                                     "es-MX" "accent-mexico"
                                     "es-ES" "accent-spain"
                                     nil)]
               [:tags #{accent-tag}]))
      (ensure-channel! (:youtube/channelId info)
                       (:youtube/channelTitle info)))))

(defn $ensure-video-info [yt-id by]
  (when-not (every? (or (get-in @!state [:videos :id->video yt-id]) {}) [:yt/title :yt/channel])
    ($add-video-info yt-id by)))

(defn approved-tags-for-user [{:keys [user tags]}]
  (set (concat (:used-tags user)
               (->> (:tag->info tags)
                    (filter (comp :approved? second))
                    (map first)))))

(def languages
  [[:es "Spanish"]
   [:hi "Hindi"]
   [:de "German"]
   [:fr "French"]
   [:fi "Finnish"]
   [:en "English"]])

(def lang-kw->name (into {} languages))

(defn seconds->hours-minutes [seconds]
  (let [minutes (int (/ seconds 60))
        hours (int (/ minutes 60))
        rest-minutes (rem minutes 60)]
    (format "%d:%02d" hours rest-minutes)))

(defn admin?
  ([{:keys [user]}]
   (get-in user [:roles :admin])))

(defn page [{:keys [user lang]} & body]
  (response
   (p/html5 {:encoding "UTF-8" :xml? true}
            [:head
             [:title "Comprehensible Input"]
             [:link {:rel "stylesheet" :href "/asset/style.css?1"}]
             [:link {:rel "icon" :href "/asset/favicon.png"}]
             [:script {:src "https://unpkg.com/htmx.org@2.0.4"
                       :integirty "sha384-HGfztofotfshcF7+8n44JQL2oJmowVChPTg48S+jvZoztPfvwD79OC/LTtG6dMp+"}]]
            [:body #_{:hx-boost "true"}
             [:div.header
              [:a {:href "/"} "home"]
              [:form
               [:select {:name "lang"
                         :hx-swap "none"
                         :hx-post "/change-user-lang"}
                (for [[short long] languages]
                  [:option {:value (name short)
                            :selected (= short lang)}
                   long])]]
              [:a {:href "/progress"}
               "progress "
               (seconds->hours-minutes (->> (get-in user [:watch-log :lang-> lang])
                                            (map :seconds)
                                            (apply +)))]
              [:form.h {:method "POST" :action "/add"}
               [:input {:type "text" :name "url" :placeholder "https://www.youtube.com/watch?v=..."}]
               [:button "Add video"]]]
             [:div
              body]
             [:div.footer "Please send feedback and questions to "
              [:a {:href "mailto:ema@mailbox.org"}
               "ema@mailbox.org"]]])))

(defn page2 [handler state & args]
  ;; TODO: time handler and inject into page
  (page state (apply handler state args)))

(defn half-compare [this-id other-id]
  [:form
   [:input {:type "hidden" :name "hard" :value this-id}]
   [:input {:type "hidden" :name "easy" :value other-id}]
   [:button {:type "submit"
             :hx-post "/compare"
             :hx-target "#compare"}

   [:img {:src (str "https://img.youtube.com/vi/" this-id "/hqdefault.jpg")}]]])

(defn tags-list-inert [tags]
  [:div.tags
   (for [tag (sort tags)]
     [:div tag])])

(defn video-card [{:keys [user] :as state} opts video]
  [:a.video-link {:class (when (some (set (:tags-prioritize user)) (:tags video))
                           [:prioritized])
                  :href (cond-> (str "/watch/" (:yt/id video))
                          (:context opts) (str "?" (:context opts)))}
   [:div {:style {:display "grid"
                  :grid-template-areas "\"stack\""}}
    [:img {:src (str "https://img.youtube.com/vi/" (:yt/id video) "/hqdefault.jpg")
           :style {:grid-area  "stack" } }]
    [:div {:style {#_#_:position "absolute"
                   :grid-area  "stack"}}
     [:div.h {:style {:background "white"
                    :width "fit-content"
                    :margin "1ch"
                    :padding "0.5ch"
                    :border-radius "0.5ch"}}
      (str (int (:de/score video START-SCORE)))
      (when (admin? state)
        [:div.admin (str (:de/comparisons video)
                         " "
                         (seconds->hours-minutes (:de/watch-seconds video 0))
                         " "
                         (:de/times-finished video))])]]]
   [:div
    (hu/escape-html (:yt/title video))]
   (tags-list-inert (filter (set (:tags-shown-on-card user)) (:tags video)))])

(defn video-list [{:keys [user] :as state} vs & {:as opts}]
  (let [[as bs] (->> vs
                     (remove #(some (set (:tags-hide user)) (:tags %)))
                     (bifurcate-by #(some (set (:tags-prioritize user)) (:tags %))))]
    (list (map (partial video-card state opts) as)
          (map (partial video-card state opts) bs))))

(defn tags-list [tags]
  [:div.tags
   (for [tag (sort tags)]
     [:a {:href (str "/tag/" tag)} tag])])

(defn tags [{:keys [videos] :as state} yt-id]
  (let [video (get-in videos [:id->video yt-id])]
    [:div {:style {:display "flex"
                    :gap "1ch"}}
     (tags-list (filter (approved-tags-for-user state) (:tags video)))
     [:button {:type "submit"
               :hx-get (str "/update-tags?yt-id=" yt-id)
               :hx-target "#tags"}
      "update tags"]]))

(defn tags-form [state yt-id]
  (let [video (get-in state [:videos :id->video yt-id])]
    [:div
     (for [tag (->> (:tags video)
                    (filter (approved-tags-for-user state))
                    sort)]
       [:form
        [:input {:type "hidden" :name "yt-id" :value yt-id}]
        [:input {:type "hidden" :name "tag" :value tag}]
        [:div.remove {:hx-post "/remove-tag"
                :hx-target "#tags"}
         "remove "
         [:span.tag
          (hu/escape-html tag)]]])
     [:form.h
      [:datalist {:id "tags-list"}
       (for [tag (-> state
                     :videos
                     :lang->
                     (get (:lang state))
                     :tag->ids
                     keys
                     (->> (filter (approved-tags-for-user state)))
                     sort)]
         [:option {:value tag}])]
      [:input {:type "hidden" :name "yt-id" :value yt-id}]
      [:input {:type "text" :name "tag"
               :list "tags-list"}]
      [:button {:hx-post "/add-tag"
                :hx-target "#tags"}
       "Add tag"]]]))

(defn get-channel-videos [{:keys [lang videos]} ch-id]
  (->> (get-in videos [:lang-> lang :channel->ids ch-id])
       (map (:id->video videos))
       (sort-by :de/score)))

(defn get-tag-videos [{:keys [lang videos]} tag]
  (->> (get-in videos [:lang-> lang :tag->ids tag])
       (map (:id->video videos))
       (sort-by :de/score)))

(defn sort-from-score [reference-score videos]
  (sort-by #(Math/abs (- reference-score (:de/score % START-SCORE))) videos))

(defn get-side-videos [{:keys [videos] :as state} yt-id & {:as opts :keys [user channel tag]}]
  (let [video (get-in videos [:id->video yt-id])]
    (->> (cond channel (get-channel-videos state channel)
               tag (get-tag-videos state tag)
               :else (get-videos state))
         (remove (comp #{yt-id} :yt/id))
         (sort-from-score (:de/score video START-SCORE))
         (take 5))))

(defn watch-log [{:keys [videos user]}]
  [:div
   (for [[lang watch-log] (-> user :watch-log :lang->)]
     [:div
      [:h2 (lang-kw->name lang)]
      [:table
       (for [entry watch-log]
         [:tr
          [:td (:at entry)]
          [:td (-> (:seconds entry)
                   seconds->hours-minutes)]
          [:td [:a {:href (str "/watch/" (:yt-id entry))}
                (get-in videos [:id->video (:yt-id entry) :yt/title])]]])]])])

(defn channel-title [state ch-id]
  (hu/escape-html (:yt/title (get-in state [:channels :id->channel ch-id]) ch-id)))

(defn comparison-box [{:keys [videos comparisons user]}]
  (let [user-id (:id user)
        [lw1 lw2] (@!last-watched user-id)
        v1 (get-in videos [:id->video lw1])
        v2 (get-in videos [:id->video lw2])]
    (if (and lw1 lw2 (= (:lang v1) (:lang v2))
             (not (get comparisons [user-id #{lw1 lw2}])))
      [:div
       [:div#compare
        "Which did you find more difficult?"
        [:div.h.center-items
         (half-compare lw1 lw2)
         "or"
         (half-compare lw2 lw1)]]])))

(defn watch-box [yt-id]
  [:div
   [:script {:src "/asset/watch.js"}]
   [:iframe.yt-player
    {:src (str "https://www.youtube.com/embed/" yt-id "?enablejsapi=1")
     :allowfullscreen true}]])

(defn watch [state yt-id side-videos & {:as opts}]
  (let [video (get-in state [:videos :id->video yt-id])]
    [:div.watch-flex
     [:div
      (watch-box yt-id)
      [:h2 (hu/escape-html (:yt/title video))]
      [:div.h.center-items
       [:a {:href (str "/channel/" (:yt/channel video))}
        [:h4 (channel-title state (:yt/channel video))]]
       [:form
        [:input {:type "hidden" :name "yt-id" :value yt-id}]
        [:select {:name "lang"
                  :hx-swap "none"
                  :hx-post "/change-lang"}
         (for [[short long] (cons [nil "Other"] languages)]
           [:option {:value (if short
                              (name short)
                              "")
                     :selected (= short (:lang video))}
            long])]]]
      [:div (str "estimated difficulty: " (int (:de/score video START-SCORE)) " from " (:de/comparisons video 0) " comparisons")
       (when (admin? state)
         [:div.admin (str (seconds->hours-minutes (:de/watch-seconds video 0))
                          " "
                          (:de/times-finished video))])]
      [:div#tags
       (tags state yt-id)]
      (comparison-box state)]
     [:div
      (video-list state side-videos opts)]]))

(defn get-mid-video [state start end]
  (->> (get-videos state)
       (filter #(and (<= 4 (:de/comparisons % 0))
                     (< start (:de/score % START-SCORE) end)))
       (sort-from-score (* 0.5 (+ start end)))
       first))

(defn judgement-box [state action mid-vid]
  [:div.center-column
   [:div.message
    "We will show you a series of videos. Watch each until you have a general idea of how difficult it is for you to understand."]
   [:div
    (watch-box (:yt/id mid-vid))]
   [:form.judgement-buttons {:method "POST" :action action}
    [:input {:type "hidden" :name "lang" :value (:lang state)}]
    [:input {:type "hidden" :name "yt-id" :value (:yt/id mid-vid)}]
    [:button {:name "judgement" :value "slow"}
     "too slow"]
    [:button {:name "judgement" :value "good"}
     "about right"]
    [:button {:name "judgement" :value "hard"}
     "too hard"]]])

(defn find-level-start [state]
  (let [{:keys [slow hard]} (get-level-range state)]
    (judgement-box state  "/find-level-start" (get-mid-video state slow hard))))

(defn find-level-cont [{:keys [user lang] :as state}]
  (let [{:keys [slow good-low good-high hard]} (get-in user [:lang->levels lang])
        mid-vid (if good-low
                  (or (get-mid-video state slow good-low)
                      (get-mid-video state good-high hard))
                  (get-mid-video state slow hard))]
    (if mid-vid
      (judgement-box state "/find-level-cont" mid-vid)
      [:div.message.center-column
       [:div
        (str "We recomend you to watch videos between difficulty " (int (or good-low slow)) " and " (int (or good-high hard)) ".")]
       [:div
        "If a video with a lower difficulty score catches your eye do watch it, if necessary with a higher playback speed."]
       [:div
        "If a video with a higher difficulty score looks interesting to you perhaps give it a try anyway. As long as you're understanding something you're learning something."]
       [:div
        "If there are not enough videos for you to watch in the suggested range email Emanuel at "
        [:a {:href "mailto:ema@mailbox.org"}
         "ema@mailbox.org"]
        " and he will try to add more."]])))

(defn channel [state ch-id]
  (video-list state (get-channel-videos state ch-id)
              :context (str "channel=" ch-id)))

(defn user-tag-settings-form [{:keys [user]} tag]
  (let [{:keys [show-on-card? influence]} (get-in user [:tag-settings tag])]
    [:form
     [:input {:type "hidden" :name "tag" :value tag}]
     [:label
      [:input {:type "checkbox" :name "show-on-card"
               :checked show-on-card?
               :hx-trigger "change"
               :hx-post "/user-tag-settings"}]
      " show tag on video cards"]
     [:div
      [:label.block
       [:input {:type "radio" :name "influence"
                :autocomplete "off"
                :checked (= :prioritize influence)
                :value "prioritize"
                :hx-trigger "change"
                :hx-post "/user-tag-settings" }]
       " prioritize videos with this tag"]
      [:label.block
       [:input {:type "radio" :name "influence"
                :autocomplete "off"
                :checked (not influence)
                :value "none"
                :hx-trigger "change"
                :hx-post "/user-tag-settings"}]
       " default"]
      [:label.block
       [:input {:type "radio" :name "influence"
                :autocomplete "off"
                :checked (= :hide influence)
                :value "hide"
                :hx-trigger "change"
                :hx-post "/user-tag-settings"}]
       " hide videos with this tag"]]]))

(defn tag [state tag]
  [:div
   (user-tag-settings-form state tag)
   (video-list state (get-tag-videos state tag)
               :context (str "tag=" tag))])

(defn stats [{:keys [videos user] :as state}]
  [:div
   (hu/escape-html
    (with-out-str
      (pprint (:id user))))
   [:h4 "videos by number of tags"]
   [:div (for [v (->> (vals (:id->video videos))
                      (sort-by (comp count :tags)))]
           [:div (count (:tags v))
            " "
            [:a {:href (str "/watch/" (:yt/id v))}
             (hu/escape-html (:yt/title v))]])]
   (for [[lang st*] (:lang-> videos)]
     [:div
      [:h3 (pr-str lang)]
      [:h4 "channels by number of videos"]
      [:div (for [[ch n]  (-> (:channel->ids st*)
                              (update-vals count)
                              (->> (sort-by second)))]
              [:div n
               " "
               [:a {:href (str "/channel/" ch)}
                (channel-title state ch)]])]
      (when (admin? state)
        [:div.admin
         [:h4 "tags by number of videos"]
         (for [[t n] (-> (:tag->ids st*)
                         (update-vals count)
                         (->> (sort-by second)))]
           [:div.h
            (if (get-in state [:tags :tag->info t :approved?])
              " "
              [:form
               [:input {:type "hidden" :name "tag" :value t}]
               [:button {:hx-post "/approve-tag"}
                "approve!"]])
            n
            [:a {:href (str "/tag/" t)}
             (hu/escape-html t)]])])])
   #_
   [:div {:style {:white-space "pre"}}
    (hu/escape-html
     (with-out-str
       (pprint (-> (:tag->ids st)
                   (update-vals count)
                   (->> (sort-by second))))))]])

(defn front-page [{:keys [videos lang] :as state}]
  [:div
   (tags-list (->> (get-in videos [:lang-> lang :tag->ids])
                   keys
                   (filter (approved-tags-for-user state))))
   [:div {:style {:display "grid"
                  :gap "1em"
                  :grid-template-columns "repeat(auto-fill, 500px)"}}
    (video-list state (get-videos state))]])

(defn wrap-user-id [handler]
  (fn [req]
    (if-let [user-id (some-> (:value (get (:cookies req) "user-id"))
                             parse-uuid)]
      (handler (assoc req :user-id user-id))
      (let [user-id (random-uuid)]
        (-> (handler (assoc req :user-id user-id))
            (set-cookie "user-id" (str user-id)
                        {:max-age (* 60 60 24 365)}))))))

(defn get-state [user-id]
  (let [state @!state
        user (get-in state [:users :id->user user-id])]
    (assoc state :user user :lang (:lang user :es))))

(defn wrap-state [handler]
  (fn [req]
    (handler (assoc req :state (get-state (:user-id req))))))

(def handler
  (-> (ring-handler
       (router
        ["/"
         [""
          {:get (fn [{:keys [state]}]
                  (page state (front-page state)))}]
         ["stats"
          {:get (fn [{:keys [state]}]
                  (page state (stats state)))}]
         ["progress"
          {:get (fn [{:keys [state]}]
                  (page state (watch-log state)))}]
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
         ["change-user-lang"
          {:post (fn [{:keys [user-id params]}]
                   (log! :user user-id :id user-id :lang (keyword (:lang params)))
                   (-> (response "")
                       (response/header "HX-Refresh" "true")))}]
         ["change-lang"
          {:post (fn [{:keys [user-id params]}]
                   (let [yt-id (:yt-id params)]
                     (transact! (fn [st]
                                  [(make-entry! :video user-id :yt/id yt-id
                                                :lang (when (seq (:lang params))
                                                        (keyword (:lang params))))]))
                     (response "")))}]
         ["update-tags"
          {:get (fn [{:keys [state params] :as x}]
                  (response (str (h2/html (tags-form state (:yt-id params))))))}]
         ["add-tag"
          {:post (fn [{:keys [state user-id params]}]
                   (let [yt-id (:yt-id params)]
                     (transact! (fn [st]
                                  [(make-entry! :video user-id :yt/id yt-id
                                                :tags (conjs (get-in st [:videos :id->video yt-id :tags]) (:tag params)))
                                   ;; TODO only make entry when tag is new in used-tags
                                   (make-entry! :user user-id :id user-id
                                                :used-tags (conjs (get-in st [:users :id->user user-id :used-tags]) (:tag params)))]))
                     (response (str (h2/html (tags (get-state user-id) (:yt-id params)))))))}]
         ["remove-tag"
          {:post (fn [{:keys [state user-id params]}]
                   (let [yt-id (:yt-id params)]
                     (transact! (fn [st]
                                  [(make-entry! :video user-id :yt/id yt-id
                                                :tags (disj (get-in st [:videos :id->video yt-id :tags]) (:tag params)))]))
                     (response (str (h2/html (tags (get-state user-id) (:yt-id params)))))))}]
         ["approve-tag"
          {:post (fn [{:keys [state user-id params]}]
                   (when (admin? state)
                     (log! :tag user-id :tag (:tag params) :approved? true))
                   (response ""))}]
         ["watch/:yt-id"
          {:get (fn [{:keys [state user-id path-params params] :as a}]
                  (let [yt-id (:yt-id path-params)]
                    (swap! !last-watched update user-id (comp (partial take 2) distinct conj) yt-id)
                    (page state (watch state yt-id (get-side-videos state yt-id
                                                                    :channel (:channel params)
                                                                    :tag (:tag params))
                                       (cond
                                         (:channel params) {:context (str "channel=" (:channel params))}
                                         (:tag params) {:context (str "tag=" (:tag params))})))))}]
         ["find-level/:lang"
          {:get (fn [{:keys [state path-params]}]
                  (page2 find-level-start (assoc state :lang (keyword (:lang path-params)))))}]
         ["find-level-cont"
          {:get (fn [{:keys [state]}]
                  (page2 find-level-cont state))
           :post (fn [{:keys [state user-id params]}]
                   (log! :judgement user-id :user-id user-id :yt-id (:yt-id params)
                         :judgement (keyword (:judgement params)))
                   (redirect "/find-level-cont" :see-other))}]
         ["find-level-start"
          {:post (fn [{:keys [state user-id params]}]
                   (log! :user user-id :id user-id :lang (keyword (:lang params)))
                   (log! :do/reset-level user-id :user-id user-id)
                   (log! :judgement user-id :user-id user-id :yt-id (:yt-id params)
                         :judgement (keyword (:judgement params)))
                   (redirect "/find-level-cont" :see-other))}]
         ["channel/:ch-id"
          {:get  (fn [{:keys [state path-params]}]
                   (page state (channel state (:ch-id path-params))))}]
         ["tag/:tag"
          {:get  (fn [{:keys [state path-params]}]
                   (page state (tag state (:tag path-params))))}]
         ["user-tag-settings"
          {:post (fn [{:keys [state user-id params]}]
                   (log! :user-tag-settings user-id :user-id user-id
                         :tag (:tag params)
                         :show-on-card? (= "on" (:show-on-card params))
                         :influence (case (:influence params)
                                      "none" nil
                                      "prioritize" :prioritize
                                      "hide" :hide))
                   (-> (response "")
                       (response/header "HX-Refresh" "true")))}]
         ["add-watch-time"
          {:post (fn [{:keys [user-id params] :as req}]
                   (log! :watch-time user-id :user-id user-id :yt-id (:yt-id params)
                         :ended (= "true" (:ended params))
                         :seconds (Long. (:seconds params)))
                   (response ""))}]
         ["asset/*"
          (create-resource-handler)]
         ])
       (routes
        (create-default-handler)))
      wrap-state
      wrap-user-id
      wrap-keyword-params
      wrap-params
      wrap-cookies))

#_
(defn remove! [id]
  (swap! !videos #(into (empty %) (remove (comp #{id} :yt/id)) %)))

(defn -main [& args]
  (run-undertow (wrap-reload #'handler) {:host "0.0.0.0" :port (or (some-> (first args) Long.) 8080)}))
