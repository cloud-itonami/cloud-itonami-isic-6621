(ns adjustment.adjusterllm
  "Adjuster-LLM client -- the *contained intelligence node* for the
  independent loss-adjustment actor.

  It normalizes matter intake, drafts a per-jurisdiction valuation-
  methodology/evidence checklist, screens the assigned adjuster for a
  conflict of interest, and drafts the valuation-finalization action.
  CRITICAL: it is a smart-but-untrusted advisor. It returns a *proposal*
  (with a rationale + the fields it cited), never a committed record or
  a real valuation report. Every output is censored downstream by
  `adjustment.governor` before anything touches the SSoT, and
  `:valuation/finalize` proposals NEVER auto-commit at any phase -- see
  README `Actuation`.

  Like `cloud-itonami-isic-6511`'s `underwriting.underwriterllm` /
  `cloud-itonami-isic-6512`'s `casualty.underwriterllm`, this is a
  deterministic mock so the actor graph runs offline and the governor
  contract is exercised end-to-end. In production this calls a real LLM
  (kotoba-llm or equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the spec-basis gate
     :cites      [kw|str ..]    ; facts/sources the LLM used -- SCANNED too
     :effect     kw             ; how a commit would mutate the SSoT
     :stake      kw|nil         ; :actuation if it touches a real valuation
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [adjustment.facts :as facts]
            [adjustment.store :as store]
            [langchain.model :as model]))

(defn- normalize-intake
  "Directory upsert -- the LLM only normalizes/validates the patch; it
  does not invent the matter's case reference, subject, requesting
  party, assigned adjuster or jurisdiction. High confidence, low
  stakes."
  [_db {:keys [patch]}]
  {:summary    (str "案件レコード更新: " (pr-str (keys patch)))
   :rationale  "入力 patch の正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :matter/upsert
   :value      patch
   :stake      nil
   :confidence 0.97})

(defn- assess-jurisdiction
  "Per-jurisdiction valuation-methodology/evidence checklist draft.
  `:no-spec?` injects the failure mode we must defend against:
  proposing a checklist for a jurisdiction with NO official spec-basis
  in `adjustment.facts` -- the Loss Adjustment Governor must reject this
  (never invent a jurisdiction's methodology requirements)."
  [db {:keys [subject no-spec?]}]
  (let [m (store/matter db subject)
        iso3 (if no-spec? "ATL" (:jurisdiction m))
        sb (facts/spec-basis iso3)]
    (if (nil? sb)
      {:summary    (str iso3 " の公式spec-basisが見つかりません")
       :rationale  "adjustment.facts に未登録の法域。要件を推測で作らない。"
       :cites      []
       :effect     :assessment/set
       :value      {:jurisdiction iso3 :checklist [] :spec-basis nil}
       :stake      nil
       :confidence 0.9}
      {:summary    (str iso3 " (" (:owner-authority sb) ") 向け必要根拠資料 "
                        (count (:required-evidence sb)) " 件を提案")
       :rationale  (str "公式ソース: " (:provenance sb) " / 法的根拠: " (:legal-basis sb))
       :cites      [(:legal-basis sb) (:provenance sb)]
       :effect     :assessment/set
       :value      {:jurisdiction iso3
                    :checklist (:required-evidence sb)
                    :spec-basis (:provenance sb)
                    :legal-basis (:legal-basis sb)}
       :stake      nil
       :confidence 0.9})))

(defn- screen-conflict
  "Conflict-of-interest screening draft. `:conflict-hit?` on the party
  record injects the failure mode: the Loss Adjustment Governor must
  HOLD, un-overridably, on any conflict-of-interest hit. Missing
  disclosure yields low confidence -> escalate rather than auto-clear."
  [db {:keys [subject]}]
  (let [p (store/party db subject)]
    (cond
      (nil? p)
      {:summary "対象partyが見つかりません" :rationale "no party record"
       :cites [] :effect :conflict/set :value {:party-id subject :verdict :unknown}
       :stake nil :confidence 0.0}

      (:conflict-hit? p)
      {:summary    (str (:name p) ": 利益相反を検出")
       :rationale  "スクリーニングが利益相反を検出。人手確認とホールドが必須。"
       :cites      [:conflict-registry]
       :effect     :conflict/set
       :value      {:party-id subject :verdict :hit}
       :stake      nil
       :confidence 0.95}

      (nil? (:disclosure-doc p))
      {:summary    (str (:name p) ": 利益相反開示書類が未提出")
       :rationale  "開示書類が無いため確信度を上げられない。"
       :cites      [:disclosure-doc]
       :effect     :conflict/set
       :value      {:party-id subject :verdict :incomplete}
       :stake      nil
       :confidence 0.4}

      :else
      {:summary    (str (:name p) ": 利益相反なし、開示書類あり")
       :rationale  "開示書類確認 + 利益相反リスト非一致。"
       :cites      [:disclosure-doc :conflict-registry]
       :effect     :conflict/set
       :value      {:party-id subject :verdict :clear}
       :stake      nil
       :confidence 0.9})))

(defn- propose-finalize
  "Draft the actual valuation-finalization action -- issuing a real
  valuation report an insurer or insured will rely on for a real
  settlement. ALWAYS `:stake :actuation` -- this is a REAL-WORLD act,
  never a draft the actor may auto-run. See README `Actuation`: no phase
  ever adds this op to a phase's `:auto` set (`adjustment.phase`); the
  governor also always escalates on `:actuation`. Two independent
  layers agree, deliberately."
  [db {:keys [subject valuation-amount supporting-evidence]}]
  (let [m (store/matter db subject)
        assessment (store/assessment-of db subject)
        evidence-ok? (and assessment (facts/required-evidence-satisfied?
                                      (:jurisdiction m)
                                      (:checklist assessment)))]
    {:summary    (str (:case-reference m) " (" (:jurisdiction m)
                      ") の評価確定準備ができました" (when-not evidence-ok? " (根拠資料未充足)"))
     :rationale  (if assessment
                   (str "spec-basis: " (:spec-basis assessment))
                   "assessment未実施")
     :cites      (if assessment [(:spec-basis assessment)] [])
     :effect     :valuation/mark-finalized
     :value      {:matter-id subject :valuation-amount valuation-amount
                 :supporting-evidence supporting-evidence}
     :stake      :actuation
     :confidence (if evidence-ok? 0.9 0.3)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :matter/intake        (normalize-intake db request)
    :jurisdiction/assess   (assess-jurisdiction db request)
    :conflict/screen       (screen-conflict db request)
    :valuation/finalize    (propose-finalize db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは独立損害鑑定人の助言者です。与えられた事実のみに基づき、"
       "提案を1つだけEDNマップで返します。説明や前置きは一切書かず、"
       "EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:matter/upsert|:assessment/set|:conflict/set|:valuation/mark-finalized) "
       ":stake(:actuation か nil) :confidence(0..1)。\n"
       "重要: 登録されていない法域の要件を絶対に創作してはいけません。"
       "spec-basisが無い場合は :cites を空にし confidence を上げないこと。"))

(defn- facts-for [st {:keys [op subject]}]
  (case op
    :jurisdiction/assess {:matter (store/matter st subject)}
    :conflict/screen     {:party (store/party st subject)}
    :valuation/finalize  {:matter (store/matter st subject)
                         :assessment (store/assessment-of st subject)}
    {:matter (store/matter st subject)}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the Loss Adjustment Governor
  escalates/holds -- an LLM hiccup can never auto-finalize a valuation."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :adjusterllm-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
