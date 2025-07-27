(ns clj-hl7v2-stub.example-test
  (:require [clojure.test :refer :all]
            [clj-hl7v2-stub.core :refer :all])
  (:import [ca.uhn.hl7v2.util Terser]
           [ca.uhn.hl7v2.parser PipeParser]))

(deftest test-message-inspection
  (testing "Handler receives the full parsed message object"
    (let [received-message (atom nil)]
      (with-hl7-stub
        {"ADT^A01" (fn [msg]
                     ;; Store the message for assertions
                     (reset! received-message msg)
                     (create-ack msg "AA" "Processed"))}
        
        ;; Send a test message
        (let [parser (PipeParser.)
              msg-string (str "MSH|^~\\&|TEST_SENDER|TEST_FACILITY|TEST_RECEIVER|TEST_RECV_FAC|20240101120000||ADT^A01|12345|P|2.4\r"
                             "EVN|A01|20240101120000\r"
                             "PID|||123456||DOE^JOHN||19800101|M")
              test-msg (.parse parser msg-string)
              context (get-context)
              connection (.newClient context "localhost" 8080 false)
              initiator (.getInitiator connection)
              response (.sendAndReceive initiator test-msg)]
          
          ;; Now assert against the received message
          (is (not (nil? @received-message)))
          
          ;; Check message type
          (is (= "ca.uhn.hl7v2.model.v24.message.ADT_A01" 
                 (.getName (.getClass @received-message))))
          
          ;; Use Terser to extract and verify specific fields
          (let [terser (Terser. @received-message)]
            (is (= "TEST_SENDER" (.get terser "/MSH-3")))
            (is (= "TEST_FACILITY" (.get terser "/MSH-4")))
            (is (= "12345" (.get terser "/MSH-10")))
            (is (= "123456" (.get terser "/PID-3")))
            (is (= "DOE" (.get terser "/PID-5-1")))
            (is (= "JOHN" (.get terser "/PID-5-2")))))))))

(deftest test-conditional-response-based-on-content
  (testing "Handler can inspect message and respond conditionally"
    (with-hl7-stub
      {"ORM^O01" (fn [msg]
                   (let [terser (Terser. msg)
                         test-code (.get terser "/OBR-4-1")]
                     (cond
                       (= test-code "GLUCOSE")
                       (create-ack msg "AA" "Glucose test accepted")
                       
                       (= test-code "INVALID")
                       (create-ack msg "AR" "Invalid test code")
                       
                       :else
                       (create-ack msg "AA" "Test accepted"))))}
      
      ;; Test with valid code
      (let [parser (PipeParser.)
            valid-msg (.parse parser (str "MSH|^~\\&|LAB|HOSPITAL|LIS|LAB|20240101||ORM^O01|123|P|2.4\r"
                                          "PID|||123||DOE^JOHN\r"
                                          "OBR|1|123|456|GLUCOSE^Glucose Test"))
            context (get-context)
            connection (.newClient context "localhost" 8080 false)
            initiator (.getInitiator connection)
            response (.sendAndReceive initiator valid-msg)
            response-str (.encode parser response)]
        (is (.contains response-str "Glucose test accepted")))
      
      ;; Test with invalid code
      (let [parser (PipeParser.)
            invalid-msg (.parse parser (str "MSH|^~\\&|LAB|HOSPITAL|LIS|LAB|20240101||ORM^O01|124|P|2.4\r"
                                            "PID|||123||DOE^JOHN\r"
                                            "OBR|1|123|456|INVALID^Invalid Test"))
            context (get-context)
            connection (.newClient context "localhost" 8080 false)
            initiator (.getInitiator connection)
            response (.sendAndReceive initiator invalid-msg)
            response-str (.encode parser response)]
        (is (.contains response-str "AR"))
        (is (.contains response-str "Invalid test code"))))))