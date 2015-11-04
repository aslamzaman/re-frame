(ns re-frame.router
  (:require [reagent.impl.batching :refer [do-later]]
            [re-frame.handlers :refer [handle]]
            [re-frame.utils :refer [error]]
            [goog.async.nextTick]))


;; -- Router Loop ------------------------------------------------------------
;;
;; Conceptually, the task is to process events in a perpetual loop, one after
;; the other, FIFO, calling the right event-handler for each, being idle when
;; there are no events, and firing up when one arrives, etc. The processing
;; of an event happens "asynchronously" sometime after the event is
;; dispatched.
;;
;; In practice, browsers only have a single thread of control and we must be
;; careful to not hog the CPU. When processing events one after another, we
;; must hand back control to the browser regularly, so it can redraw, process
;; websockets, etc. But not too regularly! If we are in a de-focused browser
;; tab, our app will be CPU throttled. Each time we get back control, we have
;; to process all queued events, or else something like a bursty websocket
;; (producing events) might overwhelm the queue. So there's a balance.
;;
;; The original implementation of this router loop used core.async. It was
;; fairly simple, and it mostly worked, but it did not give enough
;; control. So now we hand-roll our own, finite-state-machine and all.
;;
;; The strategy is this:
;;   - maintain a queue of `dispatched` events.
;;   - when a new event arrives, "schedule" processing of this queue using
;;     goog.async.nextTick, which means it will happen "very soon".
;;   - when processing events, do ALL the ones currently queued. Don't stop.
;;     Don't yield to the browser. Hog that CPU.
;;   - but if any new events arrive during this cycle of processing, don't do
;;     them immediately. Leave them queued. Yield first to the browser, and
;;     do these new events in the next processing cycle. That way we drain
;;     the queue up to a point, but we never hog the CPU forever. In
;;     particular, we handle the case where handling one event will beget
;;     another event. The freshly begat event will be handled next cycle,
;;     with yielding in between.
;;   - In some cases, an event should not be run until after the GUI has been
;;     updated, i.e., after the next reagent animation frame. In such a case,
;;     the event should be dispatched with :flush-dom metadata like this:
;;       (dispatch ^:flush-dom [:event-id other params])
;;     Such an event will temporarily block all further processing because
;;     events are processed sequentially: we handle each event before we
;;     handle the ones behind it.
;;
;; Implementation
;;   - queue processing can be in a number of states: scheduled, running, paused
;;     etc. So it is modeled explicitly as a FSM.
;;     See "-fsm-trigger" (below) for the states and transitions.
;;   - the scheduling is done via "goog.async.nextTick" which is pretty quick
;;   - when the event has :dom-flush we schedule via "reagent.impl.batching.doLater"
;;     which will run event processing after the next reagent animation frame.
;;


;; A map from event metadata keys to the corresponding "run later" functions
(def later-fns
  {:flush-dom do-later              ;; after next annimation frame
   :yield     goog.async.nextTick}) ;; almost immediately

(defprotocol IEventQueue
  (enqueue [this event])

  ;; Finite State Machine transitions
  (-fsm-trigger [this trigger arg])

  ;; Finite State Machine (FSM) actions
  (-add-event [this event])
  (-process-1st-event [this])
  (-run-next-tick [this])
  (-run-queue [this])
  (-pause-run [this later-fn])
  (-exception [this ex])
  (-begin-resume [this]))


