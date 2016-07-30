(ns infocue.core
  (:require [hickory.core :refer [as-hickory parse]]
            [hickory.select :as s]
            [clj-http.client :as http]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as string]
            [clojure.tools.cli :refer [parse-opts]])
  (:import [javax.script ScriptEngineManager ScriptException])
  (:gen-class))

(def ^:dynamic *config*
  {:width 1280
   :height 720
   :scratch "scratch"
   :output "out.mp4"
   :user-agent "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_11_6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/51.0.2704.103 Safari/537.36"})

(defn- not-nil? [x] (not (nil? x)))

(defn sh
  [& args]
  (println "running command: " (string/join " " args))
  (apply shell/sh args))

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

(defn parse-exif-value
  "Try parsing a exiftool string value as a int, double, duration, or
   date. Otherwise, just return the string."
  [s]
  (condp re-matches (string/trim s)
    #"^[0-9]+$" (Long/parseLong (string/trim s))
    #"^[0-9]*\.[0-9]+([eE][-+]?[0-9]+)?" (Double/parseDouble s)
    #"^[0-9]+:[0-9]{2}:[0-9]{2}$" (duration->secs s)
    #"^[0-9]+[ ]*s$" (Long/parseLong (string/trim (first (string/split s #"s"))))
    (string/trim s)))

