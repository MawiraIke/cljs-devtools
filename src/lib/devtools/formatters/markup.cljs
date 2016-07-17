(ns devtools.formatters.markup
  (:require-macros [devtools.util :refer [oget oset ocall oapply safe-call]]
                   [devtools.formatters.markup :refer [emit-markup-map]])
  (:require [cljs.pprint]
            [devtools.formatters.helpers :refer [bool? cljs-function? pref abbreviate-long-string cljs-type? cljs-instance?
                                                 instance-of-a-well-known-type? get-constructor]]
            [devtools.formatters.printing :refer [managed-pr-str managed-print-via-protocol]]
            [devtools.munging :as munging]))

; reusable hiccup-like templates

(declare get-markup-map)

; -- helpers ----------------------------------------------------------------------------------------------------------------
; TODO: move these into helpers namespace

(defn- get-more-marker [more-count]
  (str (pref :plus-symbol) more-count (pref :more-symbol)))

(defn- wrap-arity [arity]
  (let [args-open-symbol (pref :args-open-symbol)
        args-close-symbol (pref :args-close-symbol)]
    (str args-open-symbol arity args-close-symbol)))

(defn- fetch-field-value [obj field]
  [field (oget obj (munge field))])

(defn- fetch-fields-values [obj fields]
  (map (partial fetch-field-value obj) fields))

; -- references -------------------------------------------------------------------------------------------------------------

(defn <surrogate> [& args]
  (concat ["surrogate"] args))

(defn <reference> [& args]
  (concat ["reference"] args))

(defn <reference-surrogate> [& args]
  (<reference> (apply <surrogate> args)))

(defn <circular-reference> [& children]
  (concat [:circular-reference-tag :circular-ref-icon] children))

(defn <native-reference> [object]
  (let [reference (<reference> object {:prevent-recursion true})]
    [:native-reference-tag :native-reference-background reference]))

; -- simple markup ----------------------------------------------------------------------------------------------------------

(defn <cljs-land> [& children]
  (concat [:cljs-land-tag] children))

(defn <nil> []
  [:nil-tag :nil-label])

(defn <bool> [bool]
  [:bool-tag bool])

(defn <keyword> [keyword]
  [:keyword-tag (str keyword)])

(defn <symbol> [symbol]
  [:symbol-tag (str symbol)])

(defn <number> [number]
  (if (integer? number)
    [:integer-tag number]
    [:float-tag number]))

; -- string markup ----------------------------------------------------------------------------------------------------------

(defn <string> [string]
  (let [dq (pref :dq)
        re-nl (js/RegExp. "\n" "g")
        nl-marker (pref :new-line-string-replacer)
        inline-string (.replace string re-nl nl-marker)
        max-inline-string-size (+ (pref :string-prefix-limit) (pref :string-postfix-limit))
        quote-string (fn [s] (str dq s dq))
        should-abbreviate? (> (count inline-string) max-inline-string-size)]
    (if should-abbreviate?
      (let [abbreviated-string (abbreviate-long-string inline-string
                                                       (pref :string-abbreviation-marker)
                                                       (pref :string-prefix-limit)
                                                       (pref :string-postfix-limit))
            abbreviated-string-markup [:string-tag (quote-string abbreviated-string)]
            string-with-nl-markers (.replace string re-nl (str nl-marker "\n"))
            details-markup [:expanded-string-tag string-with-nl-markers]]
        (<reference-surrogate> string abbreviated-string-markup true details-markup))
      [:string-tag (quote-string inline-string)])))

; -- generic preview markup -------------------------------------------------------------------------------------------------

(defn <preview> [value]
  (managed-pr-str value :header-style (pref :max-print-level) (get-markup-map)))

; -- body-related templates -------------------------------------------------------------------------------------------------

(defn <aligned-body> [markups-lists]
  (let [prepend-li-tag (fn [line]
                         (if line
                           (concat [:aligned-li-tag] line)))
        aligned-lines (keep prepend-li-tag markups-lists)]
    [:body-tag
     (concat [:standard-ol-no-margin-tag] aligned-lines)]))

(defn <standard-body> [markups-lists & [no-margin?]]
  (let [ol-tag (if no-margin? :standard-ol-no-margin-tag :standard-ol-tag)
        li-tag (if no-margin? :standard-li-no-margin-tag :standard-li-tag)
        prepend-li-tag (fn [line]
                         (if line
                           (concat [li-tag] line)))
        lines-markups (keep prepend-li-tag markups-lists)]
    (concat [ol-tag] lines-markups)))