;; Want to understand this? Look at FSM in -fsm-trigger?
(deftype EventQueue [^:mutable fsm-state ^:mutable queue]
  IEventQueue

  (enqueue [this event]
    (-fsm-trigger this :add-event event))

  ;; Finite State Machine "Actions"
  (-add-event
    [this event]
    (set! queue (conj queue event)))

  (-process-1st-event
    [this]
    (let [event-v (peek queue)]
      (try
        (handle event-v)
        (catch :default ex
          (-fsm-trigger this :exception ex)))
      (set! queue (pop queue))))

  (-run-next-tick
    [this]
    (goog.async.nextTick #(-fsm-trigger this :begin-run nil)))

  (-exception
    [_ ex]
    (set! queue #queue [])     ;; purge the queue
    (throw ex))

  ;; Process all the events currently in the queue, but not any new ones.
  ;; Be aware that events might have metadata which will pause processing.
  (-run-queue
    [this]
    (let [queue-length (count queue)]
      (loop [n queue-length]
        (if (zero? n)
          (-fsm-trigger this :finish-run nil)
          (let [event-v (peek queue)]
            (if-let [later-fn (some later-fns (keys (meta event-v)))]
              (-fsm-trigger this :pause-run later-fn)
              (do (-process-1st-event this)
                  (recur (dec n)))))))))

  (-pause-run
    [this later-fn]
    (later-fn #(-fsm-trigger this :begin-resume nil)))

  (-begin-resume
    [this]
    (-process-1st-event this)               ;; do the event which paused processing
    (-fsm-trigger this :finish-resume nil)) ;; do the rest of the queued events

  (-fsm-trigger
    [this trigger arg]

    ;; work out new FSM state and action function for the transition
    (let [[new-state action-fn]
          (case [fsm-state trigger]

            ;; Here is the FSM
            ;; [current-state trigger] [new-state action-fn]

            ;; the queue is idle
            [:quiescent :add-event] [:scheduled #(do (-add-event this arg)
                                                     (-run-next-tick this))]

            ;; processing has been already been scheduled to run in the future
            [:scheduled :add-event] [:scheduled #(-add-event this arg)]
            [:scheduled :begin-run] [:running   #(-run-queue this)]

            ;; processing one event after another
            [:running :add-event ] [:running   #(-add-event this arg)]
            [:running :pause-run ] [:paused    #(-pause-run this arg)]
            [:running :exception ] [:quiescent #(-exception this arg)]
            [:running :finish-run] (if (empty? queue)       ;; FSM guard
                                     [:quiescent]
                                     [:scheduled #(-run-next-tick this)])

            ;; event processing is paused - probably by :flush-dom metadata
            [:paused :add-event   ] [:paused   #(-add-event this arg)]
            [:paused :begin-resume] [:resuming #(-begin-resume this)]

            ;; processing the event that caused the queue to be paused
            [:resuming :add-event    ] [:resuming  #(-add-event this arg)]
            [:resuming :exception    ] [:quiescent #(-exception this arg)]
            [:resuming :finish-resume] [:running   #(-run-queue this)]

            (throw (str "re-frame: state transition not found. " fsm-state " " trigger)))]

      ;;  change state and run the action fucntion
      (set! fsm-state new-state)
      (when action-fn (action-fn)))))

;; ---------------------------------------------------------------------------
;; This is the global queue for events
;; When an event is dispatched, it is put into this queue.  Later the queue
;; will "run" and the event will be "handled" by the registered event handler.
;;

(def event-queue (->EventQueue :quiescent #queue []))


;; ---------------------------------------------------------------------------
;; Dispatching
;;

(defn dispatch
  "Queue an event to be processed by the registered handler.

  Usage example:
     (dispatch [:delete-item 42])"
  [event-v]
  (if (nil? event-v)
    (error "re-frame: \"dispatch\" is ignoring a nil event.")
    (enqueue event-queue event-v))
  nil)                                                      ;; Ensure nil return. See https://github.com/Day8/re-frame/wiki/Beware-Returning-False


(defn dispatch-sync
  "Send an event to be processed by the registered handler
  immediately. Note: dispatch-sync may not be called while another
  event is being handled.

  Usage example:
     (dispatch-sync [:delete-item 42])"
  [event-v]
  (handle event-v)
  nil)                                                      ;; Ensure nil return. See https://github.com/Day8/re-frame/wiki/Beware-Returning-False

