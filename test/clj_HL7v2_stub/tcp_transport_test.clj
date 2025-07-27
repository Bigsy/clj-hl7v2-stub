(ns clj-hl7v2-stub.tcp-transport-test
  (:require [clojure.test :refer :all]
            [clj-hl7v2-stub.core :refer :all]
            [clj-hl7v2-stub.transports :as transports]))

(deftest test-mllp-framing
  (testing "MLLP message framing and parsing work correctly"
    (let [test-message "MSH|^~\\&|TEST|TEST|TEST|TEST|20240101||ACK|123|P|2.5\rMSA|AA|123"
          framed (transports/encode-mllp-message test-message)
          parsed (transports/parse-mllp-message framed)]
      
      ;; Check framing adds correct MLLP bytes
      (is (= (first framed) (char 0x0b)))
      (is (clojure.string/ends-with? framed (str (char 0x1c) (char 0x0d))))
      
      ;; Check parsing removes framing correctly
      (is (= parsed test-message)))))

(deftest test-mllp-parsing-variations
  (testing "MLLP parsing handles different input formats"
    (let [test-message "MSH|^~\\&|TEST|TEST|TEST|TEST|20240101||ACK|123|P|2.5"]
      
      ;; Test string input with MLLP framing
      (is (= test-message 
             (transports/parse-mllp-message (transports/encode-mllp-message test-message))))
      
      ;; Test sequential input (simulating parsed frame)
      (is (= test-message
             (transports/parse-mllp-message [:start test-message :end])))
      
      ;; Test direct string input (no framing)
      (is (= test-message
             (transports/parse-mllp-message test-message))))))

(deftest test-tcp-transport-setup
  (testing "TCP transport mocking setup works"
    (let [handlers {"ADT^A01" (fn [msg] (create-ack msg "AA" "Test"))}
          normalized-handlers (setup-message-handlers handlers false)
          handlers-atom (atom {:handlers normalized-handlers :isolation-mode false})]
      
      ;; Test that we can create a mock TCP client function
      (is (fn? (transports/with-tcp-transport-mocking handlers-atom)))
      
      ;; Test that we can create mock MLLP stream
      (is (some? (transports/create-mock-mllp-stream handlers-atom))))))

(deftest test-universal-stub-configuration
  (testing "Universal stub accepts different configuration formats"
    ;; Test legacy format (just handlers map)
    (with-universal-hl7-stub
      {"ADT^A01" (fn [msg] (create-ack msg "AA" "Legacy"))}
      (is (some? (get-context))))
    
    ;; Test new format with explicit transport
    (with-universal-hl7-stub
      {:transport :hapi
       :handlers {"ADT^A01" (fn [msg] (create-ack msg "AA" "HAPI"))}}
      (is (some? (get-context))))
    
    ;; Test auto transport detection
    (with-universal-hl7-stub
      {:transport :auto
       :handlers {"ADT^A01" (fn [msg] (create-ack msg "AA" "Auto"))}}
      (is (some? (get-context))))))

(deftest test-transport-macro-basic-functionality  
  (testing "TCP transport macros can be called without errors"
    ;; Test basic TCP stub macro
    (with-tcp-hl7-stub
      {"ADT^A01" (fn [msg] (create-ack msg "AA" "TCP Test"))}
      (is true)) ; Just test that macro executes without error
    
    ;; Test TCP stub in isolation mode
    (with-tcp-hl7-stub-in-isolation
      {"ADT^A01" (fn [msg] (create-ack msg "AA" "TCP Isolation"))}
      (is true))))

(deftest test-mock-mllp-stream-creation
  (testing "Mock MLLP stream helper function works"
    (let [handlers {"ADT^A01" (fn [msg] (create-ack msg "AA" "Stream Test"))}
          mock-stream-fn (create-mock-mllp-stream handlers)]
      (is (fn? mock-stream-fn))
      (is (some? (mock-stream-fn nil))))))