(defn get-exif-info
  "Parse information about a video file, returning a map of useful
   information about the video. Should at least contain fields
   :image-width, :image-height, and :duration."
  [f]
  (let [out (sh "exiftool" f)
        lines (string/split (:out out) #"\n")]
    (into {}
          (map (fn [[k v]] [(keywordize k) (parse-exif-value v)])
               (map #(string/split % #":" 2) lines)))))

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
  (let [outfile (str (:scratch *config*)
                     "/image-" (last (string/split url #"/")))]
    (when (not (.exists (io/file outfile)))
      (println "fetching" url "to" outfile "...")
      (with-open [in (:body (http/get url {:as :stream}))
                  out (io/output-stream outfile)]
        (io/copy in out)))
    outfile))

(defn to-even
  [v]
  (if (even? v)
    v
    (inc v)))

(defn make-slide-video
  [n img time]
  (println "Making" time "second video of image" img)
  (let [outfile (str (:scratch *config*) "/slide-" n ".mp4")]
    (if (.exists (io/file outfile))
      outfile
      (let [ ; some slideshows are .swf (╯°□°）╯︵ ┻━┻
            img (if (.endsWith img ".swf")
                  (let [pngout (string/replace img #"\.swf$" ".png")
                        ret (sh "swfrender" img "-o" pngout)]
                    (if (zero? (:exit ret))
                      pngout
                      (throw (Exception. (str "Failed to convert SWF to PNG. Fuck it. Message: " (:err ret))))))
                  img)
            info (get-exif-info img)
            width (to-even (:image-width info))
            height (to-even (:image-height info))
            ret (sh "ffmpeg" "-y" "-loglevel" "quiet"
                    "-framerate" (str "1/" time)
                    "-i" img "-c:v" "libx264"
                    "-pix_fmt" "yuv420p" "-r" "30"
                    "-s" (str width "x" height)
                    outfile)]
        (if (zero? (:exit ret))
          outfile
          (throw (Exception. (str "error making slide video: "
                                  (:err ret)))))))))

(defn make-slides-video
  [[urls times]]
  (let [outfile (str (:scratch *config*) "/slides.mp4")
        images (doall (map fetch-image urls))
        durations (conj
                   (into [] (map #(- (second %) (first %))
                                 (partition 2 1 times)))
                   1)
        videos (map-indexed (fn [n [i t]] (make-slide-video n i t))
                            (map vector images durations))
        video-list (str (:scratch *config*) "/slide-list.txt")
        _ (with-open [out (io/writer video-list)]
                     (doall (for [v videos]
                              (.write out (str "file '" (last (string/split v #"/")) "'\n")))))
        ret (sh "ffmpeg" "-y" "-loglevel" "quiet"
                "-f" "concat" "-i" video-list "-c" "copy" outfile)]
    (if (zero? (:exit ret))
      outfile
      nil)))

(defn fetch-video
  [url referrer cookies]
  (let [outfile (str (:scratch *config*)
                     "/video." (last (string/split url #"\.")))]
    (println "Saving" url "to" outfile "...")
    ;(println "Cookies are" (str cookies))
    (try
      (when (not (.exists (io/file outfile)))
        (with-open [in (:body
                        (http/get url {:headers {"Referer" referrer}
                                       :cookies cookies
                                       :client-params {"http.useragent"
                                                       user-agent}
                                       :as :stream}))
                    out (io/output-stream outfile)]
          (io/copy in out)))
      outfile
      (catch Exception _ nil))))

(defn compute-filter
  "Create an ffmpeg filter that will combine the slides video
   and the speaker video. The slides will be left-padded and fit
   to height h, and the speaker video will appear in the upper
   left corner, over the padding area."
  [w h slide-info video-info]
  (let [slide-height (:image-height slide-info)
        slide-width (int (* (:image-width slide-info) (/ h slide-height)))
        slide-padding (- w slide-width)
        ; if the slides are 16/9, make sure we have our speaker
        ; visible at all. Note, we don't handle wider slides than
        ; 16/9 yet. Oops!
        ; Give the speaker a 1/8 width postage stamp to live in.
        video-width (max slide-padding (/ w 8))
        video-height (int (* (:image-height video-info) (/ video-width (:image-width video-info))))]
    (str "[1] scale=" video-width ":" video-height " [a];"
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
  (when (not (.exists (io/file (:scratch *config*))))
    (.mkdirs (io/file (:scratch *config*))))
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
                  _ (when (nil? video)
                      (throw (Exception. "Failed to download video.")))
                  slides-video (make-slides-video slides)
                  _ (when (nil? slides-video)
                      (throw (Exception. "Failed to create slides video.")))
                  video-info (get-exif-info video)
                  slides-info (get-exif-info slides-video)
                  outfile (str (last (string/split url #"/")) ".mp4")
                  ret (sh "ffmpeg" "-i" slides-video
                          "-i" video
                          "-s" (str (:width *config*) "x" (:height *config*))
                          "-loglevel" "quiet"
                          "-y"
                          "-filter_complex"
                          (compute-filter (:width *config*)
                                          (:height *config*)
                                          slides-info video-info)
                          outfile)]
              (if (zero? (:exit ret))
                (println "Wrote video to" outfile)
                (println "Failed to compose video, error:" (:err ret)))
              ;; (when (not (:keep *config*))
              ;;   (doseq [f (.listFiles (io/file (:scratch *config*)))]
              ;;     (io/delete-file f)))
              ))))

(def cli-opts
  [["-w" "--width W" "Video width."
    :default 1280
    :parse-fn #(Integer/parseInt %)
    :validate [#(> 0 %) "Must be positive"]]
   ["-h" "--height H" "Video height."
    :default 720
    :parse-fn #(Integer/parseInt %)
    :validate [#(> 0 %) "Must be positive"]]
   ["-s" "--scratch DIR" "Use this as scratch directory."]
   ["-o" "--output" "Set the output file, default uses the name in the URL."]
   ["-k" "--keep" "Leave intermediate files when done."]
   ["-a" "--user-agent STR" "Use custom user-agent string (default pretends to be Chrome on macOS)."]
   ["-?" "--help" "Show this help."]])

(defn -main
  "Run it."
  [& args]
  (let [options (parse-opts args cli-opts)]
    (when (-> options :options :help)
      (println "Usage: infocue.core.main [options] URL")
      (println)
      (println (:summary options))
      (System/exit 0))
    (when (not (empty? (:errors options)))
      (for [e (:errors options)]
        (println e))
      (System/exit 1))
    (if-let [[url] (:arguments options)]
      (with-bindings {#'*config* (merge *config*
                                        {:scratch (str (last (string/split url #"/")) ".temp")
                                         :output (str (last (string/split url #"/")) ".mp4")}
                                        (:options options))}
        (try
          (make-presentation-video url)
          (catch Exception e
            (println "Error:" (.getMessage e)))))
      (println "Usage: infocue.core <url>"))))
