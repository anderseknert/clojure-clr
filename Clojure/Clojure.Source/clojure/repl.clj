﻿;   Copyright (c) Chris Houser, Dec 2008. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Common Public License 1.0 (http://opensource.org/licenses/cpl.php)
;   which can be found in the file CPL.TXT at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

; Utilities meant to be used interactively at the REPL

(ns
  ^{:author "Chris Houser, Christophe Grand, Stephen Gilardi, Michel Salim"
     :doc "Utilities meant to be used interactively at the REPL"}
  clojure.repl
  (:require [clojure.spec.alpha :as spec])
  )   ;;;(:import (java.io LineNumberReader InputStreamReader PushbackReader)
      ;;;         (clojure.lang RT Reflector)))

(def ^:private special-doc-map
  '{. {:url "java_interop#dot"
       :forms [(.instanceMember instance args*)
               (.instanceMember Classname args*)
               (Classname/staticMethod args*)
               Classname/staticField]
       :doc "The instance member form works for both fields and methods.
  They all expand into calls to the dot operator at macroexpansion time."}
    def {:forms [(def symbol doc-string? init?)]
         :doc "Creates and interns a global var with the name
  of symbol in the current namespace (*ns*) or locates such a var if
  it already exists.  If init is supplied, it is evaluated, and the
  root binding of the var is set to the resulting value.  If init is
  not supplied, the root binding of the var is unaffected."}
    do {:forms [(do exprs*)]
        :doc "Evaluates the expressions in order and returns the value of
  the last. If no expressions are supplied, returns nil."}
    if {:forms [(if test then else?)]
        :doc "Evaluates test. If not the singular values nil or false,
  evaluates and yields then, otherwise, evaluates and yields else. If
  else is not supplied it defaults to nil."}
    monitor-enter {:forms [(monitor-enter x)]
                   :doc "Synchronization primitive that should be avoided
  in user code. Use the 'locking' macro."}
    monitor-exit {:forms [(monitor-exit x)]
                  :doc "Synchronization primitive that should be avoided
  in user code. Use the 'locking' macro."}
    new {:forms [(Classname. args*) (new Classname args*)]
         :url "java_interop#new"
         :doc "The args, if any, are evaluated from left to right, and
  passed to the constructor of the class named by Classname. The
  constructed object is returned."}
    quote {:forms [(quote form)]
           :doc "Yields the unevaluated form."}
    recur {:forms [(recur exprs*)]
           :doc "Evaluates the exprs in order, then, in parallel, rebinds
  the bindings of the recursion point to the values of the exprs.
  Execution then jumps back to the recursion point, a loop or fn method."}
    set! {:forms[(set! var-symbol expr)
                 (set! (. instance-expr instanceFieldName-symbol) expr)
                 (set! (. Classname-symbol staticFieldName-symbol) expr)]
          :url "vars#set"
          :doc "Used to set thread-local-bound vars, Java object instance
fields, and Java class static fields."}
    throw {:forms [(throw expr)]
           :doc "The expr is evaluated and thrown, therefore it should
  yield an instance of some derivee of Throwable."}
    try {:forms [(try expr* catch-clause* finally-clause?)]
         :doc "catch-clause => (catch classname name expr*)
  finally-clause => (finally expr*)

  Catches and handles Java exceptions."}
    var {:forms [(var symbol)]
         :doc "The symbol must resolve to a var, and the Var object
itself (not its value) is returned. The reader macro #'x expands to (var x)."}})

(defn- special-doc [name-symbol]
  (assoc (or (special-doc-map name-symbol) (meta (resolve name-symbol)))
         :name name-symbol
         :special-form true))

(defn- namespace-doc [nspace]
  (assoc (meta nspace) :name (ns-name nspace)))

(defn- print-doc [{n :ns
                   nm :name
                   :keys [forms arglists special-form doc url macro spec]
                   :as m}]
  (println "-------------------------")
  (println (or spec (str (when n (str (ns-name n) "/")) nm)))
   (when forms
     (doseq [f forms]
       (print "  ")
       (prn f)))
   (when arglists
     (prn arglists))  
  (cond
    special-form
    (println "Special Form")
    macro
    (println "Macro")
    spec
    (println "Spec"))
  (when doc (println " " doc))
  (when special-form
    (if (contains? m :url)
      (when url
        (println (str "\n  Please see http://clojure.org/" url)))
      (println (str "\n  Please see http://clojure.org/special_forms#" nm))))
  (when n
    (when-let [fnspec (spec/get-spec (symbol (str (ns-name n)) (name nm)))]
      (println "Spec")
      (doseq [role [:args :ret :fn]]
        (when-let [spec (get fnspec role)]
          (println " " (str (name role) ":") (spec/describe spec)))))))

(defn find-doc
  "Prints documentation for any var whose documentation or name
 contains a match for re-string-or-pattern"
  {:added "1.0"}
  [re-string-or-pattern]
    (let [re (re-pattern re-string-or-pattern)
          ms (concat (mapcat #(sort-by :name (map meta (vals (ns-interns %))))
                             (all-ns))
                     (map namespace-doc (all-ns))
                     (map special-doc (keys special-doc-map)))]
      (doseq [m ms
              :when (and (:doc m)
                         (or (re-find (re-matcher re (:doc m)))
                             (re-find (re-matcher re (str (:name m))))))]
               (print-doc m))))

(defmacro doc
  "Prints documentation for a var or special form given its name,
   or for a spec if given a keyword"
  {:added "1.0"}
  [name]
  (if-let [special-name ('{& fn catch try finally try} name)]
    `(#'print-doc (#'special-doc '~special-name))
    (cond
      (special-doc-map name) `(#'print-doc (#'special-doc '~name))
      (keyword? name) `(#'print-doc {:spec '~name :doc '~(spec/describe name)})
      (find-ns name) `(#'print-doc (#'namespace-doc (find-ns '~name)))
      (resolve name) `(#'print-doc (meta (var ~name))))))

;; ----------------------------------------------------------------------
;; Examine Clojure functions (Vars, really)

(defn source-fn
  "Returns a string of the source code for the given symbol, if it can
find it. This requires that the symbol resolve to a Var defined in
a namespace for which the .clj is in the classpath. Returns nil if
it can't find the source. For most REPL usage, 'source' is more
convenient.

Example: (source-fn 'filter)"
  [x]
  (when-let [v (resolve x)]
    (when-let [filepath (:file (meta v))]
      (when-let [ ^System.IO.FileInfo info (clojure.lang.RT/FindFile filepath) ]    ;;; [strm (.getResourceAsStream (RT/baseLoader) filepath)]
        (with-open [ ^System.IO.TextReader rdr (.OpenText info)]                    ;;; [rdr (LineNumberReader. (InputStreamReader. strm))]
          (dotimes [_ (dec (:line (meta v)))] (.ReadLine rdr))                      ;;; .readLine
          (let [text (StringBuilder.)
                pbr (proxy [clojure.lang.PushbackTextReader] [rdr]                  ;;; [PushbackReader] [rdr]
                      (Read [] (let [i (proxy-super Read)]                          ;;; read read
                                 (.Append text (char i))                            ;;; .append
                                 i)))
                read-opts (if (.EndsWith ^String filepath "cljc") {:read-cond :allow} {})]                   ;;; .endsWith
            (if (= :unknown *read-eval*)
              (throw (InvalidOperationException. "Unable to read source while *read-eval* is :unknown."))    ;;; IllegalStateException
              (read read-opts (clojure.lang.PushbackTextReader. pbr)))                                       ;;; (read read-opts(PushbackReader. pbr))
            (str text)))))))

(defmacro source
  "Prints the source code for the given symbol, if it can find it.
  This requires that the symbol resolve to a Var defined in a
  namespace for which the .clj is in the classpath.

  Example: (source filter)"
  [n]
  `(println (or (source-fn '~n) (str "Source not found"))))

(defn apropos
  "Given a regular expression or stringable thing, return a seq of all
public definitions in all currently-loaded namespaces that match the
str-or-pattern."
  [str-or-pattern]
  (let [matches? (if (instance? System.Text.RegularExpressions.Regex str-or-pattern)    ;;; java.util.regex.Pattern
                   #(re-find str-or-pattern (str %))
                   #(.Contains (str %) (str str-or-pattern)))]                          ;;; .contains
    (sort (mapcat (fn [ns]
                    (let [ns-name (str ns)]
                      (map #(symbol ns-name (str %))
                           (filter matches? (keys (ns-publics ns))))))
                  (all-ns)))))

(defn dir-fn
  "Returns a sorted seq of symbols naming public vars in
  a namespace or namespace alias. Looks for aliases in *ns*"
  [ns]
  (sort (map first (ns-publics (the-ns (get (ns-aliases *ns*) ns ns))))))

(defmacro dir
  "Prints a sorted directory of public vars in a namespace"
  [nsname]
  `(doseq [v# (dir-fn '~nsname)]
     (println v#)))

(defn demunge
  "Given a string representation of a fn class,
  as in a stack trace element, returns a readable version."
  {:added "1.3"}
  [fn-name]
  (clojure.lang.Compiler/demunge fn-name))

(defn root-cause
  "Returns the initial cause of an exception or error by peeling off all of
  its wrappers"
  [ ^Exception t]                     ;;; ^Throwable
  (loop [cause t]
    (if (and (instance? clojure.lang.Compiler+CompilerException cause)
	         (not= (.Source ^clojure.lang.Compiler+CompilerException cause) "NO_SOURCE_FILE"))  ;;; .source
      cause
	  (if-let [cause (.InnerException cause)]    ;;; .getCause
        (recur cause)
        cause))))

;;;  Added -DM

(defn get-stack-trace 
  "Gets the stack trace for an Exception"
  [^Exception e]
  (.GetFrames (System.Diagnostics.StackTrace. e true)))

(defn stack-element-classname
  [^System.Diagnostics.StackFrame el]
  (if-let [t (.. el  (GetMethod) ReflectedType)] 
    (.FullName t) 
	""))

(defn stack-element-methodname
  [^System.Diagnostics.StackFrame el]
  (.. el (GetMethod)  Name))

;;;


(defn stack-element-str
  "Returns a (possibly unmunged) string representation of a StackTraceElement"
  {:added "1.3"}
  [^System.Diagnostics.StackFrame el]                                                   ;;; StackTraceElement
  (let [file (.GetFileName el)                                                          ;;; getFileName
        clojure-fn? (and file (or (.EndsWith file ".clj")                               ;;; endsWith
		                          (.EndsWith file ".cljc") (.EndsWith ".cljr")          ;;; endsWith  + DM: Added .cljr
                                  (= file "NO_SOURCE_FILE")))]
    (str (if clojure-fn?
           (demunge (stack-element-classname el))                              ;;; (.getClassName el))
           (str (stack-element-classname el) "." (stack-element-methodname el)))   ;;; (.getClassName el)  (.getMethodName el)
         " (" (.GetFileName el) ":" (.GetFileLineNumber el) ")")))        ;;; getFileName  getLineNumber

(defn pst
  "Prints a stack trace of the exception, to the depth requsted. If none supplied, uses the root cause of the
  most recent repl exception (*e), and a depth of 12."
  {:added "1.3"}
  ([] (pst 12))
  ([e-or-depth]
     (if (instance? Exception e-or-depth)                                         ;;; Throwable
	   (pst e-or-depth 12)
       (when-let [e *e]
	     (pst (root-cause e) e-or-depth))))
  ([^Exception e depth]                                                            ;;; Throwable
     (binding [*out* *err*]
       (when (#{:read-source :macro-syntax-check :macroexpansion :compile-syntax-check :compilation}
               (-> e ex-data :clojure.error/phase))
         (println "Note: The following stack trace applies to the reader or compiler, your code was not executed."))
       (println (str (-> e class .Name) " "                                        ;;; .getSimpleName 
	                  (.Message e)                                                 ;;; getMessage
					  (when-let [info (ex-data e)] (str " " (pr-str info)))))
       (let [st  (get-stack-trace e)                                               ;;; (.getStackTrace e)
	         cause (.InnerException e)]                                            ;;; .getCause
	     (doseq [el (take depth
	                      (remove #(#{"clojure.lang.RestFn" "clojure.lang.AFn" "clojure.lang.AFnImpl" "clojure.lang.RestFnImpl"}	(stack-element-classname %))   ;;;  (.getClassName %)
			  			        st))]
           (println (str \tab (stack-element-str el))))
         (when cause
           (println "Caused by:")
           (pst cause (min depth
	                       (+ 2 (- (count (get-stack-trace cause))    ;;; (.getStackTrace cause)
			    		           (count st))))))))))

