(ns clj-hl7v2-stub.core-test
  (:require [clojure.test :refer :all]
            [clj-hl7v2-stub.core :refer :all])
  (:import [ca.uhn.hl7v2.parser PipeParser]))

(def ^:private parser (PipeParser.))

(defn- get-connection []
  (let [context (get-context)]
    (.newClient context "localhost" 8080 false)))

(defn- send-message [message]
  (let [connection (get-connection)
        initiator (.getInitiator connection)]
    (.sendAndReceive initiator message)))

(defn- send-and-parse [message]
  (let [response (send-message message)]
    (.encode parser response)))

(defn create-adt-a01 []
  (let [msg-string (str "MSH|^~\\&|TEST_SENDER|TEST_FACILITY|TEST_RECEIVER|TEST_RECV_FAC|20240101120000||ADT^A01|12345|P|2.4\r"
                        "EVN|A01|20240101120000\r"
                        "PID|||123456||DOE^JOHN||19800101|M")]
    (.parse parser msg-string)))

(defn create-oru-r01 []
  (let [msg-string (str "MSH|^~\\&|LAB_SYSTEM|LAB|EMR|HOSPITAL|20240101120000||ORU^R01|54321|P|2.4\r"
                        "PID|||123456||DOE^JOHN||19800101|M\r"
                        "OBR|1|123|456|TEST^Test Name")]
    (.parse parser msg-string)))

(deftest test-basic-stubbing
  (testing "Basic message stubbing with exact match"
    (with-hl7-stub
      {"ADT^A01" (fn [msg] 
                   ;; msg is the parsed HAPI Message object
                   (println "Message type:" (type msg))
                   (println "Message content:" (.encode parser msg))
                   ;; You can access fields using Terser
                   (let [terser (ca.uhn.hl7v2.util.Terser. msg)]
                     (println "Patient ID:" (.get terser "/PID-3"))
                     (println "Message Control ID:" (.get terser "/MSH-10")))
                   (create-ack msg "AA" "ADT processed"))}
      (let [response-string (send-and-parse (create-adt-a01))]
        (is (.contains response-string "AA"))
        (is (.contains response-string "ADT processed"))))))

(deftest test-regex-matching
  (testing "Regex pattern matching for message types"
    (with-hl7-stub
      {#"ADT\^A0[0-9]" (fn [msg] (create-ack msg "AA" "ADT matched by regex"))}
      (let [response-string (send-and-parse (create-adt-a01))]
        (is (.contains response-string "ADT matched by regex"))))))

(deftest test-multiple-handlers
  (testing "Multiple message type handlers"
    (with-hl7-stub
      {"ADT^A01" (fn [msg] (create-ack msg "AA" "ADT received"))
       "ORU^R01" (fn [msg] (create-ack msg "AA" "ORU received"))}
      (let [adt-response (send-and-parse (create-adt-a01))
            oru-response (send-and-parse (create-oru-r01))]
        (is (.contains adt-response "ADT received"))
        (is (.contains oru-response "ORU received"))))))

(deftest test-isolation-mode
  (testing "Isolation mode throws on unmatched messages"
    (with-hl7-stub-in-isolation
      {"ADT^A01" (fn [msg] (create-ack msg))}
      (is (send-message (create-adt-a01)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"No matching handler found"
                            (send-message (create-oru-r01)))))))

(deftest test-call-counting
  (testing "Call count validation"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Call count mismatch"
                          (with-hl7-stub
                            {"ADT^A01" {:handler (fn [msg] (create-ack msg))
                                        :times 2}}
                            (send-message (create-adt-a01)))))))

(deftest test-default-ack
  (testing "Default ACK for unmatched messages"
    (with-hl7-stub
      {"ADT^A01" (fn [msg] (create-ack msg "AA" "Handled"))}
      (let [response-string (send-and-parse (create-oru-r01))]
        (is (.contains response-string "Mock ACK"))))))

(deftest test-error-ack
  (testing "Creating error ACK responses"
    (with-hl7-stub
      {"ADT^A01" (fn [msg] (create-ack msg "AE" "Error processing ADT"))}
      (let [response-string (send-and-parse (create-adt-a01))]
        (is (.contains response-string "AE"))
        (is (.contains response-string "Error processing ADT"))))))