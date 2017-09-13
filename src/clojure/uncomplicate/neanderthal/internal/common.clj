;;   Copyright (c) Dragan Djuric. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php) or later
;;   which can be found in the file LICENSE at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns uncomplicate.neanderthal.internal.common
  (:require [uncomplicate.fluokitten.core :refer [fold]]
            [uncomplicate.commons.core :refer [Releaseable release let-release double-fn]]
            [uncomplicate.neanderthal.math :refer [f=]]
            [uncomplicate.neanderthal.internal.api :refer :all])
  (:import [uncomplicate.neanderthal.internal.api Matrix Vector Region RealBufferAccessor
            MatrixImplementation LayoutNavigator Block DiagonalMatrix]))

(defn dragan-says-ex
  ([message data]
   (throw (ex-info (format "Dragan says: %s" message) data)))
  ([message]
   (dragan-says-ex message {})))

(defn check-stride ^Block [^Block x]
  (if (= 1 (.stride x))
    x
    (dragan-says-ex "You cannot use vector with stride different than 1." {:stride (.stride x)})))

;; ================= Core Functions ===================================

(defn dense-rows [^Matrix a]
  (map #(.row a %) (range (.mrows a))))

(defn dense-cols [^Matrix a]
  (map #(.col a %) (range (.ncols a))))

(defn dense-dias [^Matrix a]
  (map #(.dia a %) (range (dec (.ncols a)) (- (.mrows a)) -1)))

(defn region-rows [^Matrix a]
  (map #(.row a %) (range (min (.mrows a) (+ (min (.mrows a) (.ncols a)) (.kl (region a)))))))

(defn region-cols [^Matrix a]
  (map #(.col a %) (range (min (.ncols a) (+ (min (.mrows a) (.ncols a)) (.ku (region a)))))))

(defn region-dias [^Matrix a]
  (let [reg (region a)]
    (map #(.dia a %) (range (.ku reg) (- (inc (.kl reg))) -1))))

(defn ^RealBufferAccessor real-accessor [a]
  (data-accessor a))

;; ======================== LU factorization ==========================================

(def ^:private f* (double-fn *))
(def ^:private falsify (constantly false))

(defn ^:private stale-factorization [& args]
  (throw (ex-info "Cannot compute with a stale factorization. Decompose the original matrix again." {})))

(defn ^:private nrm-needed-for-con []
  (throw (ex-info "Cannot compute condition number without nrm." {})))

(defrecord LUFactorization [^Matrix lu ^Vector ipiv ^Boolean master fresh]
  Releaseable
  (release [_]
    (when master (release lu))
    (release ipiv))
  Info
  (info [this]
    this)
  TRF
  (trtrs [_ b]
    (if @fresh
      (let-release [res (raw b)]
        (copy (engine b) b res)
        (trs (engine lu) lu res ipiv))
      (stale-factorization)))
  (trtrs! [_ b]
    (if @fresh
      (trs (engine lu) lu b ipiv)
      (stale-factorization)))
  (trtri! [_]
    (if (compare-and-set! fresh true false)
      (tri (engine lu) lu ipiv)
      (stale-factorization)))
  (trtri [_]
    (if @fresh
      (let-release [res (raw lu)]
        (let [eng (engine lu)]
          (copy eng lu res)
          (tri eng res ipiv))
        res)
      (stale-factorization)))
  (trcon [_ nrm nrm1?]
    (if @fresh
      (con (engine lu) lu ipiv nrm nrm1?)
      (stale-factorization)))
  (trcon [_ nrm1?]
    (nrm-needed-for-con))
  (trdet [_]
    (if @fresh
      (let [res (double (fold f* 1.0 (.dia lu)))]
        (if (even? (.dim ipiv))
          res
          (- res)))
      (stale-factorization)))
  Matrix
  (mrows [_]
    (.mrows lu))
  (ncols [_]
    (.ncols lu))
  MemoryContext
  (compatible? [_ b]
    (compatible? lu b))
  (fits? [_ b]
    (fits? lu b))
  (fits-navigation? [_ b]
    (fits-navigation? lu b)))

(defn lu-factorization [^Matrix a pure]
  (let [eng (engine a)]
    (let-release [ipiv (create-vector (index-factory a) (min (.mrows a) (.ncols a)) false)]
      (if pure
        (let-release [a-copy (raw a)]
          (copy eng a a-copy)
          (trf eng a-copy ipiv)
          (->LUFactorization a-copy ipiv true (atom true)))
        (do
          (trf eng a ipiv)
          (->LUFactorization a ipiv false (atom true)))))))

(defrecord PivotlessLUFactorization [^Matrix lu ^Boolean master fresh]
  Releaseable
  (release [_]
    (if master (release lu) true))
  Info
  (info [this]
    this)
  TRF
  (trtrs [_ b]
    (if @fresh
      (let-release [res (create-ge (factory b) (.mrows ^Matrix b) (.ncols ^Matrix b)
                                   (if (= :sb (.matrixType ^MatrixImplementation lu))
                                     true
                                     (.isColumnMajor (navigator b)))
                                   false)]
        (copy (engine b) b res)
        (trs (engine lu) lu res))
      (stale-factorization)))
  (trtrs! [_ b]
    (if @fresh
      (trs (engine lu) lu b)
      (stale-factorization)))
  (trtri! [_]
    (if (compare-and-set! fresh true false)
      (tri (engine lu) lu)
      (stale-factorization)))
  (trtri [_]
    (if @fresh
      (let-release [res (raw lu)]
        (let [eng (engine lu)]
          (tri eng (copy eng lu res)))
        res)
      (stale-factorization)))
  (trcon [_ nrm nrm1?]
    (if @fresh
      (con (engine lu) lu nrm nrm1?)
      (stale-factorization)))
  (trcon [_ nrm1?]
    (nrm-needed-for-con))
  (trdet [_]
    (if @fresh
      (let [dia-lu (.dia lu)
            res (double (fold f* 1.0 dia-lu))]
        (if (even? (.dim dia-lu))
          res
          (- res)))
      (stale-factorization)))
  Matrix
  (mrows [_]
    (.mrows lu))
  (ncols [_]
    (.ncols lu))
  MemoryContext
  (compatible? [_ b]
    (compatible? lu b))
  (fits? [_ b]
    (fits? lu b))
  (fits-navigation? [_ b]
    (fits-navigation? lu b)))

(defn pivotless-lu-factorization [a pure]
  (let [eng (engine a)]
    (if pure
      (let-release [a-copy (raw a)]
        (copy eng a a-copy)
        (trf eng a-copy)
        (->PivotlessLUFactorization a-copy true (atom true)))
      (do
        (trf eng a)
        (->PivotlessLUFactorization a false (atom true))))))

(defn dual-lu-factorization [^Matrix a pure]
  (if pure
    (let [eng (engine a)]
      (let-release [a-copy (raw a)]
        (copy eng a a-copy)
        (if (= 0 (trfx eng a-copy))
          (->PivotlessLUFactorization a-copy true (atom true))
          (let-release [ipiv (create-vector (index-factory a) (min (.mrows a) (.ncols a)) false)]
            (copy eng a a-copy)
            (trf eng a-copy ipiv)
            (->LUFactorization a-copy ipiv true (atom true))))))
    (lu-factorization a false)))

(defrecord SVDecomposition [^DiagonalMatrix sigma ^Matrix u ^Matrix vt ^Boolean master]
  Releaseable
  (release [_]
    (release sigma)
    (release u)
    (release vt))
  Info
  (info [this]
    this))

(defrecord OrthogonalFactorization [eng ^Matrix or ^Vector tau ^Boolean master fresh
                                    ^long m ^long n or-type api-orm api-org]
  Releaseable
  (release [_]
    (when master (release or))
    (release tau))
  Info
  (info [this]
    {:or-type or-type
     :or (info or)
     :tau (info tau)
     :master master
     :fresh @fresh})
  EngineProvider
  (engine [this]
    this)
  Blas
  (mm [_ alpha a b left]
    (if @fresh
      (do
        (api-orm eng or tau b left)
        (when-not (f= 1.0 alpha)
          (scal (engine b) alpha b))
        b)
      (stale-factorization)))
  ORF
  (org! [_]
    (if (compare-and-set! fresh true false)
      (api-org eng or tau)
      (stale-factorization)))
  (org [_]
    (if @fresh
      (let-release [res (raw or)]
        (copy eng or res)
        (api-org eng res tau))
      (stale-factorization)))
  Matrix
  (mrows [_]
    m)
  (ncols [_]
    n)
  DataAccessorProvider
  (data-accessor [_]
    (data-accessor or))
  MemoryContext
  (compatible? [_ b]
    (compatible? or b))
  (fits? [_ b]
    (fits? or b))
  (fits-navigation? [_ b]
    (fits-navigation? or b)))

(defn ^:private min-mn ^long [^Matrix a]
  (max 1 (min (.mrows a) (.ncols a))))

(defn qr-factorization [^Matrix a master qrf-fn]
  (let [eng (engine a)]
    (let-release [tau (create-vector (factory a) (min-mn a) false)]
      (qrf eng a tau)
      (OrthogonalFactorization. eng a tau master (atom true) (.mrows a) (.mrows a) :qr mqr gqr))))

(defn rq-factorization [^Matrix a master]
  (let [eng (engine a)]
    (let-release [tau (create-vector (factory a) (min-mn a) false)]
      (rqf eng a tau)
      (OrthogonalFactorization. eng a tau master (atom true) (.ncols a) (.ncols a) :rq mrq grq))))

(defn ql-factorization [^Matrix a master]
  (let [eng (engine a)]
    (let-release [tau (create-vector (factory a) (min-mn a) false)]
      (qlf eng a tau)
      (OrthogonalFactorization. eng a tau master (atom true) (.mrows a) (.ncols a) :ql mql gql))))

(defn lq-factorization [^Matrix a master]
  (let [eng (engine a)]
    (let-release [tau (create-vector (factory a) (min-mn a) false)]
      (lqf eng a tau)
      (OrthogonalFactorization. eng a tau master (atom true) (.ncols a) (.ncols a) :lq mlq glq))))