(defn <standard-body-reference> [o]
  (<standard-body> [[(<reference> o)]]))

; -- generic details markup -------------------------------------------------------------------------------------------------

(defn <index> [value]
  [:index-tag value :line-index-separator])

(defn- body-line [index value]
  [(<index> index) (managed-pr-str value :item-style (pref :body-line-max-print-level) (get-markup-map))])

; TODO: this fn is screaming for rewrite
(defn- prepare-body-lines [data starting-index]
  (loop [work data
         index starting-index
         lines []]
    (if (empty? work)
      lines
      (recur (rest work) (inc index) (conj lines (body-line index (first work)))))))

(defn- body-lines [value starting-index]
  (let [seq (seq value)
        max-number-body-items (pref :max-number-body-items)
        chunk (take max-number-body-items seq)
        rest (drop max-number-body-items seq)
        lines (prepare-body-lines chunk starting-index)
        continue? (not (empty? (take 1 rest)))]
    (if-not continue?
      lines
      (let [more-label-markup [:body-items-more-tag (pref :body-items-more-label)]
            start-index (+ starting-index max-number-body-items)
            more-markup (<reference-surrogate> rest more-label-markup true nil start-index)]
        (conj lines [more-markup])))))

(defn <details> [value starting-index]
  (let [has-continuation? (pos? starting-index)
        body-markup (<standard-body> (body-lines value starting-index) has-continuation?)]
    (if has-continuation?
      body-markup
      [:body-tag body-markup])))

; -- generic list template --------------------------------------------------------------------------------------------------

(defn <list-details> [items _opts]
  (<aligned-body> (map list items)))

(defn <list> [items max-count & [opts]]
  (let [items-markups (take max-count items)
        more-count (- (count items) max-count)
        more? (pos? more-count)
        separator (or (:separator opts) :list-separator)
        more-symbol (if more?
                      (if-let [more-symbol (:more-symbol opts)]
                        (if (fn? more-symbol)
                          (more-symbol more-count)
                          more-symbol)
                        (get-more-marker more-count)))
        preview-markup (concat [(or (:tag opts) :list-tag)
                                (or (:open-symbol opts) :list-open-symbol)]
                               (interpose separator items-markups)
                               (if more? [separator more-symbol])
                               [(or (:close-symbol opts) :list-close-symbol)])]
    (if more?
      (let [details-markup (:details opts)
            default-details-fn (partial <list-details> items opts)]
        (<reference-surrogate> nil preview-markup true (or details-markup default-details-fn)))
      preview-markup)))

; -- mete-related markup ----------------------------------------------------------------------------------------------------

(defn <meta> [metadata]
  (let [body [:meta-body-tag (<preview> metadata)]
        header [:meta-header-tag "meta"]]
    [:meta-reference-tag (<reference-surrogate> metadata header true body)]))

(defn <meta-wrapper> [metadata & children]
  (concat [:meta-wrapper-tag] children [(<meta> metadata)]))

; -- function markup --------------------------------------------------------------------------------------------------------

(defn <function-details> [fn-obj ns _name arities prefix]
  {:pre [(fn? fn-obj)]}
  (let [arities (map wrap-arity arities)
        make-arity-markup-list (fn [arity]
                                 [[:fn-multi-arity-args-indent-tag prefix]
                                  [:fn-args-tag arity]])
        arities-markupts-lists (if (> (count arities) 1) (map make-arity-markup-list arities))
        ns-markups-list (if-not (empty? ns) [:ns-icon [:fn-ns-name-tag ns]])
        native-markups-list [:native-icon (<native-reference> fn-obj)]]
    (<aligned-body> (concat arities-markupts-lists [ns-markups-list native-markups-list]))))

(defn <arities> [arities]
  (let [multi-arity? (> (count arities) 1)]
    [:fn-args-tag (wrap-arity (if multi-arity?
                                (pref :multi-arity-symbol)
                                (first arities)))]))

