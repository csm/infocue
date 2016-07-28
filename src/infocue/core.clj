(ns infocue.core
  (:require [hickory.core :refer [as-hickory parse]]
            [hickory.select :as s]
            [clj-http.client :as http]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as string])
  (:import [javax.script ScriptEngineManager ScriptException])
  (:gen-class))

(defn js-engine
  []
  (if-let [engine (.getEngineByName (ScriptEngineManager.) "JavaScript")]
    engine
    (throw (Exception. "no JavaScript engine found. Sorry"))))

(defn run-video-js
  "Run the string s as a JavaScript script, trying to see if it
   sets up the video URL. Returns the video URL on success, or nil
   on error."
  [s]
  (let [engine (js-engine)
        script (str "(function() {\n"
                    "  var P = {};\n"
                    s
                    "\n  return P.s;\n})();")]
    (try
      (.eval engine script)
      (catch ScriptException _ nil))))

(defn run-slides-js
  "Run the string s as a JavaScript script, trying to see if it
   sets up the slide and timing arrays. Returns the two arrays
   (the first the slide URLs, the second the slide timings)."
  [s]
  (let [engine (js-engine)
        script (str "(function() {\n"
                    s "\n"
                    "  return [slides, TIMES];\n"
                    "})();")]
    (try
      (map vals (vals (.eval engine script)))
      (catch ScriptException _ nil))))

(defn run-cloudfront-js
  "Run the string as a JavaScript script, attepmting to fetch
   the CloudFront parameters. Returns nil if the script doesn't
   produce any CloudFront params. Otherwise returns a map that can
   be passed as cookies to a new request."
  [s]
  (let [engine (js-engine)
        script (str "(function() {\n"
                    s
                    "  return {\"CloudFront-Policy\": InfoQConstants.scp, \"CloudFront-Signature\": InfoQConstants.scs, \"CloudFront-Key-Pair-Id\": InfoQConstants.sck};\n"
                    "})()")]
    (try
      (into {} (for [[k v] (.eval engine script)] {k {:value v}}))
      (catch ScriptException _ nil))))

