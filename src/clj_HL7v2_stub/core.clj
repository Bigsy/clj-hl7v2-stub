(ns clj-hl7v2-stub.core
  (:require [clj-hl7v2-stub.mock-connection :as mock]
            [clj-hl7v2-stub.validation :as validate])
  (:import [ca.uhn.hl7v2 DefaultHapiContext HapiContext]))

;; Re-export commonly used functions
(def create-ack mock/create-ack)
(def validate-message validate/validate-message)
(def with-validation validate/with-validation)

(def ^:dynamic *mock-context* nil)
(def ^:dynamic *call-counts* nil)

(defn get-context
  "Get the appropriate context - mock if available, otherwise default"
  []
  (or *mock-context* (DefaultHapiContext.)))

(defn normalize-handlers [handlers]
  (reduce-kv
    (fn [acc pattern handler-or-config]
      (cond
        (fn? handler-or-config)
        (assoc acc pattern {:handler handler-or-config})
        
        (map? handler-or-config)
        (let [times (:times handler-or-config)
              handler (:handler handler-or-config)
              validations (:validate handler-or-config)]
          (if handler
            (assoc acc pattern {:handler handler 
                                :times times 
                                :validate validations})
            acc))
        
        :else acc))
    {}
    handlers))

(defn track-call [pattern]
  (when *call-counts*
    (swap! *call-counts* update pattern (fnil inc 0))))

(defn validate-call-counts [handlers]
  (when *call-counts*
    (doseq [[pattern config] handlers]
      (when-let [expected-times (:times config)]
        (let [actual-times (get @*call-counts* pattern 0)]
          (when (not= actual-times expected-times)
            (throw (ex-info "Call count mismatch"
                            {:pattern pattern
                             :expected expected-times
                             :actual actual-times}))))))))

(defn create-tracking-handler [pattern handler validations isolation-mode?]
  (let [validated-handler (if validations
                           (validate/validation-handler validations handler isolation-mode?)
                           handler)]
    (fn [message]
      (track-call pattern)
      (validated-handler message))))

(defn setup-message-handlers [handlers isolation-mode?]
  (let [normalized-handlers (normalize-handlers handlers)
        wrapped-handlers (reduce-kv
                         (fn [acc pattern config]
                           (if-let [handler (:handler config)]
                             (let [validations (:validate config)]
                               (assoc acc pattern 
                                      (create-tracking-handler pattern handler validations isolation-mode?)))
                             acc))
                         {}
                         normalized-handlers)]
    wrapped-handlers))

(defmacro with-hl7-stub
  [handlers & body]
  `(let [message-handlers# (setup-message-handlers ~handlers false)
         handlers-atom# (atom {:handlers message-handlers# 
                               :isolation-mode false})
         mock-context# (mock/create-mock-context handlers-atom#)]
     (binding [*mock-context* mock-context#
               *call-counts* (atom {})]
       (try
         (let [result# (do ~@body)]
           (validate-call-counts (normalize-handlers ~handlers))
           result#)
         (finally
           nil)))))

(defmacro with-hl7-stub-in-isolation
  [handlers & body]
  `(let [message-handlers# (setup-message-handlers ~handlers true)
         handlers-atom# (atom {:handlers message-handlers# 
                               :isolation-mode true})
         mock-context# (mock/create-mock-context handlers-atom#)]
     (binding [*mock-context* mock-context#
               *call-counts* (atom {})]
       (try
         (let [result# (do ~@body)]
           (validate-call-counts (normalize-handlers ~handlers))
           result#)
         (finally
           nil)))))

(defmacro with-global-hl7-stub
  [handlers & body]
  `(with-hl7-stub ~handlers ~@body))

(defmacro with-global-hl7-stub-in-isolation
  [handlers & body]
  `(with-hl7-stub-in-isolation ~handlers ~@body))