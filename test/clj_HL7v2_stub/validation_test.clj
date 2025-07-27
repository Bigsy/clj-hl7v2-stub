(ns clj-HL7v2-stub.validation-test
  (:require [clojure.test :refer [deftest is testing]]
            [clj-HL7v2-stub.core :refer [with-hl7-stub with-hl7-stub-in-isolation 
                                         get-context create-ack validate-message 
                                         with-validation]]
            [clj-HL7v2-stub.validation :as validate])
  (:import [ca.uhn.hl7v2.util Terser]
           [ca.uhn.hl7v2.parser PipeParser]))

(def ^:private parser (PipeParser.))

(defn create-sample-adt []
  (let [msg-string (str "MSH|^~\\&|TEST_SENDER|TEST_FACILITY|TEST_RECEIVER|TEST_RECV_FAC|20240101120000||ADT^A01|12345|P|2.4\r"
                        "EVN|A01|20240101120000\r"
                        "PID|||12345||SMITH^JOHN||19800101|M\r"
                        "PV1||I")]
    (.parse parser msg-string)))

(defn- get-connection []
  (let [context (get-context)]
    (.newClient context "localhost" 8080 false)))

(defn- send-message [message]
  (let [connection (get-connection)
        initiator (.getInitiator connection)]
    (.sendAndReceive initiator message)))

(deftest test-path-normalization
  (testing "Path normalization works correctly"
    (is (= "/PID-3-1" (validate/normalize-path "PID.3.1")))
    (is (= "/PID-3-1" (validate/normalize-path "/PID-3-1")))
    (is (= "/PID-3-1" (validate/normalize-path "PID-3-1")))))

(deftest test-field-extraction
  (testing "Field extraction works with various path formats"
    (let [msg (create-sample-adt)]
      (is (= "ADT" (validate/get-field-value msg "MSH.9.1")))
      (is (= "12345" (validate/get-field-value msg "/PID-3-1")))
      (is (= "SMITH" (validate/get-field-value msg "PID.5.1")))
      (is (nil? (validate/get-field-value msg "PID.99.1"))))))

