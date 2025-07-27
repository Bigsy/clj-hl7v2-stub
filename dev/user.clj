(ns user
  (:require [hashp.core]
            [hl7v2-stub.core :as stub]
            [hl7v2-stub.mock-connection :as mock]
            [clojure.repl :refer [doc source]]
            [clojure.pprint :refer [pprint]])
  (:import [ca.uhn.hl7v2.parser PipeParser]
           [ca.uhn.hl7v2 DefaultHapiContext]))

;; Some helpful dev functions
(defn parse-hl7 
  "Parse an HL7 message string"
  [msg-string]
  (let [parser (PipeParser.)]
    (.parse parser msg-string)))

(defn sample-adt []
  "Create a sample ADT message"
  (parse-hl7 (str "MSH|^~\\&|TEST_SENDER|TEST_FACILITY|TEST_RECEIVER|TEST_RECV_FAC|20240101120000||ADT^A01|12345|P|2.4\r"
                  "EVN|A01|20240101120000\r"
                  "PID|||123456||DOE^JOHN||19800101|M")))

(defn sample-oru []
  "Create a sample ORU message"
  (parse-hl7 (str "MSH|^~\\&|LAB_SYSTEM|LAB|EMR|HOSPITAL|20240101120000||ORU^R01|54321|P|2.4\r"
                  "PID|||123456||DOE^JOHN||19800101|M\r"
                  "OBR|1|123|456|TEST^Test Name")))

(comment
  ;; Example usage:
  
  ;; Parse and inspect a message
  #p (sample-adt)
  
  ;; Test message type extraction
  #p (mock/get-message-type "MSH|^~\\&|TEST|TEST|TEST|TEST|20240101||ADT^A01|123|P|2.4")
  
  ;; Test the stubbing
  (stub/with-hl7-stub
    {"ADT^A01" (fn [msg] (stub/create-ack msg "AA" "Test ACK"))}
    (let [context (stub/get-context)
          connection (.newClient context "localhost" 8080 false)
          initiator (.getInitiator connection)
          response (.sendAndReceive initiator (sample-adt))]
      #p response))
  
  )