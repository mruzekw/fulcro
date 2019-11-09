(ns com.fulcrologic.fulcro.networking.file-upload
  (:require [edn-query-language.core :as eql]
            [taoensso.timbre :as log]
            [taoensso.encore :as enc]
            [clojure.string :as str]
            [clojure.tools.reader.edn :as edn]
            [com.fulcrologic.fulcro.algorithms.transit :as transit]))

(defn- upload-transaction [req]
  (or
    (get-in req [:params :upload-transaction])
    (get-in req [:params "upload-transaction"])))

(defn- upload-files [req]
  (when-let [files (or
                     (get-in req [:multipart-params "files"])
                     (get-in req [:multipart-params :files]))]
    (if (map? files)
      [files]
      files)))

(defn- transaction-with-uploads?
  [req]
  (string? (upload-transaction req)))

(defn- attach-uploads-to-txn
  [txn files]
  (let [ast             (eql/query->ast txn)
        mutation->files (reduce (fn [result {:keys [filename] :as file}]
                                  (enc/if-let [[mutation-name filename] (some-> filename (str/split #"[|]"))
                                               mutation-sym (some-> mutation-name (edn/read-string))]
                                    (update result mutation-sym (fnil conj []) (assoc file :filename filename))
                                    (do
                                      (log/error "Unable to associate a file with a mutation" file)
                                      result)))
                          {}
                          files)
        new-ast         (update ast :children
                          #(mapv
                             (fn [{:keys [dispatch-key] :as n}]
                               (if (contains? mutation->files dispatch-key)
                                 (assoc-in n [:params ::files] (mutation->files dispatch-key))
                                 n))
                             %))]
    (eql/ast->query new-ast)))

(defn wrap-mutation-file-uploads
  "Optional middleware that enables the server to handle mutations that attach file uploads. This must be composed after
   Ring middleware for keyword params (higher in the composition), but before the API handling (lower in the composition).

   ```
   (->
      ...
      (wrap-api)
      (wrap-file-uploads)
      (wrap-keyword-params)
      ...)
   ```
   "
  [handler transit-options]
  (fn [req]
    (if (transaction-with-uploads? req)
      (let [txn   (-> req
                    (upload-transaction)
                    (transit/transit-str->clj transit-options))
            files (log/spy :info (upload-files req))
            txn   (attach-uploads-to-txn txn files)]
        (when-not (seq files)
          (log/error "Incoming transaction with uploads had no files attached."))
        (handler (-> req
                   (dissoc :params :multipart-params)
                   (assoc :transit-params txn))))
      (handler req))))
