;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns clojure.data.xml.parse
  "Parsing functionality. This namespace is not public API, but will stay stable between patch versions."
  {:author "Herwig Hochleitner"}
  (:require [clojure.data.xml.event :refer [event]]
            [clojure.data.xml.impl :refer [static-case xmlns-attribute]]
            [clojure.data.xml.node :as node]
            [clojure.string :as str])
  (:import (clojure.data.xml.event Event)
           (javax.xml.stream XMLStreamConstants XMLStreamReader
                             XMLInputFactory)))

;=== Parse-related functions ===
(defn seq-tree
  "Takes a seq of events that logically represents
  a tree by each event being one of: enter-sub-tree event,
  exit-sub-tree event, or node event.

  Returns a lazy sequence whose first element is a sequence of
  sub-trees and whose remaining elements are events that are not
  siblings or descendants of the initial event.

  The given exit? function must return true for any exit-sub-tree
  event.  parent must be a function of two arguments: the first is an
  event, the second a sequence of nodes or subtrees that are children
  of the event.  parent must return nil or false if the event is not
  an enter-sub-tree event.  Any other return value will become
  a sub-tree of the output tree and should normally contain in some
  way the children passed as the second arg.  The node function is
  called with a single event arg on every event that is neither parent
  nor exit, and its return value will become a node of the output tree.

  (seq-tree #(when (= %1 :<) (vector %2)) #{:>} str
            [1 2 :< 3 :< 4 :> :> 5 :> 6])
  ;=> ((\"1\" \"2\" [(\"3\" [(\"4\")])] \"5\") 6)"
 [parent exit? node coll]
  (lazy-seq
    (when-let [[event] (seq coll)]
      (let [more (rest coll)]
        (if (exit? event)
          (cons nil more)
          (let [tree (seq-tree parent exit? node more)]
            (if-let [p (parent event (lazy-seq (first tree)))]
              (let [subtree (seq-tree parent exit? node (lazy-seq (rest tree)))]
                (cons (cons p (lazy-seq (first subtree)))
                      (lazy-seq (rest subtree))))
              (cons (cons (node event) (lazy-seq (first tree)))
                    (lazy-seq (rest tree))))))))))

(defn event-tree
  "Returns a lazy tree of Element objects for the given seq of Event
  objects. See source-seq and parse."
  [events]
  (ffirst
   (seq-tree
    (fn [^Event event contents]
      (when (= :start-element (.type event))
        (node/element* (.name event) (.attrs event) contents)))
    (fn [^Event event] (= :end-element (.type event)))
    (fn [^Event event] (.str event))
    events)))

(defn attr-prefix [^XMLStreamReader sreader index]
  (let [p (.getAttributePrefix sreader index)]
    (when-not (str/blank? p)
      p)))

(defn attr-hash [^XMLStreamReader sreader] (into {}
    (for [i (range (.getAttributeCount sreader))]
      [(keyword (attr-prefix sreader i) (.getAttributeLocalName sreader i))
       (.getAttributeValue sreader i)])))

(defn attr-hash [^XMLStreamReader sreader] (into {}
    (concat
     (for [i (range (.getAttributeCount sreader))]
      [(keyword (attr-prefix sreader i) (.getAttributeLocalName sreader i))
       (.getAttributeValue sreader i)])
     (for [i (range (.getNamespaceCount sreader))
           :let [prefix (.getNamespacePrefix sreader i)]]
       [(if prefix
          (keyword xmlns-attribute prefix)
          :xmlns)
        (.getNamespaceURI sreader i)]))))

(defn xml-tag [^XMLStreamReader sreader]
  (let [prefix (.getPrefix sreader)
        name (.getLocalName sreader)]
    (if (str/blank? prefix)
      (keyword name)
      (keyword prefix name))))

; Note, sreader is mutable and mutated here in pull-seq, but it's
; protected by a lazy-seq so it's thread-safe.
(defn pull-seq
  "Creates a seq of events.  The XMLStreamConstants/SPACE clause below doesn't seem to 
   be triggered by the JDK StAX parser, but is by others.  Leaving in to be more complete."
  [^XMLStreamReader sreader]
  (lazy-seq
   (loop []
     (static-case (.next sreader)
       XMLStreamConstants/START_ELEMENT
       (cons (event :start-element
                    (xml-tag sreader)
                    (attr-hash sreader) nil)
             (pull-seq sreader)) 
       XMLStreamConstants/END_ELEMENT
       (cons (event :end-element
                    (xml-tag sreader)
                    nil nil)
             (pull-seq sreader))
       XMLStreamConstants/CHARACTERS
       (if-let [text (and (not (.isWhiteSpace sreader))
                          (.getText sreader))]
         (cons (event :characters nil nil text)
               (pull-seq sreader))
         (recur))
       XMLStreamConstants/END_DOCUMENT
       nil
       (recur) ;; Consume and ignore comments, spaces, processing instructions etc
       ))))

(def xml-input-factory-props
  {:allocator XMLInputFactory/ALLOCATOR
   :coalescing XMLInputFactory/IS_COALESCING
   :namespace-aware XMLInputFactory/IS_NAMESPACE_AWARE
   :replacing-entity-references XMLInputFactory/IS_REPLACING_ENTITY_REFERENCES
   :supporting-external-entities XMLInputFactory/IS_SUPPORTING_EXTERNAL_ENTITIES
   :validating XMLInputFactory/IS_VALIDATING
   :reporter XMLInputFactory/REPORTER
   :resolver XMLInputFactory/RESOLVER
   :support-dtd XMLInputFactory/SUPPORT_DTD})

(defn new-xml-input-factory [props]
  (let [fac (XMLInputFactory/newInstance)]
    (doseq [[k v] props
            :let [prop (xml-input-factory-props k)]]
      (.setProperty fac prop v))
    fac))
