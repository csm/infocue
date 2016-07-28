(ns infocue.core
  (:require [hickory.core :refer :all]
            [hickory.select :as s]
            [clj-http.client :as http]
            [clojure.java.io :as io]
            [clojure.string :refer [join split]])
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
    (map #(->> % :content join)
         (filter #(nil? (-> % :attrs :src)) all-scripts))))

(def user-agent
  "The fake user agent we use"
  "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_11_6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/51.0.2704.103 Safari/537.36")

(defn fetch-image
  [url]
  (let [outfile (str "image-" (last (split url #"/")))]
    (println "fetching" url "to" outfile "...")
    (with-open [in (:body (http/get url {:as :stream}))
                out (io/output-stream outfile)]
      (io/copy in out))
    outfile))

(defn make-slide-video
  [n img time]
  (println "Making" time "second video of image" img)
  (let [proc (.exec (Runtime/getRuntime) (str "ffmpeg -framerate 1/" time " -i " img " -c:v libx264 -pix_fmt yuv420p slide-" n ".mp4"))
        ret (.waitFor proc)]
    (if (= 0 ret)
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
        proc (.exec (Runtime/getRuntime)
                    (str "ffmpeg -f concat -i " video-list " -c copy slides.mp4"))
        ret (.waitFor proc)]
    (if (= 0 ret)
      "slides.mp4"
      nil)))

(defn fetch-video
  [url referrer cookies]
  (let [outfile (str "video." (last (split url #"\.")))]
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

(defn make-presentation-video
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
                  outfile (str (last (split url #"/")) ".mp4")
                  proc (.exec (Runtime/getRuntime)
                              (str "ffmpeg -i " slides-video " -i " video " -filter_complex \"[1]scale=iw/8:ih/8 [pip]; [0][pip] overlay=main_w-overlay_w:main_h-overlay_h\" " outfile))
                  ret (.waitFor proc)]
              (if (zero? ret)
                (println "Wrote video to" outfile)
                (println "Failed to compose video"))))))

(defn -main
  "Run it. TODO: add argument parsing and options. But not now."
  [& args]
  (if-let [[url] args]
    (make-presentation-video url)
    (println "Usage: infocue.core <url>")))
