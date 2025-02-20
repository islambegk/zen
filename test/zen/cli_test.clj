(ns zen.cli-test
  (:require [zen.cli :as sut]
            [zen.core :as z]
            [clojure.string :as str]
            [clojure.edn]
            [clojure.test :as t]
            [zen.test-utils]
            [zen.cli-tools :as cli-tools]
            [matcho.core :as matcho]))


(def zen-packages-fixtures
  {'my-dep {:deps '#{}
            :zrc '#{{:ns my-dep
                     :import #{}

                     tag {:zen/tags #{zen/schema zen/tag}
                          :type zen/map
                          :require #{:a}
                          :keys {:a {:type zen/string}}}}}}

   'my-dep-fork {:deps '#{}
                 :zrc '#{{:ns my-dep
                          :import #{}

                          new-sym {:i-am-forked :not-the-original-repo}

                          tag {:zen/tags #{zen/schema zen/tag}
                               :type zen/map
                               :require #{:a}
                               :keys {:a {:type zen/string}}}}}}})


(defn sut-cmd [cmd-name & args]
  (let [opts      (last args)
        cmd-args  (butlast args)
        test-opts {:return-fn identity}
        cli-opts  (merge test-opts opts)]
    (assert (and (map? opts)
                 (contains? opts :pwd))
            "Test cmd calls must specify PWD")

    (sut/cli-main (cons cmd-name cmd-args) cli-opts)))


