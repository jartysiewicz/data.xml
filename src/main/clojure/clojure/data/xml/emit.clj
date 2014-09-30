;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns clojure.data.xml.emit
  "XML emitting. This namespace is not public API, but will stay compatible between patch versions."
  {:author "Chris Houser, Herwig Hochleitner"}
  (:require (clojure.data.xml [event :refer [event]]
                              node)
            [clojure.data.xml.impl :as impl
             :refer [raw-parse-attrs
                     xml-name
                     raw-uri raw-name raw-parse-attrs]]
            [clojure.data.xml.impl.xmlns :refer
             [uri-from-prefix prefix-from-uri default-ns-prefix null-ns-uri]]
            [clojure.string :as str])
  (:import (clojure.data.xml.event Event)
           (clojure.data.xml.node CData Comment Element)
           (clojure.lang APersistentMap)
           (java.io OutputStreamWriter)
           (java.nio.charset Charset)
           (javax.xml.stream XMLStreamWriter)
           (javax.xml.transform OutputKeys Transformer
                                TransformerFactory)))

(defn write-attributes [attrs ^XMLStreamWriter writer]
  (let [ns-ctx (.getNamespaceContext writer)]
    (doseq [[k v] attrs]
      (let [qn (xml-name k)]
        (.writeAttribute writer (raw-uri qn) (raw-name qn) v)))))

(defn update-ns [default attrs ^XMLStreamWriter writer]
  (when default
    (.setDefaultNamespace writer default))
  (doseq [[k v] attrs]
    (.setPrefix writer k v)))

(defn write-ns-attributes [default attrs ^XMLStreamWriter writer]
  (when default
    (.writeDefaultNamespace writer default))
  (doseq [[k v] attrs]
    (.writeNamespace writer k v)))

(defn emit-start-tag [event ^XMLStreamWriter writer]
  (let [{:keys [nss uris attrs default] :as parse} (raw-parse-attrs (:attrs event))
        _ (update-ns default nss writer)
        ns-ctx (.getNamespaceContext writer)
        qn (xml-name (:name event))
        uri (raw-uri qn)
        pf (if (or (str/blank? uri)
                   (= (.getNamespaceURI ns-ctx default-ns-prefix)
                      uri))
             default-ns-prefix
             (prefix-from-uri ns-ctx uri))]
    (when (and (not (str/blank? uri))
               (not= uri (uri-from-prefix ns-ctx pf)))
      (throw (ex-info (str "Uri not bound to a prefix: " uri) {:qname qn})))
    (.writeStartElement writer pf (raw-name qn) uri)
    (write-attributes attrs writer)
    (write-ns-attributes default nss writer)))

(defn emit-cdata [^String cdata-str ^XMLStreamWriter writer]
  (when-not (str/blank? cdata-str)
    (let [idx (.indexOf cdata-str "]]>")]
      (if (= idx -1)
        (.writeCData writer cdata-str )
        (do
          (.writeCData writer (subs cdata-str 0 (+ idx 2)))
          (recur (subs cdata-str (+ idx 2)) writer))))))

(defn emit-event [event ^XMLStreamWriter writer]
  (case (:type event)
    :start-element (emit-start-tag event writer)
    :end-element (.writeEndElement writer)
    :chars (.writeCharacters writer (:str event))
    :cdata (emit-cdata (:str event) writer)
    :comment (.writeComment writer (:str event))))

(defprotocol EventGeneration
  "Protocol for generating new events based on element type"
  (gen-event [item]
    "Function to generate an event for e.")
  (next-events [item next-items]
    "Returns the next set of events that should occur after e.  next-events are the
     events that should be generated after this one is complete."))

;; Same implementation for Element defrecords and plain maps
(let [impl-map {:gen-event (fn gen-event [element]
                             (event :start-element (:tag element) (:attrs element) nil))
                :next-events (fn next-events [element next-items]
                               (cons (:content element)
                                     (cons (event :end-element (:tag element) nil nil) next-items)))}]
  (extend Element EventGeneration impl-map)
  (extend APersistentMap EventGeneration impl-map))

(extend-protocol EventGeneration

  Event
  (gen-event [event] event)
  (next-events [_ next-items]
    next-items)

  clojure.lang.Sequential
  (gen-event [coll]
    (gen-event (first coll)))
  (next-events [coll next-items]
    (if-let [r (seq (rest coll))]
      (cons (next-events (first coll) r) next-items)
      (next-events (first coll) next-items)))

  String
  (gen-event [s]
    (event :chars nil nil s))
  (next-events [_ next-items]
    next-items)

  Boolean
  (gen-event [b]
    (event :chars nil nil (str b)))
  (next-events [_ next-items]
    next-items)

  Number
  (gen-event [b]
    (event :chars nil nil (str b)))
  (next-events [_ next-items]
    next-items)

  CData
  (gen-event [cdata]
    (event :cdata nil nil (:content cdata)))
  (next-events [_ next-items]
    next-items)

  Comment
  (gen-event [comment]
    (event :comment nil nil (:content comment)))
  (next-events [_ next-items]
    next-items)

  nil
  (gen-event [_]
    (event :chars nil nil ""))
  (next-events [_ next-items]
    next-items))

(defn flatten-elements [elements]
  (when (seq elements)
    (lazy-seq
     (let [e (first elements)]
       (cons (gen-event e)
             (flatten-elements (next-events e (rest elements))))))))

(defn check-stream-encoding [^OutputStreamWriter stream xml-encoding]
  (when (not= (Charset/forName xml-encoding) (Charset/forName (.getEncoding stream)))
    (throw (Exception. (str "Output encoding of stream (" xml-encoding
                            ") doesn't match declaration ("
                            (.getEncoding stream) ")")))))

(defn ^Transformer indenting-transformer []
  (doto (-> (TransformerFactory/newInstance) .newTransformer)
    (.setOutputProperty (OutputKeys/INDENT) "yes")
    (.setOutputProperty (OutputKeys/METHOD) "xml")
    (.setOutputProperty "{http://xml.apache.org/xslt}indent-amount" "2")))