(defn <function> [fn-obj]
  {:pre [(fn? fn-obj)]}
  (let [[ns name] (munging/parse-fn-info fn-obj)
        lambda? (empty? name)
        spacer-symbol (pref :spacer)
        rest-symbol (pref :rest-symbol)
        multi-arity-symbol (pref :multi-arity-symbol)
        arities (munging/extract-arities fn-obj true spacer-symbol multi-arity-symbol rest-symbol)
        arities-markup (<arities> arities)
        name-markup (if-not lambda? [:fn-name-tag name])
        icon-markup (if lambda? :lambda-icon :fn-icon)
        prefix-markup [:fn-prefix-tag icon-markup name-markup]
        preview-markup [:fn-header-tag prefix-markup arities-markup]
        details-fn (partial <function-details> fn-obj ns name arities prefix-markup)]
    (<reference-surrogate> fn-obj preview-markup true details-fn)))

; -- type markup ------------------------------------------------------------------------------------------------------------

(defn <type-basis-item> [basis-item]
  [:type-basis-item-tag (name basis-item)])

(defn <type-basis> [basis]
  (let [item-markups (map <type-basis-item> basis)
        children-markups (interpose :type-basis-item-separator item-markups)]
    (concat [:type-basis-tag] children-markups)))

(defn <type-details> [constructor-fn ns _name basis]
  (let [ns-markup (if-not (empty? ns) [:ns-icon [:fn-ns-name-tag ns]])
        basis-markup (if-not (empty? basis) [:basis-icon (<type-basis> basis)])
        native-markup [:native-icon (<native-reference> constructor-fn)]]
    (<aligned-body> [basis-markup ns-markup native-markup])))

(defn <type> [constructor-fn & [header-style]]
  (let [[ns name basis] (munging/parse-constructor-info constructor-fn)
        name-markup [:type-name-tag name]
        preview-markup [[:span (or header-style :type-header-style)] :type-symbol name-markup]
        details-markup-fn (partial <type-details> constructor-fn ns name basis)]
    [:type-wrapper-tag
     :type-header-background
     [:type-ref-tag (<reference-surrogate> constructor-fn preview-markup true details-markup-fn)]]))

(defn <standalone-type> [constructor-fn & [header-style]]
  [:standalone-type-tag (<type> constructor-fn header-style)])

; -- protocols markup -------------------------------------------------------------------------------------------------------

(defn <protocol-method-arity> [arity-fn]
  (<reference> arity-fn))

(defn <protocol-method-arities-details> [fns]
  (<aligned-body> (map <protocol-method-arity> fns)))

(defn <protocol-method-arities> [fns & [max-fns]]
  (let [max-fns (or max-fns (pref :max-protocol-method-arities-list))
        more? (> (count fns) max-fns)
        aritites-markups (map <protocol-method-arity> (take max-fns fns))
        preview-markup (concat [:protocol-method-arities-header-tag :protocol-method-arities-header-open-symbol]
                               (interpose :protocol-method-arities-list-header-separator aritites-markups)
                               (if more? [:protocol-method-arities-more-symbol])
                               [:protocol-method-arities-header-close-symbol])]
    (if more?
      (let [details-markup-fn (partial <protocol-method-arities-details> fns)]
        (<reference-surrogate> nil preview-markup true details-markup-fn))
      preview-markup)))

(defn <protocol-method> [name arities]
  [:protocol-method-tag
   :method-icon
   [:protocol-method-name-tag name]
   (<protocol-method-arities> arities)])

(defn <protocol-details> [obj ns _name selector _fast?]
  (let [protocol-obj (munging/get-protocol-object selector)
        ns-markups-list (if-not (empty? ns) [:ns-icon [:protocol-ns-name-tag ns]])
        native-markups-list (if (some? protocol-obj) [:native-icon (<native-reference> protocol-obj)])
        methods (munging/collect-protocol-methods obj selector)
        methods-markups (map (fn [[name arities]] (<protocol-method> name arities)) methods)
        methods-markups-lists (map list methods-markups)]
    (<aligned-body> (concat methods-markups-lists [ns-markups-list native-markups-list]))))

(defn <protocol> [obj protocol & [style]]
  (let [{:keys [ns name selector fast?]} protocol
        preview-markup [[:span (or style :protocol-name-style)] name]
        prefix-markup [[:span (if fast? :fast-protocol-style :slow-protocol-style)] :protocol-background]]
    (if (some? obj)
      (let [details-markup-fn (partial <protocol-details> obj ns name selector fast?)]
        (conj prefix-markup (<reference-surrogate> obj preview-markup true details-markup-fn)))
      (conj prefix-markup preview-markup))))

