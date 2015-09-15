(ns afterglow.beyond
  "Provides the ability to communicate with Pangolin's Beyond laser
  show software, including synchronizing it with Afterglow's BPM and
  beat grid, and triggering cues. This initial implementation assumes
  that sending small UDP datagrams is fast enough that it can be done
  on the caller's thread. If this turns out not to be true, it can be
  changed to use a core.async channel the way that ola-clojure does."
  (:require [afterglow.controllers :as controllers]
            [afterglow.effects :as fx]
            [afterglow.effects.color :as color]
            [afterglow.effects.params :as params]
            [afterglow.rhythm :as rhythm]
            [afterglow.show :as show]
            [afterglow.show-context :refer [with-show]]
            [com.evocomputing.colors :as colors]
            [overtone.at-at :as at-at :refer [now]]
            [taoensso.timbre :as timbre])
  (:import [java.net InetAddress DatagramPacket DatagramSocket]
           [afterglow.effects Effect Assigner]))

(defonce ^{:private true
           :doc "The socket used for sending UDP packets to Beyond."}
  send-socket (DatagramSocket.))

(defonce ^{:doc "Used to give each server a unique ID, for registering
  its metronome BPM watcher with a show, as well as looking up the
  server from its assigner target IDs."
           :private true}
  server-counter
  (atom 0))

(defn- empty-buffer
  "Empty the frame buffer of the specified server connection in
  preparation for generating a new frame of the light show."
  [server]
  (dosync
   (ref-set (:last-frame server) @(:frame-buffer server))
   (ref-set (:frame-buffer server) {})))

(defn send-command
  "Sends a PangoScript command to the specified Beyond Talk server."
  [server command]
  (let [payload (str command \return \newline)
        packet (DatagramPacket. (.getBytes payload) (.length payload) (:address server) (:port server))]
    (.send send-socket packet)))

(defn set-color-slider-to-normal
  "Sets Beyond's Live Control color slider to allow the affected cue
  to show its normal colors."
  [server]
  (send-command server "ColorSlider 0"))

(defn set-color-slider-from-hue
  "Sets Beyond's Live Control color slider to a value that causes the
  affected cue to have the same hue as the supplied color value."
  [server color]
  (send-command server (str  "ColorSlider " (+ 25 (int (* 198.0 (/ (colors/hue color) 360.0)))))))

(defn set-color-slider-to-white
  "Sets Beyond's Live Control color slider to cause the affected cue
  to be drawn in white."
  [server]
  (send-command server "ColorSlider 255"))

(defn- send-buffer
  "Update the associated Beyond server with any differences between
  what has been generated during this frame of the Afterglow light
  show and the previous one."
  [server]
  (when (not= (:beyond-color @(:frame-buffer server)) (:beyond-color @(:last-frame server)))
    (if-let [new-color (:beyond-color @(:frame-buffer server))]
      (if (> (colors/saturation new-color) 1.0)
        (set-color-slider-from-hue server new-color)
        (set-color-slider-to-white server))
      (set-color-slider-to-normal server)))
  ;; TODO: Add support for cues
  )

