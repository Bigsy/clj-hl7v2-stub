(ns clj-HL7v2-stub.mock-connection
  (:import [ca.uhn.hl7v2.app Connection ConnectionFactory Initiator]
           [ca.uhn.hl7v2.model Message]
           [ca.uhn.hl7v2.util Terser]
           [ca.uhn.hl7v2.parser PipeParser]
           [ca.uhn.hl7v2 DefaultHapiContext HapiContext]
           [ca.uhn.hl7v2.model.v24.message ACK]))


(defn get-message-type [^String message]
  (try
    (let [segments (clojure.string/split message #"[\r\n]+")
          msh (first segments)
          fields (clojure.string/split msh #"\|")]
      (when (>= (count fields) 9)
        (nth fields 8)))
    (catch Exception e
      nil)))

(defn create-ack 
  ([^Message message]
   (create-ack message "AA" nil))
  ([^Message message ^String ack-code]
   (create-ack message ack-code nil))
  ([^Message message ^String ack-code ^String text-message]
   (let [terser (Terser. message)
         ack-message (doto (ACK.)
                       (-> (.getMSH) (.getFieldSeparator) (.setValue "|"))
                       (-> (.getMSH) (.getEncodingCharacters) (.setValue "^~\\&"))
                       (-> (.getMSH) (.getSendingApplication) (.getNamespaceID) 
                           (.setValue (.get terser "/MSH-5")))
                       (-> (.getMSH) (.getSendingFacility) (.getNamespaceID)
                           (.setValue (.get terser "/MSH-6")))
                       (-> (.getMSH) (.getReceivingApplication) (.getNamespaceID)
                           (.setValue (.get terser "/MSH-3")))
                       (-> (.getMSH) (.getReceivingFacility) (.getNamespaceID)
                           (.setValue (.get terser "/MSH-4")))
                       (-> (.getMSH) (.getMessageControlID) 
                           (.setValue (.get terser "/MSH-10")))
                       (-> (.getMSA) (.getAcknowledgementCode) (.setValue ack-code))
                       (-> (.getMSA) (.getMessageControlID) 
                           (.setValue (.get terser "/MSH-10"))))]
     (when text-message
       (-> ack-message (.getMSA) (.getTextMessage) (.setValue text-message)))
     ack-message)))

(defn find-matching-handler [handlers message-string parsed-message]
  (let [message-type (get-message-type message-string)]
    (some (fn [[pattern handler]]
            (cond
              (string? pattern)
              (when (= pattern message-type)
                handler)
              
              (instance? java.util.regex.Pattern pattern)
              (when (and message-type (re-matches pattern message-type))
                handler)
              
              :else nil))
          handlers)))

;; Mock Initiator that intercepts sendAndReceive
(defn create-mock-initiator [handlers-atom]
  (let [parser (PipeParser.)]
    (proxy [Initiator] []
      (sendAndReceive [^Message message]
        (let [message-string (.encode parser message)
              {:keys [handlers isolation-mode]} @handlers-atom
              handler (find-matching-handler handlers message-string message)]
          (cond
            handler
            (handler message)
            
            isolation-mode
            (throw (ex-info "No matching handler found for message"
                            {:message-type (get-message-type message-string)
                             :message (subs message-string 0 (min 200 (count message-string)))}))
            
            :else
            (create-ack message "AA" "Mock ACK"))))
      
      (setTimeout [timeout]
        nil)
      
      (getTimeout []
        30000))))

;; Mock Connection that returns our mock initiator
(defn create-mock-connection [handlers host port]
  (let [initiator (create-mock-initiator handlers)]
    (proxy [Connection] []
      (getInitiator []
        initiator)
      
      (getRemoteAddress []
        (str host ":" port))
      
      (getRemotePort []
        port)
      
      (isOpen []
        true)
      
      (close []
        nil)
      
      (activate []
        nil))))

;; Instead of overriding ConnectionFactory, we'll override HapiContext.newClient
(defn create-mock-context [handlers]
  (let [base-context (DefaultHapiContext.)]
    (proxy [HapiContext] []
      ;; Delegate most methods to base context
      (getConnectionHub []
        (.getConnectionHub base-context))
      
      (getExecutorService []
        (.getExecutorService base-context))
      
      (getGenericParser []
        (.getGenericParser base-context))
      
      (getLowerLayerProtocol []
        (.getLowerLayerProtocol base-context))
      
      (getModelClassFactory []
        (.getModelClassFactory base-context))
      
      (getParserConfiguration []
        (.getParserConfiguration base-context))
      
      (getPipeParser []
        (.getPipeParser base-context))
      
      (getProfileStore []
        (.getProfileStore base-context))
      
      (getServerConfiguration []
        (.getServerConfiguration base-context))
      
      (getSocketFactory []
        (.getSocketFactory base-context))
      
      (getValidationContext []
        (.getValidationContext base-context))
      
      (getValidationExceptionHandlerFactory []
        (.getValidationExceptionHandlerFactory base-context))
      
      (getValidationRuleBuilder []
        (.getValidationRuleBuilder base-context))
      
      (getXMLParser []
        (.getXMLParser base-context))
      
      (newServer [port useTls]
        (.newServer base-context port useTls))
      
      (newLazyServer [port useTls]
        (.newLazyServer base-context port useTls))
      
      ;; Override newClient methods to return our mock
      (newClient [host port useTls]
        (create-mock-connection handlers host port))
      
      (newLazyClient [host port useTls]
        (create-mock-connection handlers host port))
      
      (setExecutorService [service]
        (.setExecutorService base-context service))
      
      (setLowerLayerProtocol [llp]
        (.setLowerLayerProtocol base-context llp))
      
      (setModelClassFactory [factory]
        (.setModelClassFactory base-context factory))
      
      (setParserConfiguration [config]
        (.setParserConfiguration base-context config))
      
      (setProfileStore [store]
        (.setProfileStore base-context store))
      
      (setServerConfiguration [config]
        (.setServerConfiguration base-context config))
      
      (setSocketFactory [factory]
        (.setSocketFactory base-context factory))
      
      (setValidationContext [context]
        (.setValidationContext base-context context))
      
      (setValidationExceptionHandlerFactory [factory]
        (.setValidationExceptionHandlerFactory base-context factory))
      
      (setValidationRuleBuilder [builder]
        (.setValidationRuleBuilder base-context builder))
      
      (close []
        (.close base-context)))))