(defn <more-protocols> [more-count]
  (let [fake-protocol {:name (get-more-marker more-count)}]
    (<protocol> nil fake-protocol :protocol-more-style)))

(defn <protocols-list> [obj protocols & [max-protocols]]
  (let [max-protocols (or max-protocols (pref :max-list-protocols))
        protocols-markups (map (partial <protocol> obj) protocols)]
    (<list> protocols-markups max-protocols {:tag          :protocols-header-tag
                                             :open-symbol  :protocols-list-open-symbol
                                             :close-symbol :protocols-list-close-symbol
                                             :separator    :header-protocol-separator
                                             :more-symbol  <more-protocols>})))

; -- instance fields markup -------------------------------------------------------------------------------------------------

(defn <field> [name value]
  [:header-field-tag
   [:header-field-name-tag (str name)]
   :header-field-value-spacer
   [:header-field-value-tag (<reference> value)]
   :header-field-separator])

(defn <fields-details-row> [field]
  (let [[name value] field]
    [:body-field-tr-tag
     [:body-field-td1-tag
      :body-field-symbol
      [:body-field-name-tag (str name)]]
     [:body-field-td2-tag
      :body-field-value-spacer
      [:body-field-value-tag (<reference> value)]]]))

(defn <fields> [fields & [max-fields]]
  (let [max-fields (or max-fields (pref :max-instance-header-fields))
        more? (> (count fields) max-fields)
        fields-markups (map (fn [[name value]] (<field> name value)) (take max-fields fields))]
    (concat [:fields-header-tag
             :fields-header-open-symbol]
            fields-markups
            [(if more? :more-fields-symbol)
             :fields-header-close-symbol])))

(defn <fields-details> [fields obj]
  (let [protocols (munging/scan-protocols obj)
        has-protocols? (not (empty? protocols))
        fields-markup [:fields-icon (concat [:instance-body-fields-table-tag] (map <fields-details-row> fields))]
        protocols-list-markup (if has-protocols? [:protocols-icon (<protocols-list> obj protocols)])
        native-markup [:native-icon (<native-reference> obj)]]
    (<aligned-body> [fields-markup protocols-list-markup native-markup])))

; -- type/record instance markup --------------------------------------------------------------------------------------------

(defn <instance> [value]
  (let [constructor-fn (get-constructor value)
        [_ns _name basis] (munging/parse-constructor-info constructor-fn)
        custom-printing? (implements? IPrintWithWriter value)
        type-markup (<type> constructor-fn :instance-type-header-style)
        fields (fetch-fields-values value basis)
        fields-markup (<fields> fields (if custom-printing? 0))                                                               ; TODO: handle no fields properly
        fields-details-markup-fn #(<fields-details> fields value)
        fields-preview-markup [:instance-value-tag (<reference-surrogate> value fields-markup true fields-details-markup-fn)]
        custom-printing-markup (if custom-printing?
                                 [:instance-custom-printing-wrapper-tag
                                  :instance-custom-printing-background
                                  (managed-print-via-protocol value :instance-custom-printing-style (get-markup-map))])
        preview-markup [:instance-header-tag
                        type-markup
                        :instance-value-separator
                        fields-preview-markup
                        custom-printing-markup]]
    (<reference-surrogate> value preview-markup false)))

; ---------------------------------------------------------------------------------------------------------------------------

(defn <atomic> [value]
  (cond
    (nil? value) (<nil>)
    (bool? value) (<bool> value)
    (string? value) (<string> value)
    (number? value) (<number> value)
    (keyword? value) (<keyword> value)
    (symbol? value) (<symbol> value)
    (and (cljs-instance? value) (not (instance-of-a-well-known-type? value))) (<instance> value)
    (cljs-type? value) (<standalone-type> value)
    (cljs-function? value) (<function> value)))

; ---------------------------------------------------------------------------------------------------------------------------

(def ^:dynamic *markup-map* nil)

; emit-markup-map macro will generate a map of all <functions> in this namespace:
;
;    {:atomic              <atomic>
;     :reference           <reference>
;     :native-reference    <native-reference>
;     ...}
;
; we generate it only on first call and cache it in *markup-map*
; emitting markup map statically into def would prevent dead-code elimination
;
(defn get-markup-map []
  (if (nil? *markup-map*)
    (set! *markup-map* (emit-markup-map)))
  *markup-map*)