(defn beyond-server
  "Creates a representation of the UDP PangoScript server running in
  Beyond at the specified address and port. The value returned can
  then be used with the other functions in this namespace to interact
  with that laser show. If a server has already been created for this
  combination of address and port, it is simply returned."
  [address port]
  (let [server {:id (swap! server-counter inc)
                :address (InetAddress/getByName address)
                :port port
                :synced-show (ref nil)
                :frame-buffer (ref nil)
                :empty-fn (ref nil)
                :send-fn (ref nil)
                :last-frame (ref nil)
                :task (ref nil)}]
    (dosync
     (ref-set (:empty-fn server) #(empty-buffer server))
     (ref-set (:send-fn server) #(send-buffer server)))
    server))

(defn resync-to-beat
  "Schedules a message to the laser show being run by the specified
  Beyond server to let it know when the beat after next takes place in
  its synchronized show. Has no effect if the server is not synced."
  [server]
  (when-let [show @(:synced-show server)]
    (at-at/at (long (rhythm/metro-beat (:metronome show) (inc (rhythm/metro-beat (:metronome show)))))
              #(send-command server "BeatResync") controllers/pool :desc "Resync Beyond's beat grid")))

(defn- metronome-watch-key
  "Create a key with which to uniquely identify this server as a
  registered bpm watcher of a show metronome."
  [server]
  (keyword (str "afterglow.beyond." (:id server))))

(defn bind-to-show
  "Causes the BPM of the laser show being run by the specified Beyond
  Talk server to be synced with the main metronome of the specified
  show. When sync is started, the beats will be synced as well, and
  they will be resynced periodically to correct for potential drift.
  The default resync interval is ten seconds, but it can be set to a
  different value by passing a resync interval, in milliseconds, as a
  third argument.

  Also hooks this Beyond server instance into the supplied show's
  rendering loop, so that the laser-related effects and assigners
  provided by this namespace can participate, even though they do not
  result in DMX values being sent to the show universes.

  To undo a binding established by this function, simply call it again
  to bind to another show, or to `nil` to unbind entirely."
  {:doc/format :markdown}
  ([server show]
   (bind-to-show server show 10000))
  ([server show resync-interval]
   (dosync
    (when-let [former-show @(:synced-show server)]
      (rhythm/metro-remove-bpm-watch (:metronome former-show) (metronome-watch-key server))
      (with-show former-show
        (show/clear-empty-buffer-fn! @(:empty-fn server))
        (show/clear-send-buffer-fn! @(:send-fn server))))
    (ref-set (:synced-show server) show)
    (ref-set (:frame-buffer server) nil)
    (ref-set (:last-frame server) nil)
    (when show
      (send-command server (str "SetBpm " (rhythm/metro-bpm (:metronome show))))
      (rhythm/metro-add-bpm-watch (:metronome show) (metronome-watch-key server)
                                  (fn [_ _ _ bpm]
                                    (send-command server (str "SetBpm " bpm))))
      (with-show show
        (show/add-empty-buffer-fn! @(:empty-fn server))
        (show/add-send-buffer-fn! @(:send-fn server))))
    (alter (:task server) (fn [former-task]
                            (when former-task (at-at/stop former-task))
                            (when show (at-at/every resync-interval #(resync-to-beat server) controllers/pool
                                                    :desc "Resync Beyond's beat grid")))))))

(defn laser-color-effect
  "An effect which sets the Beyond color slider to match the hue of
  the color parameter passed in, and sets it back to normal when
  ended."
  [server color]
  (Effect. "Laser Hue"
           fx/always-active
           (fn [show snapshot]
             (let [resolved (params/resolve-unless-frame-dynamic color show snapshot)]
               [(Assigner. :beyond-color (keyword (str "s" (:id server))) server
                            (fn [show snapshot target previous-assignment] resolved))]))
           fx/end-immediately))

;; Tell Afterglow about our assigners and the order in which they should be run.
(show/set-extension-resolution-order! :afterglow.beyond [:beyond-color])

;; Set up the resolution handler for the laser color assigner.
(defmethod fx/resolve-assignment :beyond-color [assignment show snapshot _]
  ;; Resolve in case assignment is still frame dynamic
  (let [target (:target assignment)
        resolved (params/resolve-param (:value assignment) show snapshot target)]
    (dosync
     (commute (:frame-buffer target) assoc :beyond-color resolved))))

;; Add fade blending support for laser color assignments
(defmethod fx/fade-between-assignments :beyond-color [from-assignment to-assignment fraction show snapshot]
  (cond (<= fraction 0) from-assignment
        (>= fraction 1) to-assignment
        :else (merge from-assignment {:value (color/fade-colors (:value from-assignment) (:value to-assignment)
                                                                fraction show snapshot (:target from-assignment))})))