(defn fetch-inline-scripts
  "Parse page as HTML, and finding any <script> tags that don't have
   any src attribute. Returns the contents of each <script> tag as
   a seq of strings."
  [page]
  (let [tree (-> page
                 :body
                 parse
                 as-hickory)
        all-scripts (s/select (s/child (s/tag :script)) tree)]
    (map #(->> % :content string/join)
         (filter #(nil? (-> % :attrs :src)) all-scripts))))

(def user-agent
  "The fake user agent we use"
  "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_11_6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/51.0.2704.103 Safari/537.36")

(defn fetch-image
  [url]
  (let [outfile (str "image-" (last (string/split url #"/")))]
    (println "fetching" url "to" outfile "...")
    (with-open [in (:body (http/get url {:as :stream}))
                out (io/output-stream outfile)]
      (io/copy in out))
    outfile))

(defn make-slide-video
  [n img time]
  (println "Making" time "second video of image" img)
  (let [ret (sh "ffmpeg" "-y" "-loglevel" "quiet"
                "-framerate" (str "1/" time)
                "-i" img "-c:v" "libx264"
                "-pix_fmt" "yuv420p" "-r" "30"
                (str "slide-" n ".mp4"))]
    (if (zero? (:exit ret))
      (str "slide-" n ".mp4")
      nil)))

(defn make-slides-video
  [[urls times]]
  (let [images (doall (map fetch-image urls))
        durations (conj
                   (into [] (map #(- (second %) (first %))
                                 (partition 2 1 times)))
                   1)
        videos (map-indexed (fn [n [i t]] (make-slide-video n i t))
                            (map vector images durations))
        video-list "slide-list.txt"
        _ (with-open [out (io/writer video-list)]
                     (doall (for [v videos]
                              (.write out (str "file '" v "'\n")))))
        ret (sh "ffmpeg" "-y" "-loglevel" "-quiet"
                "-f" "concat" "-i" video-list "-c" "copy" "slides.mp4")]
    (if (zero? (:exit ret))
      "slides.mp4"
      nil)))

(defn fetch-video
  [url referrer cookies]
  (let [outfile (str "video." (last (string/split url #"\.")))]
    (println "Saving" url "to" outfile "...")
    ;(println "Cookies are" (str cookies))
    (try
      (with-open [in (:body
                      (http/get url {:headers {"Referer" referrer}
                                     :cookies cookies
                                     :client-params {"http.useragent"
                                                     user-agent}
                                     :as :stream}))
                  out (io/output-stream outfile)]
        (io/copy in out))
      outfile
      (catch Exception _ nil))))

(defn- not-nil? [x] (not (nil? x)))

(defn keywordize
  [s]
  (-> (string/trim s)
      (.toLowerCase)
      (string/replace #"[ ]+" "-")
      keyword))

(defn duration->secs
  "Parse HH:MM:SS string into seconds."
  [d]
  (let [[h m s] (string/split (string/trim d) #":")]
    (+ (* (Integer/parseInt h) 3600)
       (* (Integer/parseInt m) 60)
       (Integer/parseInt s))))

(defn parse-video-value
  "Try parsing a exiftool string value as a int, double, duration, or
   date. Otherwise, just return the string."
  [s]
  (condp re-matches (string/trim s)
    #"^[0-9]+$" (Long/parseLong (string/trim s))
    #"^[0-9]*\.[0-9]+([eE][-+]?[0-9]+)?" (Double/parseDouble s)
    #"^[0-9]+:[0-9]{2}:[0-9]{2}$" (duration->secs s)
    #"^[0-9]+[ ]*s$" (Long/parseLong (string/trim (first (string/split s #"s"))))
    (string/trim s)))

(defn get-video-info
  "Parse information about a video file, returning a map of useful
   information about the video. Should at least contain fields
   :image-width, :image-height, and :duration."
  [f]
  (let [out (sh "exiftool" f)
        lines (string/split (:out out) #"\n")]
    (into {}
          (map (fn [[k v]] [(keywordize k) (parse-video-value v)])
               (map #(string/split % #":" 2) lines)))))

(defn compute-filter
  "Create an ffmpeg filter that will combine the slides video
   and the speaker video. The slides will be left-padded and fit
   to height h, and the speaker video will appear in the upper
   left corner, over the padding area."
  [w h slide-info video-info]
  (let [slide-height (:image-height slide-info)
        slide-width (int (* (:image-width slide-info) (/ h slide-height)))
        slide-padding (- w slide-width)
        video-height (int (* (:image-height video-info) (/ slide-padding (:image-width video-info))))]
    (str "[1] scale=" slide-padding ":" video-height " [a];"
         "[0] scale=" slide-width ":" h ","
         " pad=" w ":" h ":" slide-padding ":" 0 " [b];"
         "[b][a] overlay=0:0")))

(defn make-presentation-video
  "Fetch an InfoQ presentation (speaker video & slide deck) from
   url, and create a video that combines the speaker video and slides.
   The output video is 1280x720; the slides are scaled to fit the height
   and are right-aligned; the speaker video is squished into the upper
   left corner."
  [url]
  (let [page (http/get url {:client-params
                            {"http.useragent" user-agent}})
        scripts (fetch-inline-scripts page)
        video-url (first (filter not-nil? (map run-video-js scripts)))
        slides (first (filter not-nil? (map run-slides-js scripts)))
        cf-cookies (first (filter not-nil? (map run-cloudfront-js scripts)))]
    (cond
      (nil? video-url) (println "Failed to find video URL.")
      (nil? slides) (println "Failed to find slides URLs/times.")
      (nil? cf-cookies) (println "Failed to find CloudFront cookies.")
      :else (let [video (fetch-video video-url url (into {} [cf-cookies (:cookies page)]))
                  slides-video (make-slides-video slides)
                  video-info (get-video-info video)
                  slides-info (get-video-info video)
                  outfile (str (last (string/split url #"/")) ".mp4")
                  ret (sh "ffmpeg" "-i" slides-video
                          "-i" video
                          "-s" "1280x720"
                          "-loglevel" "quiet"
                          "-filter_complex"
                          (compute-filter 1280 720 slides-info video-info)
                          outfile)]
              (if (zero? (:exit ret))
                (println "Wrote video to" outfile)
                (println "Failed to compose video"))))))

(defn -main
  "Run it. TODO: add argument parsing and options. But not now."
  [& args]
  (if-let [[url] args]
    (make-presentation-video url)
    (println "Usage: infocue.core <url>")))
