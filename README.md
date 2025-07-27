# clj-hl7v2-stub

[![Clojars Project](https://img.shields.io/clojars/v/org.clojars.bigsy/clj-hl7v2-stub.svg)](https://clojars.org/org.clojars.bigsy/clj-hl7v2-stub)

A Clojure library for stubbing out HL7v2 message communication in tests. It allows you to intercept HL7v2 messages sent via HAPI and return predefined responses without requiring actual MLLP connections.

## Installation

Add to your `deps.edn`:

```clojure
{:deps {org.clojars.bigsy/clj-hl7v2-stub {:mvn/version "0.1.0"}}}
```

## Usage

### Basic Usage

```clojure
(ns myapp.test
  (:require [clj-hl7v2-stub.core :refer [with-hl7-stub create-ack get-context]]
            [clojure.test :refer :all])
  (:import [ca.uhn.hl7v2.parser PipeParser]))

(deftest test-hl7-message-handling
  (with-hl7-stub
    {"ADT^A01" (fn [msg] (create-ack msg "AA" "Patient admitted"))}
    
    ;; Your test code that sends HL7 messages
    (let [context (get-context)
          connection (.newClient context "localhost" 8080 false)
          initiator (.getInitiator connection)
          parser (PipeParser.)
          adt-msg (.parse parser "MSH|^~\\&|HIS|MedCenter|LIS|MedCenter|20240101120000||ADT^A01|123|P|2.4\rPID|||1234||DOE^JOHN")
          response (.sendAndReceive initiator adt-msg)]
      (is (= "AA" (-> response .getMSA .getAcknowledgmentCode .getValue))))))
```

### Message Matching

The library supports multiple ways to match incoming messages:

#### Exact Match
```clojure
(with-hl7-stub
  {"ADT^A01" (fn [msg] (create-ack msg "AA"))
   "ORU^R01" (fn [msg] (create-ack msg "AA"))}
  ;; Your test code
  )
```

#### Regex Pattern Match
```clojure
(with-hl7-stub
  {#"ADT\^A0[0-9]" (fn [msg] (create-ack msg "AA" "Admission message received"))
   #"ORU\^.*" (fn [msg] (create-ack msg "AA" "Lab result received"))}
  ;; Your test code
  )
```

### Creating Responses

The library provides a `create-ack` helper function for generating ACK messages:

```clojure
;; Simple AA (Application Accept) acknowledgment
(create-ack message)

;; Specify acknowledgment code
(create-ack message "AA")  ; Application Accept
(create-ack message "AE")  ; Application Error
(create-ack message "AR")  ; Application Reject

;; Include a text message
(create-ack message "AE" "Invalid patient ID")
```

You can also create custom responses:

```clojure
(with-hl7-stub
  {"QRY^A19" (fn [msg]
               ;; Create a custom response message
               (let [parser (PipeParser.)
                     response-text (str "MSH|^~\\&|HIS|MedCenter|LIS|MedCenter|20240101120000||ADR^A19|456|P|2.4\r"
                                       "MSA|AA|123\r"
                                       "QRD|20240101120000|R|I|QueryID|||1^RD|1234|DEM\r"
                                       "PID|||1234||DOE^JOHN||19800101|M")]
                 (.parse parser response-text)))}
  ;; Your test code
  )
```

### Isolation Mode

Use isolation mode to ensure all messages are matched. Unmatched messages will throw an exception:

```clojure
(with-hl7-stub-in-isolation
  {"ADT^A01" (fn [msg] (create-ack msg))}
  
  ;; This will succeed
  (send-adt-a01-message)
  
  ;; This will throw an exception
  (send-oru-r01-message))  ; => throws "No matching handler found for message"
```

### Call Count Validation

Verify that messages are sent the expected number of times:

```clojure
(with-hl7-stub
  {"ADT^A01" {:handler (fn [msg] (create-ack msg))
              :times 2}}
  
  ;; Must send exactly 2 ADT^A01 messages
  (send-adt-a01-message)
  (send-adt-a01-message))
  ;; Test will fail if count doesn't match
```

### Multi-threaded Tests

For tests that send messages from multiple threads, use the global variant:

```clojure
(with-global-hl7-stub
  {"ADT^A01" (fn [msg] (create-ack msg))}
  
  ;; Messages sent from any thread will be intercepted
  (future (send-adt-message))
  (future (send-adt-message)))
```

## How It Works

The library works by providing a custom `HapiContext` that returns mock connections instead of real MLLP connections. When you use `get-context` within a `with-hl7-stub` block, it returns the mock context. Messages sent through this context are intercepted and matched against your defined handlers.

## Examples

### Testing an ADT Message Flow

```clojure
(deftest test-patient-admission-flow
  (testing "Patient admission sends ADT^A01 and receives acknowledgment"
    (with-hl7-stub
      {"ADT^A01" (fn [msg]
                   (let [terser (Terser. msg)
                         patient-id (.get terser "/PID-3")]
                     (create-ack msg "AA" (str "Patient " patient-id " admitted"))))}
      
      (let [result (admit-patient {:id "12345" :name "John Doe"})]
        (is (= :success (:status result)))
        (is (.contains (:ack-message result) "Patient 12345 admitted"))))))
```

### Testing Error Scenarios

```clojure
(deftest test-lab-order-rejection
  (testing "Lab order rejected for invalid test code"
    (with-hl7-stub
      {"ORM^O01" (fn [msg]
                   (let [terser (Terser. msg)
                         test-code (.get terser "/OBR-4-1")]
                     (if (valid-test-code? test-code)
                       (create-ack msg "AA")
                       (create-ack msg "AR" (str "Invalid test code: " test-code)))))}
      
      (let [result (order-lab-test {:patient-id "12345" :test-code "INVALID"})]
        (is (= :rejected (:status result)))
        (is (.contains (:error-message result) "Invalid test code"))))))
```

## License

Copyright © 2024

Distributed under the Eclipse Public License version 1.0.