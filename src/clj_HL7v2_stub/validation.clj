(ns clj-hl7v2-stub.validation
  (:require [clojure.string :as str])
  (:import [ca.uhn.hl7v2.util Terser]))

(defn normalize-path
  "Convert dot notation to Terser path format. 
   PID.3.1 -> /PID-3-1
   PID-3-1 -> /PID-3-1
   /PID-3-1 -> /PID-3-1"
  [path]
  (cond
    (str/starts-with? path "/") path
    (str/includes? path ".") (str "/" (str/replace path "." "-"))
    :else (str "/" path)))

(defn get-field-value
  "Extract field value from message using Terser path"
  [msg path]
  (try
    (let [terser (Terser. msg)
          normalized-path (normalize-path path)]
      (.get terser normalized-path))
    (catch Exception _
      nil)))

(defn field-matches?
  "Check if a field value matches the expected value/pattern"
  [actual expected]
  (cond
    ;; Exact match
    (string? expected) (= actual expected)
    
    ;; Regex match
    (instance? java.util.regex.Pattern expected) 
    (and actual (re-matches expected actual))
    
    ;; Predicate function
    (fn? expected) (expected actual)
    
    ;; Check for existence
    (= expected :exists) (not (str/blank? actual))
    
    ;; Check for nil/empty
    (nil? expected) (str/blank? actual)
    
    :else false))

(defn validate-message
  "Validate an HL7 message against field expectations.
   Returns {:valid? true/false :errors [...]} 
   
   Example:
   (validate-message msg {:PID.3.1 \"12345\"
                         :PV1.2 :exists
                         :PID.5.1 #\"SMITH.*\"})"
  [msg validations]
  (let [errors (reduce-kv
                (fn [errs path expected]
                  (let [path-str (name path)
                        actual (get-field-value msg path-str)]
                    (if (field-matches? actual expected)
                      errs
                      (conj errs {:path path-str
                                  :expected expected
                                  :actual actual}))))
                []
                validations)]
    {:valid? (empty? errors)
     :errors errors}))

(defn validation-handler
  "Create a handler that validates before executing the wrapped handler.
   Throws on validation failure."
  [validations handler isolation-mode?]
  (fn [msg]
    (let [{:keys [valid? errors]} (validate-message msg validations)]
      (if valid?
        (handler msg)
        (throw (ex-info "Message validation failed"
                        {:validations validations
                         :errors errors}))))))

(defn with-validation
  "Wrap a handler with validation logic.
   Example:
   {\"ADT^A01\" (with-validation {:PID.3.1 \"12345\"} 
                                 (fn [msg] (create-ack msg \"AA\")))}"
  ([validations handler]
   (with-validation validations handler false))
  ([validations handler isolation-mode?]
   (validation-handler validations handler isolation-mode?)))