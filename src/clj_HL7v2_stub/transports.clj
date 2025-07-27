(ns clj-hl7v2-stub.transports
  "Multi-transport support for stubbing different HL7v2 connection types"
  (:require [clj-hl7v2-stub.mock-connection :as mock])
  (:import [ca.uhn.hl7v2.parser PipeParser]
           [ca.uhn.hl7v2.model Message]))

;; Conditionally require manifold dependencies
(def manifold-available?
  (try
    (require '[manifold.deferred :as d])
    (require '[manifold.stream :as s])
    true
    (catch Exception _ false)))

;; Dynamic vars for transport mocking
(def ^:dynamic *tcp-client-mock* nil)
(def ^:dynamic *mllp-stream-mock* nil)

(defn parse-mllp-message
  "Parse an MLLP-framed message, extracting the HL7 content"
  [mllp-data]
  (cond
    (string? mllp-data)
    ;; Remove MLLP framing characters (start=0x0b, end=0x1c+0x0d)
    (let [cleaned (-> mllp-data
                      (clojure.string/replace #"^\u000b" "") ; Remove start byte
                      (clojure.string/replace #"\u001c\r$" ""))] ; Remove end bytes
      cleaned)
    
    (sequential? mllp-data)
    ;; Handle parsed MLLP frame format [:start message end]
    (if (= (count mllp-data) 3)
      (second mllp-data)
      (str mllp-data))
    
    :else
    (str mllp-data)))

(defn encode-mllp-message
  "Encode an HL7 message with MLLP framing"
  [hl7-message]
  (str (char 0x0b) hl7-message (char 0x1c) (char 0x0d)))

(if manifold-available?
  (defn create-mock-mllp-stream
    "Creates a mock MLLP stream using manifold's built-in stream functionality"
    [handlers-atom]
    (let [parser (PipeParser.)
          input-stream (s/stream)
          output-stream (s/stream)]
      
      ;; Process messages from input stream and put responses to output stream
      (s/consume
        (fn [message]
          (try
            (let [hl7-content (parse-mllp-message message)
                  parsed-message (.parse parser hl7-content)
                  {:keys [handlers isolation-mode]} @handlers-atom
                  handler (mock/find-matching-handler handlers hl7-content parsed-message)]
              (cond
                handler
                (let [response (handler parsed-message)
                      response-str (if (instance? Message response)
                                     (.encode parser response)
                                     (str response))
                      mllp-response (encode-mllp-message response-str)]
                  (s/put! output-stream mllp-response))
                
                isolation-mode
                (throw (ex-info "No matching handler found for MLLP message"
                                {:message-type (mock/get-message-type hl7-content)
                                 :message (subs hl7-content 0 (min 200 (count hl7-content)))}))
                
                :else
                (let [ack (mock/create-ack parsed-message "AA" "Mock TCP ACK")
                      ack-str (.encode parser ack)
                      mllp-response (encode-mllp-message ack-str)]
                  (s/put! output-stream mllp-response))))
            (catch Exception e
              (if (:isolation-mode @handlers-atom)
                (throw e)
                (let [error-response (encode-mllp-message "MSH|^~\\&|MOCK|MOCK|SENDER|SENDER|20240101000000||ACK|12345|P|2.5\rMSA|AE|12345|Error processing message")]
                  (s/put! output-stream error-response))))))
        input-stream)
      
      ;; Return a duplex stream
      (s/splice output-stream input-stream)))
  
  (defn create-mock-mllp-stream
    "Creates a mock MLLP stream - requires manifold dependencies"
    [handlers-atom]
    (throw (ex-info "TCP transport features require manifold and aleph dependencies"
                    {:missing-deps ["manifold/manifold" "aleph/aleph"]
                     :help "Add these to your project dependencies to use TCP transport stubbing"}))))


(when manifold-available?
  (defn create-mock-tcp-client
    "Creates a mock TCP client that returns a mock MLLP stream"
    [handlers-atom {:keys [host port] :as connection-params}]
    (d/success-deferred (create-mock-mllp-stream handlers-atom))))

(when-not manifold-available?
  (defn create-mock-tcp-client
    "Creates a mock TCP client that returns a mock MLLP stream"
    [handlers-atom {:keys [host port] :as connection-params}]
    (throw (ex-info "TCP transport features require manifold and aleph dependencies"
                    {:missing-deps ["manifold/manifold" "aleph/aleph"]}))))

(defn with-tcp-transport-mocking
  "Sets up mocking for TCP-based HL7v2 transports"
  [handlers-atom]
  (if manifold-available?
    ;; Mock aleph.tcp/client function
    (let [original-tcp-client (when (find-ns 'aleph.tcp)
                                (resolve 'aleph.tcp/client))]
      (when original-tcp-client
        (alter-var-root original-tcp-client
                        (constantly #(create-mock-tcp-client handlers-atom %))))
      
      ;; Return cleanup function
      (fn []
        (when (and original-tcp-client (var? original-tcp-client))
          (alter-var-root original-tcp-client
                          (constantly @(resolve 'aleph.tcp/client))))))
    (throw (ex-info "TCP transport features require manifold and aleph dependencies"
                    {:missing-deps ["manifold/manifold" "aleph/aleph"]
                     :help "Add these to your project dependencies to use TCP transport stubbing"}))))

(defn mock-mllp-stream-fn
  "Mock function that can replace protocol/mllp-stream calls"
  [handlers-atom]
  (fn [stream]
    (create-mock-mllp-stream handlers-atom)))