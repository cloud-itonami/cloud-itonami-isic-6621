(ns adjustment.facts
  "Per-jurisdiction independent loss-adjustment/valuation-methodology
  catalog -- the G2-style spec-basis table the Loss Adjustment Governor
  checks every jurisdiction/assess proposal against ('did the advisor
  cite an OFFICIAL public source for this jurisdiction's licensing/
  methodology requirements, or did it invent one?').

  Coverage is reported HONESTLY (see `coverage`), the same discipline
  `cloud-itonami-isic-6511`'s `underwriting.facts` / `cloud-itonami-isic-
  6512`'s `casualty.facts` use: a jurisdiction not in this table has NO
  spec-basis, full stop -- the advisor must not fabricate one, and the
  governor holds if it tries.

  Seed values are drawn from each jurisdiction's official adjuster-
  licensing/valuation-standard authority (see `:provenance`); they are a
  STARTING catalog, not a from-scratch survey of all ~194 jurisdictions.
  Extending coverage is additive: add one map to `catalog`, cite a real
  source, done -- never invent a jurisdiction's requirements to make
  coverage look bigger.")

(def catalog
  "iso3 -> requirement map. `:required-evidence` is the generic
  evidence set an independent adjuster attaches to a valuation in some
  form; `:legal-basis` / `:owner-authority` / `:provenance` are the G2
  citation the governor requires before any :jurisdiction/assess
  proposal can commit."
  {"JPN" {:name "Japan"
          :owner-authority "金融庁 (Financial Services Agency)"
          :legal-basis "保険業法 (Insurance Business Act) -- 損害保険登録鑑定人制度 (Registered Non-Life Insurance Adjuster system)"
          :national-spec "日本損害保険協会 鑑定人倫理綱領 (General Insurance Association of Japan adjuster code of ethics)"
          :provenance "https://www.fsa.go.jp/"
          :required-evidence ["現場写真 (site photographs)"
                              "修理・交換見積書 (repair/replacement estimate)"
                              "所有権を証する書類 (proof of ownership)"
                              "類似事例・時価資料 (comparable value data)"]}
   "USA-NY" {:name "United States -- New York (exemplar; federalism note below)"
             :owner-authority "New York State Department of Financial Services (NYDFS)"
             :legal-basis "New York Insurance Law Article 21 (Independent Adjusters)"
             :national-spec "NYDFS independent-adjuster licensing regulation"
             :provenance "https://www.dfs.ny.gov/"
             :notes "No federal insurance regulator -- independent adjuster licensing is regulated per-state; New York is an exemplar, not a national authority."
             :required-evidence ["Site photographs"
                                 "Itemized repair/replacement estimate"
                                 "Proof of ownership"
                                 "Comparable sales/cost data"]}
   "GBR" {:name "United Kingdom"
          :owner-authority "Royal Institution of Chartered Surveyors (RICS) / Financial Conduct Authority (FCA)"
          :legal-basis "Financial Services and Markets Act 2000 (claims management regulation)"
          :national-spec "RICS Red Book valuation standards"
          :provenance "https://www.rics.org/"
          :required-evidence ["Site photographs"
                              "Itemized repair/replacement estimate"
                              "Proof of ownership"
                              "Comparable valuation evidence"]}
   "DEU" {:name "Germany"
          :owner-authority "Bundesanstalt für Finanzdienstleistungsaufsicht (BaFin)"
          :legal-basis "Versicherungsvertragsgesetz (VVG) -- unabhängiger Sachverständiger (independent expert)"
          :national-spec "DIN-Normen für Schadengutachten (DIN standards for damage appraisal)"
          :provenance "https://www.bafin.de/"
          :required-evidence ["Fotodokumentation (photo documentation)"
                              "Reparatur-/Wiederbeschaffungskostenvoranschlag (repair/replacement cost estimate)"
                              "Eigentumsnachweis (proof of ownership)"
                              "Vergleichswertdaten (comparable value data)"]}})

(defn spec-basis
  "The jurisdiction's requirement map, or nil -- nil means NO spec-basis,
  and the governor must hold any proposal that tries to finalize a
  valuation on it."
  [iso3]
  (get catalog iso3))

(defn coverage
  "Honest coverage report: how many of the requested jurisdictions actually
  have a spec-basis entry. Never report a missing jurisdiction as covered."
  ([] (coverage (keys catalog)))
  ([iso3s]
   (let [have (filter catalog iso3s)
         missing (remove catalog iso3s)]
     {:requested (count iso3s)
      :covered (count have)
      :covered-jurisdictions (vec (sort have))
      :missing-jurisdictions (vec (sort missing))
      :note (str "cloud-itonami-isic-6621 R0: " (count catalog)
                 " jurisdictions seeded with an official spec-basis. "
                 "This is a starting catalog, not a survey of all ~194 "
                 "jurisdictions -- extend `adjustment.facts/catalog`, "
                 "never fabricate a jurisdiction's requirements.")})))

(defn required-evidence-satisfied?
  "Does `submitted` (a set/coll of evidence keywords or strings) satisfy
  every evidence item listed for `iso3`? Missing spec-basis -> never
  satisfied."
  [iso3 submitted]
  (when-let [{:keys [required-evidence]} (spec-basis iso3)]
    (let [need (count required-evidence)
          have (count (filter (set submitted) required-evidence))]
      (= need have))))

(defn evidence-checklist [iso3]
  (:required-evidence (spec-basis iso3) []))