(t/deftest cli-usecases-test
  (def test-dir-path "/tmp/zen-cli-test")
  (def my-package-dir-path (str test-dir-path "/my-package/"))
  (def dependency-dir-path (str test-dir-path "/my-dep/"))
  (def dependency-fork-dir-path (str test-dir-path "/my-dep-fork/"))

  (zen.test-utils/rm-fixtures test-dir-path)
  (zen.test-utils/mk-fixtures test-dir-path zen-packages-fixtures)

  (t/testing "wrong command"
    (matcho/match (sut-cmd "AAAAAAAAAAAAAA" {:pwd test-dir-path})
                  {::cli-tools/status :error})

    (matcho/match (sut-cmd "init" "a" "b" "c" "d" "too many args" {:pwd test-dir-path})
                  {::cli-tools/status :error}))

  (t/testing "create template"
    (zen.test-utils/mkdir my-package-dir-path)

    (matcho/match (sut-cmd "init" "my-package" {:pwd my-package-dir-path})
                  {::cli-tools/code :initted-new, ::cli-tools/status :ok})

    (matcho/match (zen.test-utils/fs-tree->tree-map my-package-dir-path)
                  {"zen-package.edn" some?
                   "zrc"             {"my-package.edn" some?}
                   ".git"            {}
                   ".gitignore"      some?}))


  (t/testing "try to create new template over existing directory, get error that repo already exists"
    (matcho/match (sut-cmd "init" "my-package" {:pwd my-package-dir-path})
                  {::cli-tools/status :ok, ::cli-tools/code :already-exists}))


  (t/testing "check new template no errors"
    (matcho/match (sut-cmd "errors" {:pwd my-package-dir-path})
                  {::cli-tools/status :ok}))


  (zen.test-utils/git-init-commit my-package-dir-path)


  (t/testing "declare a symbol with tag and import ns from a dependency"
    (t/testing "no changes are made yet"
      (matcho/match (sut-cmd "changes" {:pwd my-package-dir-path})
                    {::cli-tools/status :ok
                     ::cli-tools/result {:status :unchanged}}))

    (t/testing "the symbol doesn't exist before update"
      (matcho/match (sut-cmd "get-symbol" "my-package/sym" {:pwd my-package-dir-path})
                    {::cli-tools/status :ok
                     ::cli-tools/result nil?})

      (matcho/match (sut-cmd "get-tag" "my-dep/tag" {:pwd my-package-dir-path})
                    {::cli-tools/status :ok
                     ::cli-tools/result empty?}))

    (zen.test-utils/update-zen-file (str my-package-dir-path "/zrc/my-package.edn")
                     #(assoc %
                             :import #{'my-dep}
                             'sym {:zen/tags #{'my-dep/tag}
                                   :a "a"}))

    (t/testing "get the symbol"
      (matcho/match (sut-cmd "get-symbol" "my-package/sym" {:pwd my-package-dir-path})
                    {::cli-tools/status :ok
                     ::cli-tools/result {:zen/tags #{'my-dep/tag}
                                         :a "a"}}))

    (t/testing "get the symbol by the tag"
      (matcho/match (sut-cmd "get-tag" "my-dep/tag" {:pwd my-package-dir-path})
                    {::cli-tools/status :ok
                     ::cli-tools/result                     #{'my-package/sym}}))

    (t/testing "see changes"
      (matcho/match (sut-cmd "changes" {:pwd my-package-dir-path})
                    {::cli-tools/status :ok
                     ::cli-tools/result {:status :changed
                                         :changes [{} nil]}})

      (zen.test-utils/git-commit my-package-dir-path "zrc/" "Add my-dep/new-sym")

      (matcho/match (sut-cmd "changes" {:pwd my-package-dir-path})
                    {::cli-tools/status :ok
                     ::cli-tools/result {:status :unchanged}})))


  (t/testing "specify a dependency in zen-package.edn"
    (t/testing "check errors, see that namespace the dependency ns is missing"

      (matcho/match (sut-cmd "errors" {:pwd my-package-dir-path})
                    {::cli-tools/status :ok
                     ::cli-tools/result [{:missing-ns 'my-dep}
                                         {:unresolved-symbol 'my-dep/tag}
                                         nil]}))

    (t/testing "can safely pull deps without deps specified"
      (matcho/match (sut-cmd "pull-deps" {:pwd my-package-dir-path})
                    {::cli-tools/status :ok
                     ::cli-tools/result {:status :ok
                                         :code :nothing-to-pull
                                         :deps empty?}}))

    (zen.test-utils/update-zen-file (str my-package-dir-path "/zen-package.edn")
                                    #(assoc % :deps {'my-dep dependency-dir-path}))

    (t/testing "do pull-deps & check for errors, should be no errors"
      (matcho/match (sut-cmd "pull-deps" {:pwd my-package-dir-path})
                    {::cli-tools/status :ok
                     ::cli-tools/result {:status :ok
                                         :code :pulled
                                         :deps #{'my-dep}}})

      (matcho/match (sut-cmd "errors" {:pwd my-package-dir-path})
                    {::cli-tools/status :ok
                     ::cli-tools/result nil?}))

    (t/testing "do pull-deps again should be no errors and no changes"

      (matcho/match (sut-cmd "pull-deps" {:pwd my-package-dir-path})
                    {::cli-tools/status :ok})

      (matcho/match (sut-cmd "errors" {:pwd my-package-dir-path})
                    {::cli-tools/status :ok
                     ::cli-tools/result nil?}))


    (t/testing "change repo url, pull"
      (zen.test-utils/update-zen-file (str my-package-dir-path "/zen-package.edn")
                                      #(assoc % :deps {'my-dep dependency-fork-dir-path}))

      (matcho/match (sut-cmd "pull-deps" {:pwd my-package-dir-path})
                    {::cli-tools/status :ok
                     ::cli-tools/result {:status :ok
                                         :code :pulled
                                         :deps #{'my-dep}}})

      (matcho/match (sut-cmd "errors" {:pwd my-package-dir-path})
                    {::cli-tools/status :ok
                     ::cli-tools/result nil?})

      (matcho/match (sut-cmd "get-symbol" "my-dep/new-sym" {:pwd my-package-dir-path})
                    {::cli-tools/result {:i-am-forked :not-the-original-repo}})))


  (t/testing "commit update to the dependency"
    (zen.test-utils/update-zen-file (str dependency-fork-dir-path "/zrc/my-dep.edn")
                                    #(assoc-in % ['new-sym :i-am-forked] :fork-updated))

    (zen.test-utils/git-commit dependency-fork-dir-path "zrc/" "Update my-dep/new-sym")

    (t/testing "do pull-deps and see the update"
      (matcho/match (sut-cmd "pull-deps" {:pwd my-package-dir-path})
                    {::cli-tools/status :ok
                     ::cli-tools/result {:status :ok
                                         :code :pulled
                                         :deps #{'my-dep}}})

      (matcho/match (sut-cmd "errors" {:pwd my-package-dir-path})
                    {::cli-tools/status :ok
                     ::cli-tools/result nil?})

      (matcho/match (sut-cmd "get-symbol" "my-dep/new-sym" {:pwd my-package-dir-path})
                    {::cli-tools/result {:i-am-forked :fork-updated}})))


  (t/testing "use validate command to validate some data"
    (matcho/match (sut-cmd "validate" "#{my-dep/tag}" "{}" {:pwd my-package-dir-path})
                  {::cli-tools/result {:errors [{} nil]}})))


(defn sut-repl [opts]
  (assert (contains? opts :pwd) "Test repl must specify PWD")

  (let [timeout 5000

        return-atom-promise (atom (promise))
        input-atom-promise  (atom (promise))

        get&promise-new (fn [atom-promise timeout timeout-value]
                          (let [result (deref @atom-promise timeout timeout-value)]
                            (reset! atom-promise (promise))
                            result))

        eval-in-repl! (fn [s]
                        (deliver @input-atom-promise s)
                        (get&promise-new return-atom-promise
                                         timeout
                                         {::cli-tools/status :error, ::cli-tools/code :eval-timeout}))

        repl-opts (merge {:prompt-fn (fn [])
                          :read-fn   #(get&promise-new input-atom-promise timeout "exit")
                          :return-fn #(deliver @return-atom-promise %)}
                         opts)

        start-repl! (fn []
                      #_(future (sut/repl sut/commands repl-opts))
                      (future (sut/cli-main nil repl-opts)))]
    (start-repl!)
    eval-in-repl!))


(def repl-zen-packages-fixtures
  {'my-dep {:deps '#{}
            :zrc '#{{:ns my-dep
                     :import #{}

                     tag {:zen/tags #{zen/schema zen/tag}
                          :type zen/map
                          :require #{:a}
                          :keys {:a {:type zen/string}}}}}}})


(t/deftest repl-test
  (def test-dir-path "/tmp/zen-cli-repl-test")
  (def my-package-dir-path (str test-dir-path "/my-package/"))
  (def dependency-dir-path (str test-dir-path "/my-dep/"))

  (zen.test-utils/rm-fixtures test-dir-path)
  (zen.test-utils/mk-fixtures test-dir-path repl-zen-packages-fixtures)

  (def stop-repl-atom (atom false))

  (def eval-in-repl! (sut-repl {:pwd my-package-dir-path
                                :stop-repl-atom stop-repl-atom}))

  (t/testing "init"
    (zen.test-utils/mkdir my-package-dir-path)

    (matcho/match (eval-in-repl! "init my-package")
                  {::cli-tools/status :ok, ::cli-tools/code :initted-new})

    (zen.test-utils/git-init-commit my-package-dir-path))
  #_(throw (Exception. "HALT"))

  (zen.test-utils/update-zen-file (str my-package-dir-path "/zrc/my-package.edn")
                                  #(assoc %
                                          :import #{'my-dep}
                                          'sym {:zen/tags #{'my-dep/tag}
                                                :a "a"}))

  (t/testing "errors"
    (matcho/match (eval-in-repl! "errors")
                  {::cli-tools/result [{:missing-ns 'my-dep}
                                       {:unresolved-symbol 'my-dep/tag}
                                       nil]}))

  (zen.test-utils/update-zen-file (str my-package-dir-path "/zen-package.edn")
                                  #(assoc % :deps {'my-dep dependency-dir-path}))

  (t/testing "pull-deps"
    (matcho/match (eval-in-repl! "pull-deps")
                  {::cli-tools/status :ok
                   ::cli-tools/result {:code :pulled, :deps #{'my-dep}}})

    (matcho/match (eval-in-repl! "errors")
                  {::cli-tools/result empty?}))

  (t/testing "changes"
    (matcho/match (eval-in-repl! "changes")
                  {::cli-tools/result
                   {:changes
                    [{:type :namespace/new, :namespace 'my-dep}
                     {:type :symbol/new, :symbol 'my-package/sym}
                     nil]}}))

  (t/testing "validate"
    (matcho/match (eval-in-repl! "validate #{my-dep/tag} {:a :wrong-type}")
                  {::cli-tools/result {:errors [{} nil]}})

    (matcho/match (eval-in-repl! "validate #{my-dep/tag} {:a \"correct-type\"}")
                  {:errors empty?}))

  (t/testing "get-symbol"
    (matcho/match (eval-in-repl! "get-symbol my-package/sym")
                  {::cli-tools/result {:zen/name 'my-package/sym}}))

  (t/testing "get-tag"
    (matcho/match (eval-in-repl! "get-tag my-dep/tag")
                  {::cli-tools/result #{'my-package/sym}}))

  (t/testing "exit"
    (matcho/match (eval-in-repl! "exit")
                  {::cli-tools/result {:status :ok, :code :exit}})

    (t/is (true? @stop-repl-atom)))

  #_"NOTE: this is safety stop if repl eval couldn't stop for some reason"
  (reset! stop-repl-atom true))


(t/deftest build-zen-project-into-zip
  (def test-dir-path "/tmp/zen-cli-build-cmd-test")
  (def my-package-dir-path (str test-dir-path "/my-package/"))
  (def build-dir "build-dir")
  (zen.test-utils/rm-fixtures test-dir-path)

  (t/testing "Initialize project"
    (zen.test-utils/mkdir my-package-dir-path)
    (matcho/match (sut-cmd "init" "my-package" {:pwd my-package-dir-path})
                  {::cli-tools/code :initted-new ::cli-tools/status :ok})

    (matcho/match (zen.test-utils/fs-tree->tree-map my-package-dir-path)
                  {"zen-package.edn" some?
                   "zrc"             {"my-package.edn" some?}
                   ".git"            {}
                   ".gitignore"      some?}))

  (t/testing "Building project archive"
    (matcho/match (sut-cmd "build" build-dir "zen-project" {:pwd my-package-dir-path})
                  {::cli-tools/result {:status :ok :code :builded}
                   ::cli-tools/status :ok})

    (t/testing "Can see project-archive on fs-tree"
      (matcho/match (zen.test-utils/fs-tree->tree-map my-package-dir-path)
                    {"build-dir" {"zen-project.zip" some?}
                     "zen-package.edn" some?
                     "zrc"             {"my-package.edn" some?}
                     ".git"            {}
                     ".gitignore"      some?}))

    (t/testing "Archive contains only zen-project files, .git is ommited"
      (matcho/match
        (zen.test-utils/zip-archive->fs-tree (str my-package-dir-path \/ build-dir \/ "zen-project.zip"))
        {"zrc" {"my-package.edn" {}}}))))