(deftest test-field-matching
  (testing "String matching"
    (is (validate/field-matches? "SMITH" "SMITH"))
    (is (not (validate/field-matches? "SMITH" "JONES"))))
  
  (testing "Regex matching"
    (is (validate/field-matches? "SMITH" #"SMITH.*"))
    (is (validate/field-matches? "SMITH123" #"SMITH.*"))
    (is (not (validate/field-matches? "JONES" #"SMITH.*"))))
  
  (testing "Predicate matching"
    (is (validate/field-matches? "12345" #(= 5 (count %))))
    (is (not (validate/field-matches? "123" #(= 5 (count %))))))
  
  (testing "Existence checking"
    (is (validate/field-matches? "value" :exists))
    (is (not (validate/field-matches? "" :exists)))
    (is (not (validate/field-matches? nil :exists))))
  
  (testing "Nil/empty checking"
    (is (validate/field-matches? "" nil))
    (is (validate/field-matches? nil nil))
    (is (not (validate/field-matches? "value" nil)))))

(deftest test-message-validation
  (testing "Message validation with various field types"
    (let [msg (create-sample-adt)]
      (testing "Valid message"
        (let [result (validate-message msg {:PID.3.1 "12345"
                                           :PID.5.1 "SMITH"
                                           :MSH.9.1 "ADT"})]
          (is (:valid? result))
          (is (empty? (:errors result)))))
      
      (testing "Invalid message"
        (let [result (validate-message msg {:PID.3.1 "WRONG"
                                           :PID.5.1 "SMITH"})]
          (is (not (:valid? result)))
          (is (= 1 (count (:errors result))))
          (is (= "PID.3.1" (-> result :errors first :path)))))
      
      (testing "Regex validation"
        (let [result (validate-message msg {:PID.5.1 #"SM.*"})]
          (is (:valid? result))))
      
      (testing "Existence validation"
        (let [result (validate-message msg {:PID.3.1 :exists
                                           :PID.99.1 nil})]
          (is (:valid? result)))))))

(deftest test-validation-in-handlers
  (testing "Basic validation in handler map"
    (let [received-msg (atom nil)]
      (with-hl7-stub
        {"ADT^A01" {:validate {:PID.3.1 "12345"}
                    :handler (fn [msg] 
                              (reset! received-msg msg)
                              (create-ack msg "AA"))}}
        (let [msg (create-sample-adt)
              response (send-message msg)]
          (is (not (nil? @received-msg)))
          (is (.contains (.encode parser response) "MSA|AA"))))))
  
  (testing "Validation failure in normal mode"
    (is (thrown? Exception
          (with-hl7-stub
            {"ADT^A01" {:validate {:PID.3.1 "WRONG"}
                        :handler (fn [msg] (create-ack msg "AA"))}}
            (let [msg (create-sample-adt)]
              (send-message msg))))))
  
  (testing "Validation failure in isolation mode"
    (is (thrown? Exception
          (with-hl7-stub-in-isolation
            {"ADT^A01" {:validate {:PID.3.1 "WRONG"}
                        :handler (fn [msg] (create-ack msg "AA"))}}
            (let [msg (create-sample-adt)]
              (send-message msg))))))
  
  (testing "Validation with with-validation helper"
    (let [received-msg (atom nil)]
      (with-hl7-stub
        {"ADT^A01" (with-validation {:PID.5.1 #"SM.*"}
                                   (fn [msg] 
                                     (reset! received-msg msg)
                                     (create-ack msg "AA")))}
        (let [msg (create-sample-adt)
              response (send-message msg)]
          (is (not (nil? @received-msg)))
          (is (.contains (.encode parser response) "MSA|AA")))))))

(deftest test-comprehensive-validation-types
  (testing "All validation types work in handler context"
    
    (testing "Exact string match validation"
      (let [received (atom false)]
        (with-hl7-stub
          {"ADT^A01" {:validate {:PID.3.1 "12345"}
                      :handler (fn [msg] 
                                (reset! received true)
                                (create-ack msg "AA"))}}
          (let [msg (create-sample-adt)]
            (send-message msg)
            (is @received)))))
    
    (testing "Regex match validation"
      (let [received (atom false)]
        (with-hl7-stub
          {"ADT^A01" {:validate {:PID.5.1 #"SMITH.*"}
                      :handler (fn [msg] 
                                (reset! received true)
                                (create-ack msg "AA"))}}
          (let [msg (create-sample-adt)]
            (send-message msg)
            (is @received)))))
    
    (testing "Predicate function validation"
      (let [received (atom false)]
        (with-hl7-stub
          {"ADT^A01" {:validate {:PID.3.1 #(= 5 (count %))}
                      :handler (fn [msg] 
                                (reset! received true)
                                (create-ack msg "AA"))}}
          (let [msg (create-sample-adt)]
            (send-message msg)
            (is @received)))))
    
    (testing "Field exists validation"
      (let [received (atom false)]
        (with-hl7-stub
          {"ADT^A01" {:validate {:PID.3.1 :exists}
                      :handler (fn [msg] 
                                (reset! received true)
                                (create-ack msg "AA"))}}
          (let [msg (create-sample-adt)]
            (send-message msg)
            (is @received)))))
    
    (testing "Field nil/empty validation"
      (let [received (atom false)]
        (with-hl7-stub
          {"ADT^A01" {:validate {:PID.99.1 nil}  ; This field doesn't exist, should be nil
                      :handler (fn [msg] 
                                (reset! received true)
                                (create-ack msg "AA"))}}
          (let [msg (create-sample-adt)]
            (send-message msg)
            (is @received)))))
    
    (testing "Multiple validation rules"
      (let [received (atom false)]
        (with-hl7-stub
          {"ADT^A01" {:validate {:PID.3.1 "12345"
                                :PID.5.1 #"SMITH.*"
                                :PV1.2 :exists
                                :PID.99.1 nil}
                      :handler (fn [msg] 
                                (reset! received true)
                                (create-ack msg "AA"))}}
          (let [msg (create-sample-adt)]
            (send-message msg)
            (is @received)))))))

(deftest test-validation-failure-scenarios
  (testing "Exact match failure"
    (is (thrown? Exception
          (with-hl7-stub
            {"ADT^A01" {:validate {:PID.3.1 "WRONG_ID"}
                        :handler (fn [msg] (create-ack msg "AA"))}}
            (send-message (create-sample-adt))))))
  
  (testing "Regex match failure"
    (is (thrown? Exception
          (with-hl7-stub
            {"ADT^A01" {:validate {:PID.5.1 #"JONES.*"}
                        :handler (fn [msg] (create-ack msg "AA"))}}
            (send-message (create-sample-adt))))))
  
  (testing "Predicate function failure"
    (is (thrown? Exception
          (with-hl7-stub
            {"ADT^A01" {:validate {:PID.3.1 #(= 10 (count %))}  ; PID is "12345" (5 chars)
                        :handler (fn [msg] (create-ack msg "AA"))}}
            (send-message (create-sample-adt))))))
  
  (testing "Field exists failure"
    (is (thrown? Exception
          (with-hl7-stub
            {"ADT^A01" {:validate {:PID.99.1 :exists}  ; This field doesn't exist
                        :handler (fn [msg] (create-ack msg "AA"))}}
            (send-message (create-sample-adt))))))
  
  (testing "Field nil validation failure"
    (is (thrown? Exception
          (with-hl7-stub
            {"ADT^A01" {:validate {:PID.3.1 nil}  ; This field has value "12345"
                        :handler (fn [msg] (create-ack msg "AA"))}}
            (send-message (create-sample-adt)))))))